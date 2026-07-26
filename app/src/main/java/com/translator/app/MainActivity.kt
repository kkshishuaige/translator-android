package com.translator.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var recordBtn: Button
    private lateinit var statusText: TextView
    private lateinit var modelStatusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var translationText: TextView
    private lateinit var sourceLang: Spinner
    private lateinit var targetLang: Spinner
    private lateinit var historyLayout: LinearLayout
    private lateinit var downloadModelBtn: Button
    private lateinit var downloadProgress: ProgressBar

    // 模型文件（使用 tiny 模型 75MB，base 141MB 在部分设备上有空指针崩溃）
    private val modelFileName = "ggml-tiny.bin"
    private val modelDownloadUrl = "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"

    private var isRecording = false
    private var whisperReady = false
    private var gemmaReady = false

    // 录音
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var audioBuffer = mutableListOf<Short>()

    // 录音参数
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    // 每段最大时长（秒）
    private val maxSegmentSec = 5
    // 静音检测阈值
    private val silenceThreshold = 500
    // 静音超时（秒）
    private val silenceTimeoutSec = 2

    private val transcriptBuffer = StringBuilder()
    private val translationBuffer = StringBuilder()
    private val historyItems = mutableListOf<HistoryItem>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    data class LangInfo(val locale: String, val displayName: String, val translateCode: String,
                        val whisperCode: String)
    data class HistoryItem(val source: String, val target: String, val time: String,
                           val sourceLang: String, val targetLang: String)

    // 语言配置
    private val languages = listOf(
        LangInfo("zh", "中文", "Chinese", "zh"),
        LangInfo("en-US", "English", "English", "en"),
        LangInfo("ru-RU", "Русский", "Russian", "ru"),
        LangInfo("ar", "العربية", "Arabic", "ar"),
        LangInfo("es-ES", "Español", "Spanish", "es"),
        LangInfo("fr-FR", "Français", "French", "fr")
    )

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_gemma4)

        recordBtn = findViewById(R.id.recordBtn)
        statusText = findViewById(R.id.statusText)
        modelStatusText = findViewById(R.id.modelStatusText)
        transcriptText = findViewById(R.id.transcriptText)
        translationText = findViewById(R.id.translationText)
        sourceLang = findViewById(R.id.sourceLang)
        targetLang = findViewById(R.id.targetLang)
        historyLayout = findViewById(R.id.historyLayout)
        downloadModelBtn = findViewById(R.id.downloadModelBtn)
        downloadProgress = findViewById(R.id.downloadProgress)

        val names = languages.map { it.displayName }
        sourceLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        targetLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        sourceLang.setSelection(0)
        targetLang.setSelection(1)

        checkPermission()

        // 加载 Gemma 4（ML Kit 翻译）
        initGemma4()

        // 下载/加载模型
        downloadModelBtn.setOnClickListener {
            startModelDownload()
        }

        recordBtn.setOnClickListener {
            if (!whisperReady) {
                Toast.makeText(this, "请先加载语音模型", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isRecording) stopRecording() else startRecording()
        }

        // 启动时检查是否已有模型
        checkExistingModel()
    }

    /**
     * 检查 app 私有目录是否已有模型文件，有则自动加载
     */
    private fun checkExistingModel() {
        val modelFile = File(filesDir, modelFileName)
        if (modelFile.exists() && modelFile.length() > 1000000) {
            modelStatusText.text = "✅ 模型已存在，正在加载…"
            downloadModelBtn.text = "⏳ 加载中…"
            downloadModelBtn.isEnabled = false
            loadWhisperModel(modelFile.absolutePath)
        } else {
            modelStatusText.text = "需要下载语音模型 (148MB)"
        }
    }

    /**
     * 开始下载模型
     */
    private fun startModelDownload() {
        downloadModelBtn.isEnabled = false
        downloadProgress.visibility = android.view.View.VISIBLE
        downloadProgress.progress = 0
        modelStatusText.text = "⏳ 准备下载…"
        downloadModelBtn.text = "⏳ 下载中…"

        scope.launch(Dispatchers.IO) {
            try {
                val url = URL(modelDownloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 30000
                conn.requestMethod = "GET"
                conn.connect()

                val totalSize = conn.contentLengthLong
                val inputStream = conn.inputStream
                val targetFile = File(filesDir, modelFileName)
                val outputStream = FileOutputStream(targetFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead: Long = 0
                var lastPercent = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    if (totalSize > 0) {
                        val percent = ((totalRead * 100) / totalSize).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            val mbRead = totalRead / (1024 * 1024)
                            val mbTotal = totalSize / (1024 * 1024)
                            runOnUiThread {
                                downloadProgress.progress = percent
                                modelStatusText.text = "📥 下载中 $percent% (${mbRead}MB / ${mbTotal}MB)"
                            }
                        }
                    }
                }

                outputStream.close()
                inputStream.close()

                runOnUiThread {
                    modelStatusText.text = "✅ 下载完成！正在加载模型…"
                    downloadProgress.progress = 100
                }

                // 自动加载模型
                loadWhisperModel(targetFile.absolutePath)
            } catch (e: Exception) {
                runOnUiThread {
                    modelStatusText.text = "❌ 下载失败：${e.message}"
                    downloadModelBtn.isEnabled = true
                    downloadModelBtn.text = "📥 重新下载 (148MB)"
                    downloadProgress.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun initGemma4() {
        modelStatusText.text = "⏳ 初始化 Gemma 4…"
        modelStatusText.setTextColor(android.graphics.Color.parseColor("#FFA500"))

        scope.launch(Dispatchers.IO) {
            try {
                Generation.getClient()
                gemmaReady = true
                runOnUiThread {
                    modelStatusText.text = "🟢 Gemma 4 就绪（本地翻译）"
                    modelStatusText.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                    updateUiStatus()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    modelStatusText.text = "⚠️ Gemma 4 未就绪：${e.message}"
                    modelStatusText.setTextColor(android.graphics.Color.parseColor("#FF9800"))
                }
            }
        }
    }

    private fun loadWhisperModel(path: String) {
        val modelFile = File(path)
        if (!modelFile.exists()) {
            modelStatusText.text = "❌ 模型文件不存在：$path"
            downloadModelBtn.isEnabled = true
            downloadModelBtn.text = "📥 下载模型 (148MB)"
            return
        }

        statusText.text = "⏳ 加载语音模型…（148MB，请稍候）"

        scope.launch(Dispatchers.IO) {
            val success = WhisperModel.load(path)
            runOnUiThread {
                if (success) {
                    whisperReady = true
                    downloadModelBtn.text = "✅ 模型已加载"
                    modelStatusText.text = "🟢 语音模型就绪"
                    statusText.text = "语音模型已加载 ✅"
                    Toast.makeText(this@MainActivity, "模型加载成功！", Toast.LENGTH_SHORT).show()
                    updateUiStatus()
                } else {
                    downloadModelBtn.isEnabled = true
                    downloadModelBtn.text = "📥 下载模型 (148MB)"
                    modelStatusText.text = "❌ 模型加载失败，请重新下载"
                    statusText.text = "❌ 模型加载失败"
                    Toast.makeText(this@MainActivity, "模型加载失败，请检查 logcat", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateUiStatus() {
        val whisperStatus = if (whisperReady) "🟢" else "🔴"
        val gemmaStatus = if (gemmaReady) "🟢" else "🔴"
        statusText.text = "语音${whisperStatus} 翻译${gemmaStatus} ${if (whisperReady && gemmaReady) "就绪，可以开始同传" else ""}"
    }

    /**
     * 使用 Gemma 4 本地翻译
     */
    private suspend fun translateWithGemma4(text: String, sourceLang: String, targetLang: String): String {
        if (!gemmaReady) return text
        if (sourceLang == targetLang) return text

        return withContext(Dispatchers.IO) {
            try {
                val prompt = """
                    Translate the following text from $sourceLang to $targetLang.
                    Output ONLY the translation, no extra text or explanation.
                    
                    Text: $text
                """.trimIndent()

                val response = Generation.getClient().generateContent(prompt)
                response.candidates.firstOrNull()?.text?.trim() ?: text
            } catch (e: Exception) {
                text
            }
        }
    }

    // ========== 录音和识别 ==========

    private fun startRecording() {
        if (isRecording) return
        if (!whisperReady) {
            Toast.makeText(this, "语音模型未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要麦克风权限", Toast.LENGTH_SHORT).show()
            return
        }

        isRecording = true
        transcriptBuffer.clear()
        translationBuffer.clear()
        audioBuffer.clear()

        recordBtn.text = "⏹ 停止同传"
        recordBtn.setBackgroundTintList(
            ContextCompat.getColorStateList(this, android.R.color.holo_red_light))
        statusText.text = "🎤 录音中…"

        // 启动录音线程
        recordingJob = scope.launch(Dispatchers.IO) {
            startAudioRecording()
        }
    }

    private fun startAudioRecording() {
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                runOnUiThread {
                    statusText.text = "❌ 麦克风初始化失败"
                    stopRecording()
                }
                return
            }

            audioRecord?.startRecording()

            val audioBuf = ShortArray(bufferSize / 2)
            var silenceFrames = 0
            val maxSilenceFrames = (sampleRate * silenceTimeoutSec) / (bufferSize / 2)
            val maxSegmentSamples = sampleRate * maxSegmentSec

            while (isRecording) {
                val bytesRead = audioRecord?.read(audioBuf, 0, audioBuf.size) ?: -1
                if (bytesRead <= 0) continue

                // 检查音量
                var maxSample = 0
                for (i in 0 until bytesRead) {
                    val sample = abs(audioBuf[i].toInt())
                    if (sample > maxSample) maxSample = sample
                }

                // 收集音频
                synchronized(audioBuffer) {
                    for (i in 0 until bytesRead) {
                        audioBuffer.add(audioBuf[i])
                    }
                }

                if (maxSample < silenceThreshold) {
                    silenceFrames++
                } else {
                    silenceFrames = 0
                }

                // 判断是否需要转录
                val currentSize: Int
                synchronized(audioBuffer) { currentSize = audioBuffer.size }

                val shouldTranscribe = (silenceFrames >= maxSilenceFrames && currentSize > sampleRate) // 静音超时且有声音
                        || currentSize >= maxSegmentSamples  // 达到最大时长

                if (shouldTranscribe && currentSize > sampleRate / 2) {
                    val audioCopy: List<Short>
                    synchronized(audioBuffer) {
                        audioCopy = audioBuffer.toList()
                        audioBuffer.clear()
                    }
                    silenceFrames = 0

                    // 转录
                    runOnUiThread { statusText.text = "🔄 识别中…" }
                    transcribeAudio(audioCopy)
                    runOnUiThread { statusText.text = "🎤 继续录音…" }
                }

                // 更新音量指示
                if (isRecording) {
                    val db = if (maxSample > 0) (20 * Math.log10(maxSample.toDouble() / 32768.0)).toInt() else -100
                    runOnUiThread {
                        statusText.text = "🎤 录音中 ${if (db > -30) "🔊" else if (db > -50) "🔉" else "🔈"}"
                    }
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                statusText.text = "❌ 录音错误：${e.message}"
            }
        } finally {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }
    }

    private fun transcribeAudio(audioSamples: List<Short>) {
        if (audioSamples.isEmpty()) return

        // 转成 float 数组
        val floatData = FloatArray(audioSamples.size)
        for (i in audioSamples.indices) {
            floatData[i] = audioSamples[i].toFloat() / 32768.0f
        }

        val langCode = languages[sourceLang.selectedItemPosition].whisperCode

        scope.launch(Dispatchers.IO) {
            try {
                val text = WhisperModel.transcribeAudio(floatData, langCode)
                if (text.isNotEmpty()) {
                    runOnUiThread {
                        transcriptBuffer.append(text).append("\n")
                        transcriptText.text = transcriptBuffer.toString()
                    }

                    // 翻译
                    if (gemmaReady) {
                        val sourceCode = languages[sourceLang.selectedItemPosition].translateCode
                        val targetCode = languages[targetLang.selectedItemPosition].translateCode
                        val translation = translateWithGemma4(text, sourceCode, targetCode)
                        runOnUiThread {
                            translationBuffer.append(translation).append("\n")
                            translationText.text = translationBuffer.toString()
                            addHistory(text, translation)
                        }
                    } else {
                        runOnUiThread {
                            addHistory(text, "")
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    transcriptBuffer.append("[识别错误: ${e.message}]\n")
                    transcriptText.text = transcriptBuffer.toString()
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        statusText.text = "⏹ 已停止"
        recordBtn.text = "🎤 开始同传"
        recordBtn.setBackgroundTintList(
            ContextCompat.getColorStateList(this, android.R.color.holo_blue_dark))
    }

    // ========== UI 辅助 ==========

    private fun addHistory(src: String, tgt: String) {
        val sourceName = languages.getOrNull(sourceLang.selectedItemPosition)?.displayName ?: ""
        val targetName = languages.getOrNull(targetLang.selectedItemPosition)?.displayName ?: ""
        historyItems.add(0, HistoryItem(src, tgt, timeFormatter.format(Date()), sourceName, targetName))
        if (historyItems.size > 100) historyItems.removeLast()
        runOnUiThread {
            historyLayout.removeAllViews()
            for (item in historyItems) {
                val view = layoutInflater.inflate(R.layout.history_item, historyLayout, false)
                view.findViewById<TextView>(R.id.historySource).text = "[${item.sourceLang}] ${item.source}"
                view.findViewById<TextView>(R.id.historyTarget).text = "[${item.targetLang}] ${item.target}"
                view.findViewById<TextView>(R.id.historyTime).text = item.time
                historyLayout.addView(view)
            }
        }
    }

    private fun checkPermission() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET)
            != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.INTERNET)

        if (permissions.isNotEmpty())
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        recordingJob?.cancel()
        scope.cancel()
        WhisperModel.unload()
    }
}

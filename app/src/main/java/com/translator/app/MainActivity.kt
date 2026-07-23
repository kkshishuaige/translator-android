package com.translator.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.genai.prompt.GenerativeAudioPrompt
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.GenerativeModelFidelity
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var recordBtn: Button
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var translationText: TextView
    private lateinit var sourceLang: Spinner
    private lateinit var targetLang: Spinner
    private lateinit var historyLayout: LinearLayout
    private lateinit var modelStatusText: TextView

    private var isRecording = false
    private var recordingJob: Job? = null

    // 录音参数
    private val sampleRate = 16000
    private val bufferSize = 4096
    private val silenceThreshold = 0.035
    private val silenceTimeoutMs = 2500L
    private var lastVoiceTime = 0L
    private var isInSilence = false
    private var segmentBuffer = ByteArrayOutputStream()
    private var isSegmentProcessing = false

    // Gemma 4 模型
    private var generativeModel: GenerativeModel? = null
    private var gemmaModelReady = false

    private val transcriptBuffer = StringBuilder()
    private val translationBuffer = StringBuilder()
    private val historyItems = mutableListOf<HistoryItem>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    data class LangInfo(val displayName: String, val translateCode: String)

    data class HistoryItem(
        val source: String,
        val target: String,
        val time: String,
        val sourceLang: String,
        val targetLang: String
    )

    // 6 种语言
    private val languages = listOf(
        LangInfo("中文", "Chinese"),
        LangInfo("English", "English"),
        LangInfo("Русский", "Russian"),
        LangInfo("العربية", "Arabic"),
        LangInfo("Español", "Spanish"),
        LangInfo("Français", "French")
    )

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordBtn = findViewById(R.id.recordBtn)
        statusText = findViewById(R.id.statusText)
        transcriptText = findViewById(R.id.transcriptText)
        translationText = findViewById(R.id.translationText)
        sourceLang = findViewById(R.id.sourceLang)
        targetLang = findViewById(R.id.targetLang)
        historyLayout = findViewById(R.id.historyLayout)
        modelStatusText = findViewById(R.id.modelStatusText)

        val names = languages.map { it.displayName }
        sourceLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        targetLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        sourceLang.setSelection(0)
        targetLang.setSelection(1)

        checkPermission()

        // 初始化 Gemma 4 模型
        initGemmaModel()

        recordBtn.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
    }

    private fun initGemmaModel() {
        modelStatusText.text = "⏳ 初始化 Gemma 4 模型…"
        modelStatusText.setTextColor(android.graphics.Color.parseColor("#FFA500"))

        scope.launch(Dispatchers.IO) {
            try {
                val model = GenerativeModel(
                    modelName = "gemma-4-e2b-quantized",
                    apiKey = "",
                    fidelity = GenerativeModelFidelity.FASTEST
                )

                generativeModel = model
                gemmaModelReady = true

                runOnUiThread {
                    modelStatusText.text = "🟢 Gemma 4 E2B 就绪 · 本地识别+翻译"
                    modelStatusText.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                    statusText.text = "就绪，点击开始同传"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    modelStatusText.text = "❌ 模型未就绪：${e.message}"
                    modelStatusText.setTextColor(android.graphics.Color.parseColor("#FF4444"))
                    statusText.text = "⚠️ 请先通过 AI Edge Gallery 下载 Gemma 4 E2B 模型"
                }
            }
        }
    }

    private fun startRecording() {
        if (isRecording) return
        if (!gemmaModelReady || generativeModel == null) {
            Toast.makeText(this, "Gemma 4 模型未就绪，请在 AI Edge Gallery 中下载", Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "需要麦克风权限", Toast.LENGTH_SHORT).show()
            return
        }

        isRecording = true
        transcriptBuffer.clear()
        translationBuffer.clear()
        segmentBuffer = ByteArrayOutputStream()
        isSegmentProcessing = false
        lastVoiceTime = System.currentTimeMillis()

        updateUIForRecording(true)
        updateStatus("🎤 录音中… 静音自动识别翻译")

        recordingJob = scope.launch(Dispatchers.IO) {
            val minBufferSize = maxOf(
                AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                ),
                bufferSize * 4
            )

            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize
                )
            } catch (e: Exception) {
                runOnUiThread {
                    updateStatus("❌ 录音初始化失败")
                    isRecording = false
                    updateUIForRecording(false)
                }
                return@launch
            }

            audioRecord.startRecording()
            val buffer = ShortArray(bufferSize)

            while (isRecording && isActive) {
                val read = audioRecord.read(buffer, 0, bufferSize)
                if (read > 0) {
                    val rms = calculateRMS(buffer, read)
                    val byteBuf = ByteArray(read * 2)
                    for (i in 0 until read) {
                        byteBuf[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                        byteBuf[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xFF).toByte()
                    }
                    segmentBuffer.write(byteBuf)

                    val now = System.currentTimeMillis()
                    if (rms < silenceThreshold) {
                        if (!isInSilence) {
                            isInSilence = true
                            lastVoiceTime = now
                        }
                        if (now - lastVoiceTime > silenceTimeoutMs && segmentBuffer.size() > sampleRate) {
                            processAudioSegment()
                        }
                    } else {
                        isInSilence = false
                        lastVoiceTime = now
                    }
                }
            }

            try {
                audioRecord.stop()
                audioRecord.release()
            } catch (_: Exception) {}

            processAudioSegment()
        }
    }

    private suspend fun transcribeAndTranslate(
        audioData: ByteArray,
        sourceLangCode: String,
        targetLangCode: String
    ): Pair<String, String> {
        return withContext(Dispatchers.IO) {
            try {
                val model = generativeModel ?: return@withContext Pair("", "")

                val promptText = """
                    You are a professional simultaneous interpreter.
                    Transcribe the speech in $sourceLangCode, then translate it to $targetLangCode.
                    Output format:
                    [Transcription] <transcribed text>
                    [Translation] <translated text>
                    Do NOT add any extra text or explanation.
                """.trimIndent()

                val audioPrompt = GenerativeAudioPrompt(
                    audio = audioData,
                    text = promptText
                )

                val result = model.generateContent(audioPrompt)
                val output = result.text?.trim() ?: ""

                if (output.isEmpty()) return@withContext Pair("", "")

                val transcription = extractTag(output, "Transcription")
                val translation = extractTag(output, "Translation")

                if (transcription.isNotEmpty() && translation.isNotEmpty()) {
                    Pair(transcription, translation)
                } else if (transcription.isNotEmpty()) {
                    Pair(transcription, output)
                } else {
                    Pair("", "")
                }
            } catch (e: Exception) {
                runOnUiThread { updateStatus("⚠️ 推理错误：${e.message}") }
                Pair("", "")
            }
        }
    }

    private fun extractTag(text: String, tag: String): String {
        val regex = Regex("""\[$tag\]\s*(.+?)(?=\n\[|$)""", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(text.trim())
        return match?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun processAudioSegment() {
        if (isSegmentProcessing) return
        val audioData = segmentBuffer.toByteArray()
        segmentBuffer = ByteArrayOutputStream()
        if (audioData.size < sampleRate * 3) return

        isSegmentProcessing = true
        val sourceLangInfo = languages[sourceLang.selectedItemPosition]
        val targetLangInfo = languages[targetLang.selectedItemPosition]

        scope.launch {
            updateStatus("🤖 Gemma 4 处理中…")
            try {
                val result = transcribeAndTranslate(
                    audioData,
                    sourceLangInfo.translateCode,
                    targetLangInfo.translateCode
                )

                if (result.first.isNotEmpty()) {
                    runOnUiThread {
                        transcriptBuffer.append(result.first).append("\n")
                        transcriptText.text = transcriptBuffer.toString()

                        if (result.second.isNotEmpty()) {
                            translationBuffer.append(result.second).append("\n")
                            translationText.text = translationBuffer.toString()
                            addHistory(result.first, result.second)
                        }

                        val scrollView = findViewById<ScrollView>(R.id.scrollView)
                        scrollView?.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                    }
                    updateStatus("🎤 继续监听…")
                } else {
                    updateStatus("🎤 监听中…")
                }
            } catch (_: Exception) {
                updateStatus("🎤 监听中…")
            } finally {
                isSegmentProcessing = false
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        scope.launch {
            delay(1000)
            updateUIForRecording(false)
            updateStatus("✅ 已停止")
        }
    }

    private fun updateUIForRecording(recording: Boolean) {
        runOnUiThread {
            recordBtn.text = if (recording) "⏹ 停止同传" else "🎤 开始同传"
            recordBtn.setBackgroundTintList(
                ContextCompat.getColorStateList(
                    this@MainActivity,
                    if (recording) android.R.color.holo_red_light else android.R.color.holo_blue_dark
                )
            )
        }
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { statusText.text = msg }
    }

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

    private fun calculateRMS(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) sum += abs(buffer[i].toDouble()) / 32767.0
        return sum / length
    }

    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        recordingJob?.cancel()
        scope.cancel()
    }
}

package com.translator.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var recordBtn: Button
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var translationText: TextView
    private lateinit var sourceLang: Spinner
    private lateinit var targetLang: Spinner
    private lateinit var historyLayout: LinearLayout

    private var isRecording = false
    private var recordingJob: Job? = null
    private var segmentProcessJob: Job? = null

    // 录音参数
    private val sampleRate = 16000
    private val bufferSize = 4096
    private val silenceThreshold = 0.035
    private val silenceTimeoutMs = 2500L
    private var lastVoiceTime = 0L
    private var isInSilence = false
    private var segmentBuffer = ByteArrayOutputStream()
    private var isSegmentProcessing = false

    // 识别引擎 — 优先本地 Qwen3-ASR，回退服务器 Whisper
    private var qwenModelLoaded = false
    private var useLocalASR = false

    private val transcriptBuffer = StringBuilder()
    private val translationBuffer = StringBuilder()
    private val historyItems = mutableListOf<HistoryItem>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val serverUrl = "http://111.229.193.192:3000"
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class LangInfo(val locale: String, val displayName: String, val translateCode: String)
    data class HistoryItem(val source: String, val target: String, val time: String,
                           val sourceLang: String, val targetLang: String, val asr: String)

    private val languages = listOf(
        LangInfo("zh", "中文", "zh"),
        LangInfo("en", "English", "en"),
        LangInfo("ru", "Русский", "ru"),
        LangInfo("ar", "العربية", "ar"),
        LangInfo("es", "Español", "es"),
        LangInfo("fr", "Français", "fr")
    )

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ===== llama.cpp JNI =====
    companion object {
        init {
            try {
                System.loadLibrary("llama")
            } catch (_: UnsatisfiedLinkError) {}
        }
    }

    private external fun llamaInit(modelPath: String, nCtx: Int): Long
    private external fun llamaEval(contextPtr: Long, input: FloatArray, nSteps: Int): String
    private external fun llamaRelease(contextPtr: Long)

    private var llamaContext: Long = 0

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

        val names = languages.map { it.displayName }
        sourceLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        targetLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        sourceLang.setSelection(0)
        targetLang.setSelection(1)

        checkPermission()

        // 检查本地 Qwen3-ASR 模型是否存在
        checkLocalModel()

        recordBtn.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
    }

    private fun checkLocalModel() {
        scope.launch(Dispatchers.IO) {
            val modelPaths = listOf(
                Environment.getExternalStorageDirectory().absolutePath + "/qwen3_asr/qwen3_asr_0.6b_q4_k_m.gguf",
                Environment.getExternalStorageDirectory().absolutePath + "/qwen3_asr/qwen3_asr_0.6b.gguf",
                "/sdcard/qwen3_asr/qwen3_asr_0.6b_q4_k_m.gguf",
                "/storage/emulated/0/qwen3_asr/qwen3_asr_0.6b_q4_k_m.gguf"
            )

            var foundModel = ""
            for (p in modelPaths) {
                val f = File(p)
                if (f.exists() && f.length() > 100 * 1024 * 1024) {
                    foundModel = p
                    break
                }
            }

            if (foundModel.isNotEmpty()) {
                runOnUiThread { statusText.text = "⏳ 加载本地模型…" }
                try {
                    llamaContext = llamaInit(foundModel, 2048)
                    if (llamaContext != 0L) {
                        qwenModelLoaded = true
                        useLocalASR = true
                        runOnUiThread {
                            statusText.text = "🟢 本地模型就绪（${foundModel.split("/").last().take(20)}）"
                        }
                        return@launch
                    }
                } catch (e: Exception) {
                    // fallback
                }
            }

            runOnUiThread {
                useLocalASR = false
                statusText.text = "⚡ 使用服务器识别（未找到本地模型）"
            }
        }
    }

    private fun startRecording() {
        if (isRecording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
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
        updateStatus(if (useLocalASR) "🎤 本地识别中…" else "🎤 服务器识别中…")

        recordingJob = scope.launch(Dispatchers.IO) {
            val minBufferSize = maxOf(
                AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                bufferSize * 4
            )

            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize
                )
            } catch (e: Exception) {
                runOnUiThread { updateStatus("❌ 录音初始化失败"); isRecording = false; updateUIForRecording(false) }
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
                            isInSilence = true; lastVoiceTime = now
                        }
                        if (now - lastVoiceTime > silenceTimeoutMs && segmentBuffer.size() > sampleRate) {
                            processAudioSegment()
                        }
                    } else {
                        isInSilence = false; lastVoiceTime = now
                    }
                }
            }

            try { audioRecord.stop(); audioRecord.release() } catch (_: Exception) {}
            processAudioSegment()
        }
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
            try {
                val text = if (useLocalASR && qwenModelLoaded && llamaContext != 0L) {
                    recognizeLocal(audioData, sourceLangInfo.translateCode)
                } else {
                    recognizeServer(audioData, sourceLangInfo.translateCode, targetLangInfo.translateCode)
                }

                if (text.first.isNotEmpty()) {
                    runOnUiThread {
                        transcriptBuffer.append(text.first).append("\n")
                        transcriptText.text = transcriptBuffer.toString()
                        if (text.second.isNotEmpty()) {
                            translationBuffer.append(text.second).append("\n")
                            translationText.text = translationBuffer.toString()
                            addHistory(text.first, text.second, if (useLocalASR) "本地" else "服务器")
                        }
                    }
                }
            } catch (_: Exception) {} finally {
                isSegmentProcessing = false
            }
        }
    }

    // 本地 Qwen3-ASR 识别（用 llama.cpp 推理）
    private fun recognizeLocal(pcmData: ByteArray, language: String): Pair<String, String> {
        try {
            val samples = ShortArray(pcmData.size / 2)
            for (i in samples.indices) {
                val low = pcmData[i * 2].toInt() and 0xFF
                val high = (pcmData[i * 2 + 1].toInt() and 0xFF) shl 8
                samples[i] = (low or high).toShort()
            }

            val floats = FloatArray(samples.size)
            for (i in samples.indices) {
                floats[i] = samples[i].toFloat() / 32768.0f
            }

            val result = llamaEval(llamaContext, floats, floats.size)
            val text = result.trim()

            if (text.isNotEmpty()) {
                val translation = translateServer(text, language)
                return Pair(text, translation)
            }
        } catch (e: Exception) {
            runOnUiThread { updateStatus("⚠️ 本地识别错误，切换到服务器") }
            useLocalASR = false
        }
        return Pair("", "")
    }

    // 服务器 Whisper 识别
    private suspend fun recognizeServer(pcmData: ByteArray, sourceLang: String, targetLang: String): Pair<String, String> {
        return withContext(Dispatchers.IO) {
            try {
                val tempFile = File(cacheDir, "asr_${System.currentTimeMillis()}.wav")
                createWavFile(tempFile, pcmData)

                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("audio", "audio.wav",
                        RequestBody.create("audio/wav".toMediaTypeOrNull(), tempFile))
                    .addFormDataPart("language", sourceLang)
                    .addFormDataPart("target", targetLang)
                    .build()

                val request = Request.Builder().url("$serverUrl/asr").post(body).build()
                val response = okHttpClient.newCall(request).execute()
                val json = JSONObject(response.body?.string() ?: "{}")
                tempFile.delete()

                Pair(json.optString("text", ""), json.optString("translation", ""))
            } catch (e: Exception) {
                Pair("", "")
            }
        }
    }

    // 翻译（服务端）
    private fun translateServer(text: String, source: String): String {
        return try {
            val json = JSONObject().apply {
                put("text", text); put("source", source)
                put("target", languages[targetLang.selectedItemPosition].translateCode)
            }
            val conn = URL("$serverUrl/translate").openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            conn.outputStream.write(json.toString().toByteArray())
            val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream))
            val response = reader.readText(); reader.close()
            conn.disconnect()
            JSONObject(response).optString("translation", text)
        } catch (e: Exception) {
            text
        }
    }

    private fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        scope.launch {
            delay(3000)
            updateUIForRecording(false)
            updateStatus("✅ 已停止")
        }
    }

    private fun updateUIForRecording(recording: Boolean) {
        runOnUiThread {
            recordBtn.text = if (recording) "⏹ 停止同传" else "🎤 开始同传"
            recordBtn.setBackgroundTintList(
                ContextCompat.getColorStateList(this@MainActivity,
                    if (recording) android.R.color.holo_red_light else R.color.blue))
        }
    }

    private fun updateStatus(msg: String) { runOnUiThread { statusText.text = msg } }

    private fun addHistory(src: String, tgt: String, asr: String) {
        val sourceName = languages.getOrNull(sourceLang.selectedItemPosition)?.displayName ?: ""
        val targetName = languages.getOrNull(targetLang.selectedItemPosition)?.displayName ?: ""
        historyItems.add(0, HistoryItem(src, tgt, timeFormatter.format(Date()), sourceName, targetName, asr))
        if (historyItems.size > 100) historyItems.removeLast()
        runOnUiThread {
            historyLayout.removeAllViews()
            for (item in historyItems) {
                val view = layoutInflater.inflate(R.layout.history_item, historyLayout, false)
                view.findViewById<TextView>(R.id.historySource).text = "[${item.sourceLang}] ${item.source}"
                view.findViewById<TextView>(R.id.historyTarget).text = "[${item.targetLang}] ${item.target} (${item.asr})"
                view.findViewById<TextView>(R.id.historyTime).text = item.time
                historyLayout.addView(view)
            }
        }
    }

    private fun createWavFile(file: File, pcmData: ByteArray) {
        val totalSize = 44 + pcmData.size
        FileOutputStream(file).use { fos ->
            val h = ByteArray(44)
            h[0] = 'R'.code.toByte(); h[1] = 'I'.code.toByte(); h[2] = 'F'.code.toByte(); h[3] = 'F'.code.toByte()
            writeIntLE(h, 4, totalSize - 8)
            h[8] = 'W'.code.toByte(); h[9] = 'A'.code.toByte(); h[10] = 'V'.code.toByte(); h[11] = 'E'.code.toByte()
            h[12] = 'f'.code.toByte(); h[13] = 'm'.code.toByte(); h[14] = 't'.code.toByte(); h[15] = ' '.code.toByte()
            writeIntLE(h, 16, 16); writeShortLE(h, 20, 1); writeShortLE(h, 22, 1)
            writeIntLE(h, 24, sampleRate); writeIntLE(h, 28, sampleRate * 2); writeShortLE(h, 32, 2); writeShortLE(h, 34, 16)
            h[36] = 'd'.code.toByte(); h[37] = 'a'.code.toByte(); h[38] = 't'.code.toByte(); h[39] = 'a'.code.toByte()
            writeIntLE(h, 40, pcmData.size)
            fos.write(h); fos.write(pcmData)
        }
    }

    private fun writeIntLE(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte()
        b[o + 2] = ((v shr 16) and 0xFF).toByte(); b[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun calculateRMS(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) sum += abs(buffer[i].toDouble()) / 32767.0
        return sum / length
    }

    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false; recordingJob?.cancel()
        segmentProcessJob?.cancel(); scope.cancel()
        if (llamaContext != 0L) {
            try { llamaRelease(llamaContext) } catch (_: Exception) {}
        }
    }
}

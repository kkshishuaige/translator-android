package com.translator.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import android.content.Intent
import android.speech.RecognitionListener
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import java.io.ByteArrayOutputStream
import android.view.View

class MainActivity : AppCompatActivity() {

    private lateinit var recordBtn: Button
    private lateinit var statusText: TextView
    private lateinit var modelStatusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var translationText: TextView
    private lateinit var sourceLang: Spinner
    private lateinit var targetLang: Spinner
    private lateinit var historyLayout: LinearLayout

    private var isRecording = false
    private var recordingJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null

    // 翻译引擎 — Gemma 4 本地（ML Kit Prompt API）
    private var gemmaModelReady = false
    private var useLocalTranslation = false

    private val transcriptBuffer = StringBuilder()
    private val translationBuffer = StringBuilder()
    private val historyItems = mutableListOf<HistoryItem>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    data class LangInfo(val locale: String, val displayName: String, val translateCode: String)
    data class HistoryItem(val source: String, val target: String, val time: String,
                           val sourceLang: String, val targetLang: String)

    // 注意：Gemma 4 的语言代码和 Android 的不一样，这里用 Gemini 风格的
    private val languages = listOf(
        LangInfo("zh", "中文", "Chinese"),
        LangInfo("en-US", "English", "English"),
        LangInfo("ru-RU", "Русский", "Russian"),
        LangInfo("ar", "العربية", "Arabic"),
        LangInfo("es-ES", "Español", "Spanish"),
        LangInfo("fr-FR", "Français", "French")
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

        val names = languages.map { it.displayName }
        sourceLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        targetLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        sourceLang.setSelection(0)
        targetLang.setSelection(1)

        checkPermission()

        // 初始化 Gemma 4（ML Kit Prompt API）
        initGemma4()

        recordBtn.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
    }

    private fun initGemma4() {
        modelStatusText.text = "⏳ 初始化 Gemma 4…"
        modelStatusText.setTextColor(android.graphics.Color.parseColor("#FFA500"))
        statusText.text = "正在加载翻译模型…"

        scope.launch(Dispatchers.IO) {
            try {
                // 检查 AICore 是否可用
                Generation.getClient()
                gemmaModelReady = true
                useLocalTranslation = true

                runOnUiThread {
                    modelStatusText.text = "🟢 Gemma 4 就绪（本地翻译）"
                    modelStatusText.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                    statusText.text = "就绪，点击开始同传"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    modelStatusText.text = "⚠️ Gemma 4 未就绪：${e.message}"
                    modelStatusText.setTextColor(android.graphics.Color.parseColor("#FF9800"))
                    statusText.text = "Gemma 4 未加载，退出重试"
                }
            }
        }
    }

    /**
     * 使用 Gemma 4 本地翻译（ML Kit Prompt API）
     */
    private suspend fun translateWithGemma4(text: String, sourceLang: String, targetLang: String): String {
        if (!gemmaModelReady) return text
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
                text // 失败时返回原文
            }
        }
    }

    private fun startRecording() {
        if (isRecording) return
        if (!gemmaModelReady) {
            Toast.makeText(this, "Gemma 4 正在加载，请稍候", Toast.LENGTH_SHORT).show()
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

        updateUIForRecording(true)
        updateStatus("🎤 语音识别中…")

        startSpeechRecognition()
    }

    private fun startSpeechRecognition() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languages[sourceLang.selectedItemPosition].locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (isRecording) startSpeechRecognition() // 重新开始监听
            }

            override fun onError(error: Int) {
                if (isRecording) {
                    delay(500)
                    startSpeechRecognition()
                }
            }

            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!texts.isNullOrEmpty()) {
                    val recognizedText = texts[0]
                    runOnUiThread {
                        transcriptBuffer.append(recognizedText).append("\n")
                        transcriptText.text = transcriptBuffer.toString()
                    }
                    // 用 Gemma 4 翻译
                    scope.launch {
                        val sourceCode = languages[sourceLang.selectedItemPosition].translateCode
                        val targetCode = languages[targetLang.selectedItemPosition].translateCode
                        val translation = translateWithGemma4(recognizedText, sourceCode, targetCode)
                        runOnUiThread {
                            translationBuffer.append(translation).append("\n")
                            translationText.text = translationBuffer.toString()
                            addHistory(recognizedText, translation)
                        }
                    }
                }
                if (isRecording) {
                    delay(300)
                    startSpeechRecognition()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!texts.isNullOrEmpty()) {
                    runOnUiThread {
                        transcriptText.text = transcriptBuffer.toString() + texts[0]
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    private fun stopRecording() {
        isRecording = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        recordingJob?.cancel()
        updateUIForRecording(false)
        updateStatus("✅ 已停止")
    }

    private fun updateUIForRecording(recording: Boolean) {
        runOnUiThread {
            recordBtn.text = if (recording) "⏹ 停止同传" else "🎤 开始同传"
            recordBtn.setBackgroundTintList(
                ContextCompat.getColorStateList(this@MainActivity,
                    if (recording) android.R.color.holo_red_light else android.R.color.holo_blue_dark))
        }
    }

    private fun updateStatus(msg: String) { runOnUiThread { statusText.text = msg } }

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
        speechRecognizer?.destroy()
        recordingJob?.cancel()
        scope.cancel()
    }
}

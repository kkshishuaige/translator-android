package com.translator.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var recordBtn: Button
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var translationText: TextView
    private lateinit var sourceLang: Spinner
    private lateinit var targetLang: Spinner
    private lateinit var historyLayout: LinearLayout

    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecording = false
    private val historyItems = mutableListOf<Pair<String, String>>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 翻译服务器地址
    private val serverUrl = "https://7668aa24d9b37008-111-229-193-192.serveousercontent.com"

    private val sourceLanguages = arrayOf("zh-CN", "en-US", "ja-JP", "ko-KR")
    private val targetLanguages = arrayOf("en", "zh", "ja", "ko")
    private val langNames = mapOf(
        "zh-CN" to "中文", "en-US" to "English",
        "ja-JP" to "日本語", "ko-KR" to "한국어",
        "en" to "English", "zh" to "中文",
        "ja" to "日本語", "ko" to "한국어"
    )

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

        // 设置语言选择器
        sourceLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            sourceLanguages.map { "${langNames[it] ?: it} ($it)" })
        targetLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            targetLanguages.map { "${langNames[it] ?: it} ($it)" })
        targetLang.setSelection(0) // 默认 English

        // 检查权限
        checkPermission()

        // 初始化语音识别器
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        // 按键录音
        recordBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> startRecording()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopRecording()
            }
            true
        }
    }

    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    private fun startRecording() {
        if (isRecording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "❌ 需要麦克风权限"
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, sourceLang.selectedItem.toString()
                .substringAfter("(").substringBefore(")"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isRecording = true
                runOnUiThread {
                    recordBtn.text = "🔴 松开识别"
                    statusText.text = "🎤 录音中…"
                    recordBtn.setBackgroundTintList(
                        ContextCompat.getColorStateList(this@MainActivity,
                            android.R.color.holo_red_light))
                }
            }

            override fun onResults(results: Bundle?) {
                isRecording = false
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = texts?.firstOrNull() ?: ""
                runOnUiThread {
                    resetButton()
                    if (text.isNotEmpty()) {
                        transcriptText.text = text
                        statusText.text = "⏳ 翻译中…"
                        translateText(text)
                    } else {
                        statusText.text = "⚠️ 未识别到语音"
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val texts = partialResults?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION)
                val text = texts?.firstOrNull() ?: ""
                runOnUiThread {
                    if (text.isNotEmpty()) {
                        transcriptText.text = text
                    }
                }
            }

            override fun onError(error: Int) {
                isRecording = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有说话"
                    SpeechRecognizer.ERROR_AUDIO -> "录音错误"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                    else -> "错误: $error"
                }
                runOnUiThread {
                    resetButton()
                    statusText.text = "⚠️ $msg"
                }
            }

            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
        })

        speechRecognizer?.startListening(intent)
    }

    private fun stopRecording() {
        speechRecognizer?.stopListening()
    }

    private fun resetButton() {
        recordBtn.text = "🎤 按住说话"
        recordBtn.setBackgroundTintList(
            ContextCompat.getColorStateList(this, R.color.blue))
    }

    private fun translateText(text: String) {
        val source = sourceLang.selectedItem.toString()
            .substringAfter("(").substringBefore(")")
        val target = targetLang.selectedItem.toString()
            .substringAfter("(").substringBefore(")")

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    doTranslate(text, source, target)
                }
                translationText.text = result
                addHistory(text, result)
                statusText.text = "✅ 完成"
            } catch (e: Exception) {
                statusText.text = "❌ 翻译失败: ${e.message}"
            }
        }
    }

    private fun doTranslate(text: String, source: String, target: String): String {
        val json = JSONObject().apply {
            put("text", text)
            put("source", source.split("-")[0])
            put("target", target)
        }

        val url = URL("$serverUrl/translate")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        conn.outputStream.write(json.toString().toByteArray())

        val reader = BufferedReader(InputStreamReader(conn.inputStream))
        val response = reader.readText()
        reader.close()

        val resultJson = JSONObject(response)
        return resultJson.optString("translation", text)
    }

    private fun addHistory(src: String, tgt: String) {
        historyItems.add(0, Pair(src, tgt))
        if (historyItems.size > 30) historyItems.removeLast()

        runOnUiThread {
            historyLayout.removeAllViews()
            for (item in historyItems) {
                val view = layoutInflater.inflate(R.layout.history_item, historyLayout, false)
                view.findViewById<TextView>(R.id.historySource).text = item.first
                view.findViewById<TextView>(R.id.historyTarget).text = item.second
                historyLayout.addView(view)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        scope.cancel()
    }
}

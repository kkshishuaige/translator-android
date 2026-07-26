package com.translator.app

/**
 * Whisper 本地语音识别模型 JNI 封装
 * 通过 whisper.cpp 进行本地语音识别，支持 99 种语言
 */
object WhisperModel {
    private var loaded = false

    // JNI 方法
    private external fun init(modelPath: String): Boolean
    private external fun transcribe(audioData: FloatArray, lang: String): String
    private external fun release()
    private external fun isReady(): Boolean

    /**
     * 加载模型
     */
    fun load(modelPath: String): Boolean {
        if (loaded) return true
        loaded = init(modelPath)
        return loaded
    }

    /**
     * 转录语音
     * @param audioData 16kHz 16-bit PCM 音频数据，归一化到 float[-1,1]
     * @param lang 语言代码（"en","zh","ar","ru","es","fr","auto"）
     * @return 识别文本
     */
    fun transcribeAudio(audioData: FloatArray, lang: String = "auto"): String {
        if (!loaded) return ""
        return transcribe(audioData, lang)
    }

    /**
     * 模型是否已加载
     */
    fun isModelReady(): Boolean = loaded && isReady()

    /**
     * 释放模型
     */
    fun unload() {
        if (loaded) {
            release()
            loaded = false
        }
    }
}

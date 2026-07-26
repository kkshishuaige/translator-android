package com.translator.app

/**
 * Whisper 本地语音识别模型 JNI 封装
 * 通过 whisper.cpp 进行本地语音识别，支持 99 种语言
 */
object WhisperModel {
    private var loaded = false

    // 加载 native 库（CMake 编译生成 libwhisper.so）
    init {
        System.loadLibrary("whisper")
    }

    // JNI 方法 - 使用 @JvmStatic 确保编译为静态方法
    @JvmStatic private external fun nativeInit(modelPath: String): Boolean
    @JvmStatic private external fun nativeTranscribe(audioData: FloatArray, lang: String): String
    @JvmStatic private external fun nativeRelease()
    @JvmStatic private external fun nativeIsReady(): Boolean

    /**
     * 加载模型
     */
    fun load(modelPath: String): Boolean {
        if (loaded) return true
        loaded = nativeInit(modelPath)
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
        return nativeTranscribe(audioData, lang)
    }

    /**
     * 模型是否已加载
     */
    fun isModelReady(): Boolean = loaded && nativeIsReady()

    /**
     * 释放模型
     */
    fun unload() {
        if (loaded) {
            nativeRelease()
            loaded = false
        }
    }
}

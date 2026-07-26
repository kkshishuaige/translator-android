package com.translator.app

import android.util.Log

/**
 * Whisper 本地语音识别模型 JNI 封装
 * 通过 whisper.cpp 进行本地语音识别，支持 99 种语言
 * 模型文件存放在 app 私有目录下，直接文件路径加载
 */
object WhisperModel {
    private const val TAG = "WhisperModel"
    private var loaded = false

    init {
        System.loadLibrary("whisper")
    }

    // JNI 方法
    @JvmStatic private external fun nativeInit(modelPath: String): Boolean
    @JvmStatic private external fun nativeInitFromBytes(modelData: ByteArray): Boolean
    @JvmStatic private external fun nativeTranscribe(audioData: FloatArray, lang: String): String
    @JvmStatic private external fun nativeRelease()
    @JvmStatic private external fun nativeIsReady(): Boolean

    /**
     * 加载模型（从文件读入字节数组再传给 JNI，避免 NDK mmap 问题）
     * @param modelPath 模型文件完整路径（app 私有目录）
     */
    fun load(modelPath: String): Boolean {
        if (loaded) return true

        val file = java.io.File(modelPath)
        if (!file.exists()) {
            Log.e(TAG, "Model file not found: $modelPath")
            return false
        }

        val fileSize = file.length()
        Log.i(TAG, "Loading model from: $modelPath (${fileSize / (1024*1024)} MB)")

        // 读入字节数组（tiny 75MB 在大堆模式下没问题）
        return try {
            val bytes = file.readBytes()
            Log.i(TAG, "Read ${bytes.size} bytes from file, passing to nativeInitFromBytes")
            loaded = nativeInitFromBytes(bytes)
            if (loaded) {
                Log.i(TAG, "Model loaded successfully from buffer")
            } else {
                Log.e(TAG, "nativeInitFromBytes returned false")
            }
            loaded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read model file: ${e.message}")
            false
        }
    }

    fun transcribeAudio(audioData: FloatArray, lang: String = "auto"): String {
        if (!loaded) return ""
        return nativeTranscribe(audioData, lang)
    }

    fun isModelReady(): Boolean = loaded && nativeIsReady()

    fun unload() {
        if (loaded) {
            nativeRelease()
            loaded = false
        }
    }
}

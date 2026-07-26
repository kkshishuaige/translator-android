package com.translator.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

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

    // JNI 方法
    // nativeInit(path) — 通过文件路径加载（NDK fopen，可能被 SELinux 拦截）
    @JvmStatic private external fun nativeInit(modelPath: String): Boolean
    // nativeInitFromBytes(data) — 通过内存字节数组加载（用 Java 读文件，传给 JNI，绕过 SELinux）
    @JvmStatic private external fun nativeInitFromBytes(modelData: ByteArray): Boolean
    @JvmStatic private external fun nativeTranscribe(audioData: FloatArray, lang: String): String
    @JvmStatic private external fun nativeRelease()
    @JvmStatic private external fun nativeIsReady(): Boolean

    /**
     * 加载模型（使用内存缓冲方式，避免 NDK 文件权限问题）
     * @param modelPath 模型文件路径
     * @return 是否加载成功
     */
    fun load(modelPath: String): Boolean {
        if (loaded) return true

        val file = File(modelPath)
        if (!file.exists()) {
            android.util.Log.e("WhisperModel", "Model file not found: $modelPath")
            return false
        }

        try {
            // 用 Java RandomAccessFile + FileChannel 读取文件
            // 这种方式通过 Android Java 兼容层访问文件，不受 NDK 层 SELinux 限制
            val raf = RandomAccessFile(file, "r")
            val channel = raf.channel
            val size = channel.size()

            android.util.Log.i("WhisperModel", "Reading model file: ${size} bytes (${size / (1024*1024)} MB)")

            // 读取整个文件到字节数组
            val buf = ByteArray(size.toInt())
            var offset = 0
            while (offset < size) {
                val read = channel.read(ByteBuffer.wrap(buf, offset, (size - offset).toInt()))
                if (read <= 0) break
                offset += read
            }
            channel.close()
            raf.close()

            android.util.Log.i("WhisperModel", "File read complete, passing ${buf.size} bytes to JNI")
            loaded = nativeInitFromBytes(buf)
            return loaded
        } catch (e: Exception) {
            android.util.Log.e("WhisperModel", "Failed to read model file: ${e.message}")
            // 降级：尝试用文件路径方式（某些旧设备可用）
            try {
                loaded = nativeInit(modelPath)
                return loaded
            } catch (e2: Exception) {
                android.util.Log.e("WhisperModel", "Fallback also failed: ${e2.message}")
                return false
            }
        }
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

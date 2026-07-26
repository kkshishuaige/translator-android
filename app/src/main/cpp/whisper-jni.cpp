#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <vector>
#include <string>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 全局模型指针
static struct whisper_context *g_ctx = nullptr;

extern "C" {

/**
 * 初始化 Whisper 模型
 */
JNIEXPORT jboolean JNICALL
Java_com_translator_app_WhisperModel_nativeInit(JNIEnv *env, jclass clazz, jstring modelPath) {
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s", path);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // Android CPU only

    g_ctx = whisper_init_from_file_with_params(path, cparams);

    env->ReleaseStringUTFChars(modelPath, path);

    if (g_ctx == nullptr) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

/**
 * 转录音频 PCM 数据
 */
JNIEXPORT jstring JNICALL
Java_com_translator_app_WhisperModel_nativeTranscribe(JNIEnv *env, jclass clazz,
                                                       jfloatArray audioData, jstring lang) {
    if (g_ctx == nullptr) {
        LOGE("Model not initialized");
        return env->NewStringUTF("");
    }

    // 获取音频数据
    jsize len = env->GetArrayLength(audioData);
    jfloat *audio = env->GetFloatArrayElements(audioData, nullptr);

    // 获取语言
    const char *langStr = env->GetStringUTFChars(lang, nullptr);

    // 配置参数
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_realtime   = false;
    wparams.print_progress   = false;
    wparams.print_timestamps = false;
    wparams.print_special    = false;
    wparams.n_threads        = 4;
    wparams.speed_up         = false;
    wparams.debug_mode       = false;
    wparams.suppress_blank   = true;
    wparams.suppress_non_speech_tokens = true;
    wparams.tdrz_enable      = false;

    // 语言设置
    if (strcmp(langStr, "auto") != 0 && strlen(langStr) > 0) {
        wparams.language = langStr;
    } else {
        wparams.language = nullptr;  // 自动检测
    }

    // 运行识别
    if (whisper_full(g_ctx, wparams, audio, len) != 0) {
        LOGE("Failed to run transcription");
        env->ReleaseFloatArrayElements(audioData, audio, JNI_ABORT);
        env->ReleaseStringUTFChars(lang, langStr);
        return env->NewStringUTF("");
    }

    // 提取结果
    std::string result;
    int n_segments = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(g_ctx, i);
        if (text != nullptr) {
            if (i > 0) result += " ";
            result += text;
        }
    }

    env->ReleaseFloatArrayElements(audioData, audio, JNI_ABORT);
    env->ReleaseStringUTFChars(lang, langStr);

    LOGI("Transcription result: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

/**
 * 释放模型
 */
JNIEXPORT void JNICALL
Java_com_translator_app_WhisperModel_nativeRelease(JNIEnv *env, jclass clazz) {
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        LOGI("Model released");
    }
}

/**
 * 获取模型是否就绪
 */
JNIEXPORT jboolean JNICALL
Java_com_translator_app_WhisperModel_nativeIsReady(JNIEnv *env, jclass clazz) {
    return (g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"

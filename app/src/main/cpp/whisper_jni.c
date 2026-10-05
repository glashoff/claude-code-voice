// Minimal JNI bridge to whisper.cpp: load a model, transcribe 16 kHz mono float audio.
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>

#include "whisper.h"

#define TAG "WhisperJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

JNIEXPORT jlong JNICALL
Java_dev_claudecodevoice_WhisperNative_init(JNIEnv *env, jclass clazz, jstring model_path) {
    (void) clazz;
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    LOGI("Loaded model %s -> %p", path, (void *) ctx);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_dev_claudecodevoice_WhisperNative_free(JNIEnv *env, jclass clazz, jlong ctx) {
    (void) env;
    (void) clazz;
    if (ctx != 0) whisper_free((struct whisper_context *) ctx);
}

// Returns the transcript as UTF-8 bytes (NewStringUTF expects modified UTF-8, which breaks on emoji).
JNIEXPORT jbyteArray JNICALL
Java_dev_claudecodevoice_WhisperNative_transcribe(JNIEnv *env, jclass clazz, jlong ctx_ptr, jfloatArray audio,
                                             jstring language, jstring prompt, jint threads) {
    (void) clazz;
    struct whisper_context *ctx = (struct whisper_context *) ctx_ptr;

    const char *lang = (*env)->GetStringUTFChars(env, language, NULL);
    const char *prompt_chars = (*env)->GetStringUTFChars(env, prompt, NULL);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_timestamps = true;
    params.no_context = true;
    params.single_segment = false;
    params.n_threads = threads;
    params.language = lang;  // "de", "en" or "auto"
    params.initial_prompt = prompt_chars[0] != '\0' ? prompt_chars : NULL;

    jfloat *samples = (*env)->GetFloatArrayElements(env, audio, NULL);
    const jsize n_samples = (*env)->GetArrayLength(env, audio);

    size_t cap = 256, len = 0;
    char *text = malloc(cap);
    text[0] = '\0';

    if (whisper_full(ctx, params, samples, n_samples) == 0) {
        const int n = whisper_full_n_segments(ctx);
        for (int i = 0; i < n; i++) {
            const char *seg = whisper_full_get_segment_text(ctx, i);
            const size_t seg_len = strlen(seg);
            while (len + seg_len + 1 > cap) {
                cap *= 2;
                text = realloc(text, cap);
            }
            memcpy(text + len, seg, seg_len + 1);
            len += seg_len;
        }
    } else {
        LOGI("whisper_full failed");
    }

    (*env)->ReleaseFloatArrayElements(env, audio, samples, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, language, lang);
    (*env)->ReleaseStringUTFChars(env, prompt, prompt_chars);

    jbyteArray result = (*env)->NewByteArray(env, (jsize) len);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize) len, (const jbyte *) text);
    free(text);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_claudecodevoice_WhisperNative_systemInfo(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}

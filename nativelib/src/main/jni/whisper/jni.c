// file: WhisperLib.c
// ============================================================
// ✅ whisper.cpp JNI Bridge — Safe Loader + Transcriber (Full Debug Version)
// ------------------------------------------------------------
// • Supports File / Asset / InputStream loading
// • Safe InputStream buffering (64 KB, GlobalRef reuse)
// • Thread-safe JNIEnv retrieval (JavaVM cached per context)
// • Proper exception clearing and memory cleanup
// • Defensive checks for negative AAsset_read / JNI failures
// ============================================================

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include "whisper.h"

#define TAG "JNI-Whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ============================================================
 * Utility: get JNIEnv safely from cached JavaVM
 * ============================================================ */
static inline JNIEnv* get_env_from_jvm(JavaVM* jvm) {
    if (!jvm) return NULL;
    JNIEnv* env = NULL;
    if ((*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*jvm)->AttachCurrentThread(jvm, (void**)&env, NULL) != 0) {
            LOGE("AttachCurrentThread failed");
            return NULL;
        }
    }
    return env;
}

/* ============================================================
 * InputStream Loader Context
 * ============================================================ */
struct input_stream_context {
    JavaVM   *jvm;
    jobject   input_stream;  // GlobalRef to InputStream
    jmethodID mid_read;      // read(byte[], int, int)
    jobject   buffer_gl;     // GlobalRef to byte[]
    jint      buf_len;
    int       eof;
};

static size_t is_read(void *ctx, void *output, size_t read_size) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;
    if (!is || !is->jvm) return 0;
    JNIEnv* env = get_env_from_jvm(is->jvm);
    if (!env) return 0;

    jint chunk = (jint)((read_size > (size_t)is->buf_len) ? is->buf_len : read_size);
    jint n = (*env)->CallIntMethod(env, is->input_stream, is->mid_read, is->buffer_gl, 0, chunk);

    if ((*env)->ExceptionCheck(env)) {
        LOGE("Exception in InputStream.read()");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        is->eof = 1;
        return 0;
    }
    if (n <= 0) { is->eof = 1; return 0; }

    jbyte* ptr = (*env)->GetByteArrayElements(env, (jbyteArray)is->buffer_gl, NULL);
    if (!ptr) { LOGE("GetByteArrayElements returned NULL"); is->eof = 1; return 0; }
    memcpy(output, ptr, (size_t)n);
    (*env)->ReleaseByteArrayElements(env, (jbyteArray)is->buffer_gl, ptr, JNI_ABORT);
    return (size_t)n;
}

static bool is_eof(void *ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;
    return is ? (is->eof != 0) : true;
}

static void is_close(void *ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;
    if (!is) return;
    JNIEnv* env = get_env_from_jvm(is->jvm);
    if (env) {
        if (is->input_stream) { (*env)->DeleteGlobalRef(env, is->input_stream); is->input_stream = NULL; }
        if (is->buffer_gl)    { (*env)->DeleteGlobalRef(env, is->buffer_gl);    is->buffer_gl = NULL; }
    }
    free(is);
}

/* ============================================================
 * JNI: initContextFromInputStream
 * ============================================================ */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContextFromInputStream(
        JNIEnv *env, jclass clazz, jobject input_stream) {
    (void)clazz;
    if (!input_stream) { LOGW("input_stream is NULL"); return 0; }

    struct input_stream_context* inp = calloc(1, sizeof(*inp));
    if (!inp) { LOGE("calloc failed"); return 0; }

    if ((*env)->GetJavaVM(env, &inp->jvm) != 0) { LOGE("GetJavaVM failed"); free(inp); return 0; }
    inp->input_stream = (*env)->NewGlobalRef(env, input_stream);
    if (!inp->input_stream) { LOGE("NewGlobalRef failed"); free(inp); return 0; }

    jclass cls = (*env)->GetObjectClass(env, input_stream);
    if (!cls) { LOGE("GetObjectClass failed"); is_close(inp); return 0; }

    inp->mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");
    (*env)->DeleteLocalRef(env, cls);
    if (!inp->mid_read) { LOGE("GetMethodID(read) failed"); is_close(inp); return 0; }

    inp->buf_len = 64 * 1024;
    jbyteArray buf_local = (*env)->NewByteArray(env, inp->buf_len);
    if (!buf_local) { LOGE("NewByteArray failed"); is_close(inp); return 0; }
    inp->buffer_gl = (*env)->NewGlobalRef(env, buf_local);
    (*env)->DeleteLocalRef(env, buf_local);
    if (!inp->buffer_gl) { LOGE("NewGlobalRef(buffer) failed"); is_close(inp); return 0; }

    struct whisper_model_loader loader = { inp, is_read, is_eof, is_close };
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_with_params(&loader, cparams);
    if (!ctx) { LOGE("whisper_init_with_params failed (InputStream)"); is_close(inp); return 0; }
    LOGI("Whisper model loaded from InputStream");
    return (jlong)ctx;
}

/* ============================================================
 * Asset Loader
 * ============================================================ */
static size_t asset_read(void *ctx, void *output, size_t read_size) {
    int r = AAsset_read((AAsset *)ctx, output, read_size);
    return (r > 0) ? (size_t)r : 0;
}
static bool asset_eof(void *ctx) { return AAsset_getRemainingLength64((AAsset *)ctx) <= 0; }
static void asset_close(void *ctx) { if (ctx) AAsset_close((AAsset *)ctx); }

static struct whisper_context* whisper_init_from_asset(
        JNIEnv *env, jobject mgrObj, const char *asset_path) {
    if (!mgrObj || !asset_path) { LOGW("invalid args"); return NULL; }
    LOGI("Loading model from asset '%s'", asset_path);
    AAssetManager *mgr = AAssetManager_fromJava(env, mgrObj);
    if (!mgr) { LOGE("AAssetManager_fromJava failed"); return NULL; }
    AAsset *asset = AAssetManager_open(mgr, asset_path, AASSET_MODE_STREAMING);
    if (!asset) { LOGE("AAssetManager_open failed: %s", asset_path); return NULL; }

    struct whisper_model_loader loader = { asset, asset_read, asset_eof, asset_close };
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_with_params(&loader, cparams);
    if (!ctx) LOGE("whisper_init_with_params failed (Asset)");
    return ctx;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContextFromAsset(
        JNIEnv *env, jclass clazz, jobject mgr, jstring pathStr) {
    (void)clazz;
    if (!pathStr) return 0;
    const char *path = (*env)->GetStringUTFChars(env, pathStr, NULL);
    if (!path) return 0;
    struct whisper_context *ctx = whisper_init_from_asset(env, mgr, path);
    (*env)->ReleaseStringUTFChars(env, pathStr, path);
    return (jlong)ctx;
}

/* ============================================================
 * File Path Loader
 * ============================================================ */
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContext(
        JNIEnv *env, jclass clazz, jstring pathStr) {
    (void)clazz;
    if (!pathStr) { LOGW("pathStr NULL"); return 0; }
    const char *path = (*env)->GetStringUTFChars(env, pathStr, NULL);
    if (!path) { LOGE("GetStringUTFChars failed"); return 0; }
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    (*env)->ReleaseStringUTFChars(env, pathStr, path);
    if (!ctx) LOGE("whisper_init_from_file_with_params failed");
    else LOGI("Model loaded from file: %s", path);
    return (jlong)ctx;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_freeContext(
        JNIEnv *env, jclass clazz, jlong ptr) {
    (void)env; (void)clazz;
    if (ptr) { whisper_free((struct whisper_context*)ptr); LOGI("Context freed"); }
}

/* ============================================================
 * Transcribe (full synchronous)
 * ============================================================ */
JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_fullTranscribe(
        JNIEnv *env, jclass clazz, jlong ctxPtr, jstring langStr,
        jint nthreads, jboolean translate, jfloatArray audio) {
    (void)clazz;
    struct whisper_context *ctx = (struct whisper_context*)ctxPtr;
    if (!ctx || !audio) { LOGW("ctx or audio NULL"); return; }

    jfloat *pcm = (*env)->GetFloatArrayElements(env, audio, NULL);
    if (!pcm) { LOGE("GetFloatArrayElements failed"); return; }
    jsize n = (*env)->GetArrayLength(env, audio);

    const char *lang = NULL;
    if (langStr) lang = (*env)->GetStringUTFChars(env, langStr, NULL);

    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = nthreads > 0 ? nthreads : 1;
    p.translate = (translate == JNI_TRUE);
    p.no_context = true;
    p.single_segment = false;
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;

    if (lang && strcmp(lang, "auto") != 0) { p.language = lang; p.detect_language = false; }
    else { p.detect_language = true; }

    LOGI("whisper_full: n=%d, threads=%d, translate=%d", (int)n, p.n_threads, p.translate);
    whisper_reset_timings(ctx);
    if (whisper_full(ctx, p, pcm, (int)n) != 0) LOGW("whisper_full failed");
    else whisper_print_timings(ctx);

    if (langStr && lang) (*env)->ReleaseStringUTFChars(env, langStr, lang);
    (*env)->ReleaseFloatArrayElements(env, audio, pcm, JNI_ABORT);
}

/* ============================================================
 * Segments / Info / Bench
 * ============================================================ */
JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentCount(
        JNIEnv *env, jclass clazz, jlong ptr) {
    (void)env; (void)clazz;
    return ptr ? whisper_full_n_segments((struct whisper_context*)ptr) : 0;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegment(
        JNIEnv *env, jclass clazz, jlong ptr, jint i) {
    (void)clazz;
    const char *s = (ptr ? whisper_full_get_segment_text((struct whisper_context*)ptr, i) : "");
    return (*env)->NewStringUTF(env, s ? s : "");
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentT0(
        JNIEnv *env, jclass clazz, jlong ptr, jint i) {
    (void)env; (void)clazz;
    return ptr ? whisper_full_get_segment_t0((struct whisper_context*)ptr, i) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentT1(
        JNIEnv *env, jclass clazz, jlong ptr, jint i) {
    (void)env; (void)clazz;
    return ptr ? whisper_full_get_segment_t1((struct whisper_context*)ptr, i) : 0;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_getSystemInfo(JNIEnv *env, jclass clazz) {
    (void)clazz;
    const char *s = whisper_print_system_info();
    return (*env)->NewStringUTF(env, s ? s : "");
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_benchMemcpy(JNIEnv *env, jclass clazz, jint nt) {
    (void)clazz;
    const char *s = whisper_bench_memcpy_str(nt);
    return (*env)->NewStringUTF(env, s ? s : "");
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_benchGgmlMulMat(JNIEnv *env, jclass clazz, jint nt) {
    (void)clazz;
    const char *s = whisper_bench_ggml_mul_mat_str(nt);
    return (*env)->NewStringUTF(env, s ? s : "");
}

/* ============================================================
 * JNI_OnLoad
 * ============================================================ */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)vm; (void)reserved;
    LOGI("JNI_OnLoad: Whisper JNI initialized");
    return JNI_VERSION_1_6;
}

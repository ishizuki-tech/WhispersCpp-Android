// file: com/whispercpp/whisper/WhisperContext.kt
// ============================================================
// ✅ WhisperContext — JNI-safe, Coroutine-isolated, Debug-stable version
// ------------------------------------------------------------
// • Dedicated single-thread dispatcher ensures whisper.cpp thread safety
// • Dynamic CPU ABI + feature detection for optimized .so selection
// • Structured lifecycle (init → transcribe → release → finalize)
// • Strong Kotlin coroutine integration with JNI layer
// • Built for stability, logging, and debuggability
// ============================================================

package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

private const val LOG_TAG = "WhisperJNI"

/**
 * Kotlin wrapper for whisper.cpp JNI bindings.
 *
 * Key design notes:
 *  - whisper.cpp is **not thread-safe**: all JNI calls are serialized via a single-thread dispatcher.
 *  - Context pointer (ptr) is owned exclusively by this instance.
 *  - Provides multiple creation paths: File / Asset / InputStream.
 *  - Coroutine-safe: uses `withContext(scope.coroutineContext)` to confine JNI calls.
 *
 * Typical usage:
 * ```
 * val ctx = WhisperContext.createContextFromAsset(context.assets, "models/ggml-tiny.bin")
 * val text = ctx.transcribeData(samples, "en", translate = false)
 * ctx.release()
 * ```
 */
class WhisperContext private constructor(
    private var ptr: Long
) {
    // ------------------------------------------------------------
    // Dedicated single-thread executor
    // ------------------------------------------------------------
    // All whisper.cpp JNI calls execute here to avoid race conditions
    // in ggml/whisper internal buffers. The thread persists as long
    // as the scope lives (owned by this context).
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "WhisperThread").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }.asCoroutineDispatcher()
    )

    // ------------------------------------------------------------
    // Transcription entrypoint (suspend)
    // ------------------------------------------------------------
    /**
     * Run full synchronous transcription of normalized PCM data.
     * The call blocks the internal JNI thread, not the UI thread.
     *
     * @param data PCM samples (FloatArray) normalized to [-1,1]
     * @param lang 2-letter language code ("en", "ja", "sw") or "auto"
     * @param translate If true, translate to English
     * @param printTimestamp Whether to append timestamps per segment
     */
    suspend fun transcribeData(
        data: FloatArray,
        lang: String,
        translate: Boolean,
        printTimestamp: Boolean = true
    ): String = withContext(scope.coroutineContext) {
        check(ptr != 0L) { "WhisperContext: already released (ptr=0)" }

        val numThreads = WhisperCpuConfig.preferredThreadCount
        Log.i(LOG_TAG, "Transcribe start: threads=$numThreads lang=$lang translate=$translate")

        // JNI → whisper_full()
        WhisperLib.fullTranscribe(ptr, lang, numThreads, translate, data)

        // Collect and concatenate decoded segments
        val n = WhisperLib.getTextSegmentCount(ptr)
        buildString {
            for (i in 0 until n) {
                append(WhisperLib.getTextSegment(ptr, i))
                if (printTimestamp) {
                    val t0 = WhisperLib.getTextSegmentT0(ptr, i)
                    val t1 = WhisperLib.getTextSegmentT1(ptr, i)
                    append(" [${toTimestamp(t0)} - ${toTimestamp(t1)}]\n")
                }
            }
        }.also {
            Log.i(LOG_TAG, "Transcribe complete: $n segments (${it.length} chars)")
        }
    }

    // ------------------------------------------------------------
    // Benchmark and diagnostics (optional utilities)
    // ------------------------------------------------------------
    suspend fun benchMemory(nthreads: Int): String =
        withContext(scope.coroutineContext) { WhisperLib.benchMemcpy(nthreads) }

    suspend fun benchGgmlMulMat(nthreads: Int): String =
        withContext(scope.coroutineContext) { WhisperLib.benchGgmlMulMat(nthreads) }

    // ------------------------------------------------------------
    // Lifecycle management
    // ------------------------------------------------------------
    /**
     * Explicitly release native whisper context memory.
     * Safe to call multiple times; logs if already released.
     */
    suspend fun release() = withContext(scope.coroutineContext) {
        if (ptr != 0L) {
            runCatching {
                WhisperLib.freeContext(ptr)
                Log.d(LOG_TAG, "Released native context (ptr=$ptr)")
            }.onFailure { e ->
                Log.e(LOG_TAG, "Error releasing context", e)
            }
            ptr = 0L
        } else {
            Log.w(LOG_TAG, "Release called on already freed context")
        }
    }

    /**
     * Fallback cleanup invoked by GC if [release] was not called.
     * Synchronously runs release() to prevent native memory leak.
     */
    @Suppress("deprecation")
    protected fun finalize() {
        try {
            runBlocking { release() }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Finalize cleanup failed", e)
        }
    }

    // ============================================================
    // Companion — Context creation entrypoints
    // ============================================================
    companion object {
        /**
         * Load model from a filesystem path.
         * Fastest loading mode (mmap-capable).
         */
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            require(ptr != 0L) { "Failed to create context from file: $filePath" }
            Log.i(LOG_TAG, "WhisperContext created from file: $filePath")
            return WhisperContext(ptr)
        }

        /**
         * Load model from arbitrary Java InputStream.
         * Useful for streaming or downloading model assets.
         */
        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)
            require(ptr != 0L) { "Failed to create context from InputStream" }
            Log.i(LOG_TAG, "WhisperContext created from InputStream")
            return WhisperContext(ptr)
        }

        /**
         * Load model from Android app assets using AssetManager.
         * Streaming mode minimizes RAM usage.
         */
        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)
            require(ptr != 0L) { "Failed to create context from asset: $assetPath" }
            Log.i(LOG_TAG, "WhisperContext created from asset: $assetPath")
            return WhisperContext(ptr)
        }

        /** Return system info string from native whisper.cpp */
        fun getSystemInfo(): String = WhisperLib.getSystemInfo()
    }
}

// ============================================================
// JNI Bridge — WhisperLib
// ------------------------------------------------------------
// Loads the proper native shared library variant (.so)
// and exposes JNI native entry points for whisper.cpp
// ============================================================
private class WhisperLib {
    companion object {
        init {
            try {
                // Detect device ABI (e.g., arm64-v8a, armeabi-v7a)
                val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                Log.i(LOG_TAG, "Detected ABI: $abi")

                val info = cpuInfo()

                // Dynamic native library selection
                when {
                    isArmEabiV7a() && info?.contains("vfpv4", ignoreCase = true) == true -> {
                        System.loadLibrary("whisper_vfpv4")
                        Log.i(LOG_TAG, "Loaded optimized libwhisper_vfpv4.so (ARMv7 + VFPv4)")
                    }
                    isArmEabiV8a() && info?.contains("fphp", ignoreCase = true) == true -> {
                        System.loadLibrary("whisper_v8fp16_va")
                        Log.i(LOG_TAG, "Loaded optimized libwhisper_v8fp16_va.so (ARMv8.2 + FP16)")
                    }
                    else -> {
                        System.loadLibrary("whisper")
                        Log.i(LOG_TAG, "Loaded fallback libwhisper.so (generic CPU)")
                    }
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.e(LOG_TAG, "Failed to load native whisper library", e)
                throw e
            }
        }

        // ----------- JNI exported native function bindings -----------
        @JvmStatic external fun initContext(modelPath: String): Long
        @JvmStatic external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        @JvmStatic external fun initContextFromInputStream(inputStream: InputStream): Long
        @JvmStatic external fun freeContext(contextPtr: Long)
        @JvmStatic external fun fullTranscribe(contextPtr: Long, lang: String, numThreads: Int, translate: Boolean, audioData: FloatArray)
        @JvmStatic external fun getTextSegmentCount(contextPtr: Long): Int
        @JvmStatic external fun getTextSegment(contextPtr: Long, index: Int): String
        @JvmStatic external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        @JvmStatic external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        @JvmStatic external fun getSystemInfo(): String
        @JvmStatic external fun benchMemcpy(nthread: Int): String
        @JvmStatic external fun benchGgmlMulMat(nthread: Int): String
    }
}

// ============================================================
// Utility Functions — internal helpers
// ============================================================

/** Convert frame indices (10ms units) to "hh:mm:ss.mmm" formatted timestamps. */
private fun toTimestamp(t: Long, comma: Boolean = false): String {
    var msec = t * 10
    val hr = msec / (1000 * 60 * 60)
    msec -= hr * (1000 * 60 * 60)
    val min = msec / (1000 * 60)
    msec -= min * (1000 * 60)
    val sec = msec / 1000
    msec -= sec * 1000
    val delimiter = if (comma) "," else "."
    return String.format("%02d:%02d:%02d%s%03d", hr, min, sec, delimiter, msec)
}

/** ABI helpers — identify CPU family */
private fun isArmEabiV7a(): Boolean = Build.SUPPORTED_ABIS.firstOrNull() == "armeabi-v7a"
private fun isArmEabiV8a(): Boolean = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

/**
 * Reads `/proc/cpuinfo` to detect NEON, VFPv4, FP16, etc.
 * Used for selecting optimized native binary at runtime.
 */
private fun cpuInfo(): String? = try {
    File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
} catch (e: Exception) {
    Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
    null
}

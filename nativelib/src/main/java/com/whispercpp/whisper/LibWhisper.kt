// file: com/whispercpp/whisper/WhisperContext.kt
// ============================================================
// ✅ WhisperContext — JNI-safe, Coroutine-isolated, Debug-stable version
// ------------------------------------------------------------
// • Strict single-thread coroutine executor for whisper.cpp thread safety
// • Automatic CPU ABI detection + dynamic .so selection (vfpv4 / fp16)
// • Safe lifecycle: init / transcribe / release / finalize
// • Clear logging for each JNI stage
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
 * Kotlin wrapper for whisper.cpp context.
 * - Thread-safe via single-thread coroutine dispatcher
 * - Supports loading from File / Asset / InputStream
 * - All JNI calls executed sequentially on same thread
 */
class WhisperContext private constructor(
    private var ptr: Long
) {
    // Single-threaded dispatcher (whisper.cpp is not thread-safe)
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "WhisperThread").apply { priority = Thread.NORM_PRIORITY }
        }.asCoroutineDispatcher()
    )

    /**
     * Run full transcription from PCM float array.
     * - Must be normalized [-1,1].
     * - Runs sequentially inside the internal dispatcher.
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

        // Run JNI
        WhisperLib.fullTranscribe(ptr, lang, numThreads, translate, data)

        // Collect text segments
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
            Log.i(LOG_TAG, "Transcribe complete: ${it.length} chars, $n segments")
        }
    }

    /** Run memcpy benchmark via JNI (debug only). */
    suspend fun benchMemory(nthreads: Int): String =
        withContext(scope.coroutineContext) {
            WhisperLib.benchMemcpy(nthreads)
        }

    /** Run ggml matmul benchmark via JNI (debug only). */
    suspend fun benchGgmlMulMat(nthreads: Int): String =
        withContext(scope.coroutineContext) {
            WhisperLib.benchGgmlMulMat(nthreads)
        }

    /**
     * Free the native whisper context.
     * - Safe to call multiple times (idempotent).
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
        }
    }

    /**
     * Emergency GC cleanup — should NOT be relied upon.
     * Always prefer explicit release().
     */
    protected fun finalize() {
        try {
            runBlocking { release() }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Finalize cleanup failed", e)
        }
    }

    // ============================================================
    // Companion factory methods
    // ============================================================
    companion object {
        /** Load from file path */
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            require(ptr != 0L) { "Failed to create context from file: $filePath" }
            Log.i(LOG_TAG, "WhisperContext created from file: $filePath")
            return WhisperContext(ptr)
        }

        /** Load from InputStream (e.g., assets or network stream) */
        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)
            require(ptr != 0L) { "Failed to create context from InputStream" }
            Log.i(LOG_TAG, "WhisperContext created from InputStream")
            return WhisperContext(ptr)
        }

        /** Load from assets using AssetManager */
        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)
            require(ptr != 0L) { "Failed to create context from asset: $assetPath" }
            Log.i(LOG_TAG, "WhisperContext created from asset: $assetPath")
            return WhisperContext(ptr)
        }

        /** Retrieve build/system info from JNI */
        fun getSystemInfo(): String = WhisperLib.getSystemInfo()
    }
}

// ============================================================
// JNI Bridge — WhisperLib
// ============================================================
private class WhisperLib {
    companion object {
        init {
            try {
                val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                Log.i(LOG_TAG, "Detected ABI: $abi")

                val info = cpuInfo()
                when {
                    isArmEabiV7a() && info?.contains("vfpv4", ignoreCase = true) == true -> {
                        System.loadLibrary("whisper_vfpv4")
                        Log.i(LOG_TAG, "Loaded libwhisper_vfpv4.so")
                    }
                    isArmEabiV8a() && info?.contains("fphp", ignoreCase = true) == true -> {
                        System.loadLibrary("whisper_v8fp16_va")
                        Log.i(LOG_TAG, "Loaded libwhisper_v8fp16_va.so")
                    }
                    else -> {
                        System.loadLibrary("whisper")
                        Log.i(LOG_TAG, "Loaded default libwhisper.so")
                    }
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.e(LOG_TAG, "Failed to load native library", e)
                throw e
            }
        }

        // ========== JNI native declarations ==========
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
// Utility Functions
// ============================================================

/** Convert frame index (10 ms units) → hh:mm:ss.mmm string */
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

/** Detect CPU ABI (v7a) */
private fun isArmEabiV7a(): Boolean =
    Build.SUPPORTED_ABIS.firstOrNull() == "armeabi-v7a"

/** Detect CPU ABI (v8a 64-bit) */
private fun isArmEabiV8a(): Boolean =
    Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

/** Read /proc/cpuinfo */
private fun cpuInfo(): String? = try {
    File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
} catch (e: Exception) {
    Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
    null
}

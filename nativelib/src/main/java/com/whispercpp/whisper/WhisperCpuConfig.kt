// file: com/whispercpp/whisper/WhisperCpuConfig.kt
// ============================================================
// ✅ WhisperCpuConfig — Adaptive Thread Optimizer (Full Debug Version)
// ------------------------------------------------------------
// • Dynamically detects big.LITTLE high-performance cores
// • Robust to /proc/cpuinfo or cpufreq access errors
// • Falls back safely to availableProcessors()
// • Detailed debug logging for frequency / variant bins
// ============================================================

package com.whispercpp.whisper

import android.util.Log
import java.io.File

/**
 * Provides an adaptive thread count hint for whisper.cpp inference.
 *
 * whisper.cpp scales linearly across big cores, but with diminishing returns
 * on LITTLE cores. Using too many threads can hurt performance by scheduling
 * small cores into heavy matrix ops (GGML/BLAS), so this detector aims to
 * find a reasonable upper bound for parallelism.
 *
 * Design goals:
 *  - Detect high-performance cores (big cores) on heterogeneous SoCs.
 *  - Stay safe if /proc or /sys files are missing or unreadable.
 *  - Never crash on malformed data; always return ≥ 2 threads.
 */
object WhisperCpuConfig {

    /** Recommended thread count (≥ 2). Evaluated lazily on access. */
    val preferredThreadCount: Int
        get() = CpuInfo.determineHighPerfCpuCount()
            .coerceAtLeast(2) // enforce minimum to maintain decode throughput
}

/**
 * Internal helper class that encapsulates CPU property parsing.
 * Reads `/proc/cpuinfo` lines and uses two-tier logic:
 *   1. Prefer /sys frequency comparison (most reliable on Android)
 *   2. Fallback to CPU variant hex codes if /sys data missing
 */
private class CpuInfo(private val lines: List<String>) {

    /**
     * Estimate the count of "big" high-performance cores.
     * Primary heuristic: frequency; fallback: variant field.
     */
    fun computeHighPerfCpuCount(): Int = try {
        computeByFrequency()
    } catch (e: Exception) {
        Log.d(LOG_TAG, "Frequency detection failed → variant fallback", e)
        computeByVariant()
    }

    /**
     * Frequency-based estimation using /sys/.../cpuinfo_max_freq.
     *
     * - Collects per-core maximum frequencies (in kHz)
     * - Assumes the lowest group is LITTLE cluster, higher = big cluster
     * - Returns the count of cores whose freq > minimum freq
     */
    private fun computeByFrequency(): Int {
        val freqList = getCpuValues("processor") { getMaxCpuFrequency(it.toInt()) }
        Log.d(LOG_TAG, "CPU frequencies (kHz): ${freqList.binnedValues()}")
        return freqList.countDroppingMin()
    }

    /**
     * Variant-based fallback if frequency access fails.
     *
     * Example:
     *   CPU variant : 0x0 → LITTLE
     *   CPU variant : 0x1 → big
     * These hexadecimal variant codes differ between clusters.
     */
    private fun computeByVariant(): Int {
        val variants = getCpuValues("CPU variant") {
            it.substringAfter("0x").toIntOrNull(16) ?: 0
        }
        Log.d(LOG_TAG, "CPU variants (hex): ${variants.binnedValues()}")
        return variants.countKeepingMin()
    }

    /**
     * Extracts numeric values for all CPUs based on a property key.
     * E.g., "CPU variant : 0x1" → returns [1, 1, 0, 0, ...].
     */
    private fun getCpuValues(property: String, mapper: (String) -> Int): List<Int> =
        lines.asSequence()
            .filter { it.startsWith(property) }
            .mapNotNull { runCatching { mapper(it.substringAfter(':').trim()) }.getOrNull() }
            .sorted()
            .toList()

    /** Debug helper — returns frequency/variant histogram. */
    private fun List<Int>.binnedValues(): Map<Int, Int> = groupingBy { it }.eachCount()

    /**
     * Counts cores faster than the slowest frequency group.
     * Useful when little cores have same minimal freq (e.g. 1.8GHz),
     * and big cores show a distinct jump (e.g. 2.4–3.0GHz).
     */
    private fun List<Int>.countDroppingMin(): Int {
        val min = minOrNull() ?: return 0
        return count { it > min }
    }

    /**
     * Counts cores whose variant matches the minimum code.
     * (Simpler fallback when only variant IDs differ per cluster.)
     */
    private fun List<Int>.countKeepingMin(): Int {
        val min = minOrNull() ?: return 0
        return count { it == min }
    }

    companion object {
        private const val LOG_TAG = "WhisperCpuConfig"

        /**
         * Main entry used by [WhisperCpuConfig].
         * Returns number of detected big cores or a heuristic fallback.
         */
        fun determineHighPerfCpuCount(): Int = try {
            val info = readCpuInfo()
            val detected = info.computeHighPerfCpuCount()
            if (detected > 0) detected else safeFallback()
        } catch (e: Exception) {
            Log.d(LOG_TAG, "Failed to parse /proc/cpuinfo → using fallback", e)
            safeFallback()
        }

        /**
         * Graceful fallback when detection fails.
         * Uses availableProcessors() and basic assumptions:
         *  - If ≥8 logical cores: assume 4 are big (A78+/X3 class)
         *  - If fewer: use half the logical cores as big cores
         */
        private fun safeFallback(): Int {
            val total = Runtime.getRuntime().availableProcessors()
            val est = if (total >= 8) total - 4 else total / 2
            val result = est.coerceAtLeast(1)
            Log.d(LOG_TAG, "Fallback high-perf cores=$result (total=$total)")
            return result
        }

        /**
         * Reads /proc/cpuinfo safely and returns CpuInfo.
         * Throws if unreadable or empty.
         */
        private fun readCpuInfo(): CpuInfo {
            val file = File("/proc/cpuinfo")
            if (!file.canRead()) {
                Log.w(LOG_TAG, "Cannot read /proc/cpuinfo → using fallback")
                throw IllegalStateException("cpuinfo not readable")
            }
            val lines = file.useLines { it.toList() }
            if (lines.isEmpty()) throw IllegalStateException("cpuinfo empty")
            return CpuInfo(lines)
        }

        /**
         * Reads per-core max frequency (kHz).
         * Returns 0 if the file or directory does not exist.
         *
         * Typical path:
         *   /sys/devices/system/cpu/cpuX/cpufreq/cpuinfo_max_freq
         */
        private fun getMaxCpuFrequency(cpuIndex: Int): Int {
            val path = "/sys/devices/system/cpu/cpu$cpuIndex/cpufreq/cpuinfo_max_freq"
            return try {
                val value = File(path).takeIf { it.exists() }?.readText()?.trim()
                value?.toIntOrNull() ?: 0
            } catch (e: Exception) {
                Log.v(LOG_TAG, "No freq for cpu$cpuIndex: ${e.message}")
                0
            }
        }
    }
}

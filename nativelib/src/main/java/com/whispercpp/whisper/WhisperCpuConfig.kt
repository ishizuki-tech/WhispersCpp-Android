// file: com/whispercpp/whisper/WhisperCpuConfig.kt
// ============================================================
// ✅ WhisperCpuConfig — Adaptive Thread Optimizer (Full Debug Version)
// ------------------------------------------------------------
// • Dynamically detects big.LITTLE high-performance cores
// • Robust to /proc/cpuinfo or cpufreq access errors
// • Falls back safely to availableProcessors
// • Detailed debug logging for frequency / variant bins
// ============================================================

package com.whispercpp.whisper

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * Provides an optimized thread count for whisper.cpp inference.
 * - Detects number of high-performance CPU cores.
 * - Guarantees at least two threads.
 */
object WhisperCpuConfig {
    val preferredThreadCount: Int
        get() = CpuInfo.determineHighPerfCpuCount().coerceAtLeast(2)
}

/**
 * Internal CPU information helper for parsing /proc and /sys data.
 */
private class CpuInfo(private val lines: List<String>) {

    /** Main entry for computing high-performance core count. */
    fun computeHighPerfCpuCount(): Int = try {
        computeByFrequency()
    } catch (e: Exception) {
        Log.d(LOG_TAG, "Frequency detection failed → fallback to variant", e)
        computeByVariant()
    }

    /** Frequency-based estimate using cpuinfo_max_freq per core. */
    private fun computeByFrequency(): Int {
        val freqList = getCpuValues("processor") { getMaxCpuFrequency(it.toInt()) }
        Log.d(LOG_TAG, "CPU frequencies: ${freqList.binnedValues()}")
        return freqList.countDroppingMin()
    }

    /** Variant-based estimate (safe fallback). */
    private fun computeByVariant(): Int {
        val variants = getCpuValues("CPU variant") { it.substringAfter("0x").toIntOrNull(16) ?: 0 }
        Log.d(LOG_TAG, "CPU variants: ${variants.binnedValues()}")
        return variants.countKeepingMin()
    }

    /** Extracts integer values from /proc/cpuinfo by property name. */
    private fun getCpuValues(property: String, mapper: (String) -> Int): List<Int> =
        lines.asSequence()
            .filter { it.startsWith(property) }
            .mapNotNull { runCatching { mapper(it.substringAfter(':').trim()) }.getOrNull() }
            .sorted()
            .toList()

    /** Histogram for debug logging. */
    private fun List<Int>.binnedValues(): Map<Int, Int> =
        groupingBy { it }.eachCount()

    /** Count of elements greater than the minimum. */
    private fun List<Int>.countDroppingMin(): Int {
        val min = minOrNull() ?: return 0
        return count { it > min }
    }

    /** Count of elements equal to the minimum (for variant fallback). */
    private fun List<Int>.countKeepingMin(): Int {
        val min = minOrNull() ?: return 0
        return count { it == min }
    }

    companion object {
        private const val LOG_TAG = "WhisperCpuConfig"

        /** Public entry point.  */
        fun determineHighPerfCpuCount(): Int = try {
            val info = readCpuInfo()
            info.computeHighPerfCpuCount().takeIf { it > 0 }
                ?: safeFallback()
        } catch (e: Exception) {
            Log.d(LOG_TAG, "Failed to parse /proc/cpuinfo → fallback", e)
            safeFallback()
        }

        /** Safe fallback: subtract LITTLE-core guess or default to total. */
        private fun safeFallback(): Int {
            val total = Runtime.getRuntime().availableProcessors()
            // heuristic: assume up to 4 little cores if octa-core or higher
            val est = if (total >= 8) total - 4 else (total / 2)
            val result = est.coerceAtLeast(1)
            Log.d(LOG_TAG, "Fallback high-perf cores = $result (total=$total)")
            return result
        }

        /** Reads all lines from /proc/cpuinfo. */
        private fun readCpuInfo(): CpuInfo {
            val file = File("/proc/cpuinfo")
            if (!file.canRead()) {
                Log.w(LOG_TAG, "Cannot read /proc/cpuinfo; using fallback")
                throw IllegalStateException("cpuinfo not readable")
            }
            val lines = file.useLines { it.toList() }
            return CpuInfo(lines)
        }

        /**
         * Reads /sys/devices/system/cpu/cpuX/cpufreq/cpuinfo_max_freq.
         * Returns frequency in kHz, or 0 if unavailable.
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

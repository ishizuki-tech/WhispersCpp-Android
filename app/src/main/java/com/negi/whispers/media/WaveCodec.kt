// file: app/src/main/java/com/negi/whispers/media/WaveCodec.kt
package com.negi.whispers.media

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.min

private const val LOG_TAG = "WaveCodec"

/**
 * Robust WAV (RIFF) PCM decoder for Android Whisper integration.
 *
 * Features:
 *  - Supports PCM 16-bit (format 1) and IEEE Float 32-bit (format 3)
 *  - Handles mono/stereo/multichannel input (mixes down to mono)
 *  - Ignores unknown chunks and odd-byte padding between chunks
 *  - Optionally resamples to a given target sample rate via linear interpolation
 *
 * Output:
 *  - Normalized mono FloatArray in [-1.0, 1.0] domain
 *
 * Typical use:
 *  val samples = decodeWaveFile(file, targetSampleRate = 16000)
 *
 * @throws IOException if the file is malformed or chunk data is invalid
 * @throws IllegalArgumentException if file does not exist or is unreadable
 */
@Throws(IOException::class, IllegalArgumentException::class)
fun decodeWaveFile(
    file: File,
    targetSampleRate: Int = 16_000
): FloatArray {
    require(file.exists()) { "File not found: ${file.path}" }

    val bytes = file.readBytes()
    if (bytes.size < 44) throw IOException("Invalid WAV: header too short (${bytes.size} bytes)")

    // Validate RIFF and WAVE headers
    val riff = String(bytes, 0, 4, Charsets.US_ASCII)
    val wave = String(bytes, 8, 4, Charsets.US_ASCII)
    if (riff != "RIFF" || wave != "WAVE") {
        throw IOException("Invalid RIFF/WAVE header: riff='$riff' wave='$wave'")
    }

    // Default metadata placeholders
    var pos = 12
    var audioFormat = 1
    var channels = 1
    var sampleRate = 16_000
    var bitsPerSample = 16

    var dataStart = -1
    var dataSizeHeader = 0
    var dataEffectiveSize = 0

    // Parse all RIFF chunks until 'data' found
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4, Charsets.US_ASCII)
        val size = readLE32(bytes, pos + 4).coerceAtLeast(0)
        val chunkDataStart = pos + 8
        val chunkDataEnd = min(bytes.size, chunkDataStart + size)

        when (id) {
            "fmt " -> {
                // --- Parse format chunk ---
                if (chunkDataEnd - chunkDataStart < 16)
                    throw IOException("Invalid 'fmt ' chunk (size=$size)")

                val bb = ByteBuffer
                    .wrap(bytes, chunkDataStart, chunkDataEnd - chunkDataStart)
                    .order(ByteOrder.LITTLE_ENDIAN)

                audioFormat = bb.short.toInt() and 0xFFFF
                channels = bb.short.toInt() and 0xFFFF
                sampleRate = bb.int
                /* byteRate */ bb.int
                /* blockAlign */ bb.short
                bitsPerSample = bb.short.toInt() and 0xFFFF

                Log.d(LOG_TAG, "fmt: format=$audioFormat, ch=$channels, rate=$sampleRate, bits=$bitsPerSample")
            }

            "data" -> {
                // --- Found PCM data chunk ---
                dataStart = chunkDataStart
                dataSizeHeader = size
                dataEffectiveSize = (chunkDataEnd - chunkDataStart).coerceAtLeast(0)
                Log.v(LOG_TAG, "data chunk start=$dataStart size=$dataEffectiveSize")
            }

            else -> {
                // --- Skip all other chunks (LIST, fact, INFO, etc.) ---
                Log.v(LOG_TAG, "Skip chunk: '$id' ($size bytes)")
            }
        }

        // Advance to next chunk (size is padded to even bytes)
        val padded = size + (size and 1)
        pos += 8 + padded
        if (pos < 0) break
    }

    // Validate final state
    if (dataStart < 0 || dataEffectiveSize <= 0)
        throw IOException("Missing or empty 'data' chunk")
    if (channels <= 0) throw IOException("Invalid channel count: $channels")
    if (audioFormat !in listOf(1, 3))
        throw IOException("Unsupported format: $audioFormat (only 1=PCM, 3=FLOAT supported)")
    if (audioFormat == 1 && bitsPerSample != 16)
        throw IOException("Unsupported PCM bit depth: $bitsPerSample (only 16-bit supported)")
    if (audioFormat == 3 && bitsPerSample != 32)
        throw IOException("Unsupported FLOAT bit depth: $bitsPerSample (only 32-bit supported)")

    // Decode PCM samples into mono float buffer
    val monoSrc: FloatArray = when (audioFormat) {
        1 -> decodePcm16ToMono(bytes, dataStart, dataEffectiveSize, channels)
        3 -> decodeFloat32ToMono(bytes, dataStart, dataEffectiveSize, channels)
        else -> error("Guarded by validation")
    }

    Log.d(
        LOG_TAG,
        "Decoded WAV: ${channels}ch ${sampleRate}Hz ${bitsPerSample}-bit " +
                "(frames=${monoSrc.size}, dataHeader=$dataSizeHeader, effective=$dataEffectiveSize)"
    )

    // Optional resampling
    return if (sampleRate == targetSampleRate) {
        monoSrc
    } else {
        Log.w(LOG_TAG, "Resampling ${sampleRate}Hz → ${targetSampleRate}Hz (linear interpolation)")
        resampleLinearEndpointAligned(monoSrc, sampleRate, targetSampleRate)
    }
}

/**
 * Decodes 16-bit PCM little-endian samples and mixes all channels into mono.
 */
private fun decodePcm16ToMono(
    bytes: ByteArray,
    start: Int,
    effectiveSize: Int,
    channels: Int
): FloatArray {
    if (effectiveSize <= 0) return FloatArray(0)
    val bytesPerFrame = 2 * channels
    if (bytesPerFrame <= 0) return FloatArray(0)

    val frameCount = (effectiveSize / bytesPerFrame).coerceAtLeast(0)
    val out = FloatArray(frameCount)

    val bb = ByteBuffer
        .wrap(bytes, start, min(effectiveSize, bytes.size - start))
        .order(ByteOrder.LITTLE_ENDIAN)

    for (i in 0 until frameCount) {
        var acc = 0f
        for (ch in 0 until channels) {
            val s = bb.short.toInt() // signed 16-bit sample
            acc += s / 32768f
        }
        out[i] = (acc / channels).coerceIn(-1f, 1f)
    }
    return out
}

/**
 * Decodes IEEE 32-bit float PCM samples and mixes channels to mono.
 */
private fun decodeFloat32ToMono(
    bytes: ByteArray,
    start: Int,
    effectiveSize: Int,
    channels: Int
): FloatArray {
    if (effectiveSize <= 0) return FloatArray(0)
    val bytesPerFrame = 4 * channels
    if (bytesPerFrame <= 0) return FloatArray(0)

    val frameCount = (effectiveSize / bytesPerFrame).coerceAtLeast(0)
    val out = FloatArray(frameCount)

    val bb = ByteBuffer
        .wrap(bytes, start, min(effectiveSize, bytes.size - start))
        .order(ByteOrder.LITTLE_ENDIAN)

    for (i in 0 until frameCount) {
        var acc = 0f
        for (ch in 0 until channels) {
            acc += bb.float
        }
        out[i] = (acc / channels).coerceIn(-1f, 1f)
    }
    return out
}

/**
 * Simple endpoint-aligned linear resampler.
 * Guarantees that first and last sample align between source and target.
 */
private fun resampleLinearEndpointAligned(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
    if (src.isEmpty() || srcRate <= 0 || dstRate <= 0) return src

    val dstLen = if (src.size == 1) 1
    else ((src.size - 1).toLong() * dstRate / srcRate + 1)
        .toInt().coerceAtLeast(1)

    val dst = FloatArray(dstLen)
    if (dstLen == 1) {
        dst[0] = src[0]
        return dst
    }

    val step = (src.size - 1).toDouble() / (dstLen - 1).toDouble()
    for (i in 0 until dstLen) {
        val x = i * step
        val i0 = floor(x).toInt().coerceIn(0, src.lastIndex)
        val i1 = (i0 + 1).coerceAtMost(src.lastIndex)
        val frac = (x - i0).toFloat()
        dst[i] = src[i0] + (src[i1] - src[i0]) * frac
    }
    return dst
}

/**
 * Reads 32-bit little-endian integer from byte array safely.
 */
private fun readLE32(b: ByteArray, off: Int): Int {
    if (off < 0 || off + 4 > b.size) return 0
    return (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}

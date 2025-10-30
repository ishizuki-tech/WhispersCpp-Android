// file: app/src/main/java/com/negi/whispers/recorder/Recorder.kt
package com.negi.whispers.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-threaded WAV recorder.
 * - Deterministic state machine: Idle → Starting → Recording → Stopping → Idle
 * - Unblocks AudioRecord.read() on stop() to flush PCM then writes a valid WAV header
 * - Resilient to rapid Record→Stop taps; Start while Starting/Recording is ignored
 */
class Recorder(
    private val context: Context,
    private val onError: (Exception) -> Unit
) : Closeable {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RecorderThread").apply { priority = Thread.NORM_PRIORITY }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private var job: Job? = null
    private val activeRecorder = AtomicReference<AudioRecord?>(null)

    private var tempPcm: File? = null
    private var targetWav: File? = null
    private var cfg: Config? = null

    private enum class State { Idle, Starting, Recording, Stopping }
    private val state = AtomicReference(State.Idle)

    fun isActive(): Boolean = when (state.get()) {
        State.Starting, State.Recording, State.Stopping -> true
        else -> false
    }

    /** Start recording on a dedicated single thread (idempotent & race-free). */
    fun startRecording(
        output: File,
        rates: IntArray = intArrayOf(16_000, 48_000, 44_100)
    ) {
        if (!state.compareAndSet(State.Idle, State.Starting)) {
            Log.w(TAG, "startRecording: ignored, state=${state.get()}")
            return
        }
        targetWav = output

        scope.launch {
            try {
                checkPermission()
                val conf = findConfig(rates) ?: error("No valid AudioRecord config")
                cfg = conf

                val rec = buildRecorder(conf)
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release(); error("AudioRecord init failed")
                }

                if (state.get() != State.Starting) {
                    Log.w(TAG, "startRecording: aborted (state=${state.get()}) before start")
                    rec.release()
                    cleanup()
                    state.set(State.Idle)
                    return@launch
                }

                activeRecorder.set(rec)
                tempPcm = File.createTempFile("rec_", ".pcm", context.cacheDir)
                val tmp = tempPcm!!

                state.set(State.Recording)
                job = launch {
                    FileOutputStream(tmp).use { fos ->
                        val bos = BufferedOutputStream(fos, conf.bufferSize)
                        val shortBuf = ShortArray(conf.bufferSize / 2)
                        val byteBuf = ByteArray(shortBuf.size * 2) // reused

                        try {
                            rec.startRecording()
                            Log.i(TAG, "🎙 start ${conf.sampleRate}Hz buf=${conf.bufferSize}")
                            while (isActive) {
                                val n = rec.read(shortBuf, 0, shortBuf.size) // blocking
                                if (n <= 0) {
                                    if (n < 0) Log.w(TAG, "read() error=$n; break")
                                    break
                                }
                                var j = 0
                                for (i in 0 until n) {
                                    val v = shortBuf[i].toInt()
                                    byteBuf[j++] = (v and 0xFF).toByte()
                                    byteBuf[j++] = ((v ushr 8) and 0xFF).toByte()
                                }
                                bos.write(byteBuf, 0, n * 2)
                            }
                            bos.flush()
                        } finally {
                            runCatching { rec.stop() }
                            runCatching { rec.release() }
                            activeRecorder.compareAndSet(rec, null)
                            Log.i(TAG, "🎙 stopped & released")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startRecording failed", e)
                onError(e)
                state.set(State.Idle)
                activeRecorder.getAndSet(null)?.let {
                    runCatching { it.stop() }; runCatching { it.release() }
                }
                cleanup()
            }
        }
    }

    /** Stop recording: unblock read(), join writer, then write a valid WAV header. */
    suspend fun stopRecording() = withContext(Dispatchers.IO) {
        Log.d("Recorder", "stopRecording() invoked (state=${state.get()})")

        val s = state.get()
        if (s == State.Idle) {
            Log.w("Recorder", "stopRecording: already idle")
            return@withContext
        }
        if (s == State.Starting || s == State.Recording) {
            state.set(State.Stopping)
        }

        activeRecorder.getAndSet(null)?.let {
            Log.d("Recorder", "Calling it.stop() ...")
            runCatching { it.stop() }.onFailure { Log.w("Recorder", "stop() failed: $it") }
            Log.d("Recorder", "it.stop() returned")
        }

        try {
            // 💥 強制キャンセル + join
            job?.cancel()
            Log.d("Recorder", "Joining writer job ...")
            job?.join()
            Log.d("Recorder", "Writer job joined successfully")

            val pcm = tempPcm
            val wav = targetWav
            val c = cfg
            Log.d("Recorder", "post-join: pcm=$pcm wav=$wav cfg=$c")

            if (pcm == null || wav == null || c == null) {
                Log.w("Recorder", "stopRecording: missing pieces")
                state.set(State.Idle)
                return@withContext
            }

            writeWavFromPcm(pcm, wav, c.sampleRate, 1, 16)
            Log.d("Recorder", "WAV header written")

            if (wav.length() <= 44L) {
                Log.w("Recorder", "WAV too short (<=44) deleting: ${wav.path}")
                wav.delete()
            } else {
                Log.i("Recorder", "✅ WAV written ${wav.path} (${wav.length()} bytes)")
            }
        } catch (e: Exception) {
            Log.e("Recorder", "stopRecording error", e)
            onError(e)
        } finally {
            cleanup()
            state.set(State.Idle)
            Log.d("Recorder", "Cleanup done, state reset to Idle")
        }
    }

    // ----------------------------- internals -----------------------------
    private fun writeWavFromPcm(pcm: File, wav: File, rate: Int, ch: Int, bits: Int) {
        val size = pcm.length().toInt().coerceAtLeast(0)
        val byteRate = rate * ch * bits / 8
        val blockAlign = (ch * bits / 8).toShort()
        DataOutputStream(BufferedOutputStream(FileOutputStream(wav))).use { out ->
            out.writeBytes("RIFF"); out.writeIntLE(size + 36)
            out.writeBytes("WAVE")
            out.writeBytes("fmt "); out.writeIntLE(16)
            out.writeShortLE(1); out.writeShortLE(ch)
            out.writeIntLE(rate); out.writeIntLE(byteRate)
            out.writeShortLE(blockAlign.toInt()); out.writeShortLE(bits)
            out.writeBytes("data"); out.writeIntLE(size)
            FileInputStream(pcm).use { it.copyTo(out) }
        }
    }

    private fun DataOutputStream.writeShortLE(v: Int) {
        write(v and 0xFF); write((v ushr 8) and 0xFF)
    }
    private fun DataOutputStream.writeIntLE(v: Int) {
        write(v and 0xFF); write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
    }

    private fun cleanup() {
        runCatching { tempPcm?.delete() }
        tempPcm = null
        targetWav = null
        cfg = null
        job = null
    }

    private fun checkPermission() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
            error("No microphone")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("RECORD_AUDIO not granted")
    }

    private data class Config(
        val sampleRate: Int,
        val channelMask: Int,
        val format: Int,
        val bufferSize: Int
    )

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun findConfig(rates: IntArray): Config? {
        val ch = AudioFormat.CHANNEL_IN_MONO
        val fmt = AudioFormat.ENCODING_PCM_16BIT
        for (r in rates) {
            val minBuf = AudioRecord.getMinBufferSize(r, ch, fmt)
            if (minBuf <= 0) continue
            val test = AudioRecord(MediaRecorder.AudioSource.MIC, r, ch, fmt, minBuf)
            val ok = test.state == AudioRecord.STATE_INITIALIZED
            test.release()
            if (ok) {
                // Use minimal buffer to reduce start latency for short clips
                val buffer = minBuf
                return Config(r, ch, fmt, buffer)
            }
        }
        return null
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun buildRecorder(c: Config) = AudioRecord(
        MediaRecorder.AudioSource.MIC, c.sampleRate, c.channelMask, c.format, c.bufferSize
    )

    override fun close() {
        runBlocking { if (isActive()) stopRecording() }
        runCatching { dispatcher.close() }
        runCatching { executor.shutdownNow() }
    }

    companion object { private const val TAG = "Recorder" }
}

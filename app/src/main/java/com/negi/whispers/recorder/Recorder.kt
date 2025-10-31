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
 * High-reliability single-threaded WAV recorder.
 *
 * State machine:
 *   Idle → Starting → Recording → Stopping → Idle
 *
 * Technical highlights:
 *  • Dedicated single thread + coroutine scope ensures sequential audio operations.
 *  • Writes temporary PCM → rewrites valid RIFF/WAVE header upon stop().
 *  • Supports rapid Record→Stop taps without race conditions.
 *  • Uses atomic state and AudioRecord release safety.
 *
 * Typical lifecycle:
 *   startRecording(File)
 *   stopRecording()  // suspending
 *   close()          // on ViewModel/Activity teardown
 */
class Recorder(
    private val context: Context,
    private val onError: (Exception) -> Unit
) : Closeable {

    // -------------------------------------------------------------------------
    // Dedicated single-thread execution context
    // -------------------------------------------------------------------------
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RecorderThread").apply { priority = Thread.NORM_PRIORITY }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // Start Recording
    // -------------------------------------------------------------------------
    /**
     * Starts recording in a deterministic, race-free manner.
     * Idempotent — calling during active/starting state is ignored.
     *
     * @param output target WAV file (will be overwritten)
     * @param rates prioritized sample rate candidates
     */
    fun startRecording(
        output: File,
        rates: IntArray = intArrayOf(16_000, 48_000, 44_100)
    ) {
        if (!state.compareAndSet(State.Idle, State.Starting)) {
            Log.w(TAG, "startRecording ignored: current=${state.get()}")
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

                // Abort early if user already stopped before init finished
                if (state.get() != State.Starting) {
                    Log.w(TAG, "startRecording aborted: premature stop, state=${state.get()}")
                    rec.release(); cleanup(); state.set(State.Idle)
                    return@launch
                }

                activeRecorder.set(rec)
                tempPcm = File.createTempFile("rec_", ".pcm", context.cacheDir)
                val tmp = tempPcm!!

                state.set(State.Recording)

                // Writer coroutine: blocking read() loop → PCM write → stop
                job = launch {
                    FileOutputStream(tmp).use { fos ->
                        val bos = BufferedOutputStream(fos, conf.bufferSize)
                        val shortBuf = ShortArray(conf.bufferSize / 2)
                        val byteBuf = ByteArray(shortBuf.size * 2)

                        try {
                            rec.startRecording()
                            Log.i(TAG, "🎙 start ${conf.sampleRate}Hz buf=${conf.bufferSize}")

                            while (isActive) {
                                val n = rec.read(shortBuf, 0, shortBuf.size)
                                if (n <= 0) {
                                    if (n < 0) Log.w(TAG, "read() error=$n → break")
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
                            Log.i(TAG, "🎙 stopped & released (writer exit)")
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

    // -------------------------------------------------------------------------
    // Stop Recording
    // -------------------------------------------------------------------------
    /**
     * Stops recording and finalizes the WAV file.
     *
     * Steps:
     *  1. Unblock AudioRecord.read() via stop()
     *  2. Cancel & join writer coroutine
     *  3. Write valid RIFF/WAV header to target file
     *  4. Cleanup temp PCM and reset state
     */
    suspend fun stopRecording() = withContext(Dispatchers.IO) {
        Log.d(TAG, "stopRecording() invoked (state=${state.get()})")

        val s = state.get()
        if (s == State.Idle) {
            Log.w(TAG, "stopRecording: already idle")
            return@withContext
        }
        if (s == State.Starting || s == State.Recording) {
            state.set(State.Stopping)
        }

        // Stop AudioRecord (non-blocking safety)
        activeRecorder.getAndSet(null)?.let {
            Log.d(TAG, "Calling AudioRecord.stop() ...")
            runCatching { it.stop() }.onFailure { Log.w(TAG, "stop() failed: $it") }
            runCatching { it.release() }
        }

        try {
            // Cancel writer job gracefully and await completion
            job?.cancel()
            Log.d(TAG, "Joining writer job ...")
            job?.join()
            Log.d(TAG, "Writer job joined successfully")

            val pcm = tempPcm
            val wav = targetWav
            val c = cfg
            Log.d(TAG, "post-join: pcm=$pcm wav=$wav cfg=$c")

            if (pcm == null || wav == null || c == null) {
                Log.w(TAG, "stopRecording: missing pieces (aborting WAV write)")
                state.set(State.Idle)
                return@withContext
            }

            // Write WAV header and merge PCM payload
            writeWavFromPcm(pcm, wav, c.sampleRate, 1, 16)
            Log.d(TAG, "WAV header written")

            if (wav.length() <= 44L) {
                Log.w(TAG, "WAV too short (<=44 bytes), deleting: ${wav.path}")
                wav.delete()
            } else {
                Log.i(TAG, "✅ WAV finalized: ${wav.path} (${wav.length()} bytes)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording error", e)
            onError(e)
        } finally {
            cleanup()
            state.set(State.Idle)
            Log.d(TAG, "Cleanup complete, state=Idle")
        }
    }

    // -------------------------------------------------------------------------
    // Internal Helpers
    // -------------------------------------------------------------------------
    /** Writes RIFF/WAVE header + PCM data → valid WAV file. */
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

    /** Deletes temporary files and resets cached config. */
    private fun cleanup() {
        runCatching { tempPcm?.delete() }
        tempPcm = null; targetWav = null; cfg = null; job = null
    }

    /** Validates microphone presence and permission. */
    private fun checkPermission() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
            error("No microphone present on device")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("RECORD_AUDIO permission not granted")
    }

    /** Audio configuration container. */
    private data class Config(
        val sampleRate: Int,
        val channelMask: Int,
        val format: Int,
        val bufferSize: Int
    )

    /**
     * Iterates candidate sample rates, returns first working AudioRecord config.
     * Uses minimal buffer to reduce latency for short clips.
     */
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
            if (ok) return Config(r, ch, fmt, minBuf)
        }
        return null
    }

    /** Builds a new AudioRecord instance. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun buildRecorder(c: Config) = AudioRecord(
        MediaRecorder.AudioSource.MIC, c.sampleRate, c.channelMask, c.format, c.bufferSize
    )

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------
    /**
     * Safely stops recording and releases resources.
     * Blocks until cleanup is complete.
     */
    override fun close() {
        runBlocking { if (isActive()) stopRecording() }
        runCatching { dispatcher.close() }
        runCatching { executor.shutdownNow() }
    }

    companion object { private const val TAG = "Recorder" }
}

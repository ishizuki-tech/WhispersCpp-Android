/**
 * Main Screen ViewModel
 * ----------------------
 * Central controller for Whisper App's main screen logic.
 * It manages audio recording, transcription, playback, and record persistence.
 *
 * Responsibilities:
 * - Coordinates UI state with background operations.
 * - Loads and manages Whisper model context.
 * - Handles safe recording start/stop via [Recorder].
 * - Executes offline transcription with Whisper.cpp.
 * - Persists recording logs and metadata as JSON.
 *
 * Threading:
 * - All UI state mutations run on Main dispatcher.
 * - File I/O and model loading occur on IO dispatcher.
 * - Long-running transcription jobs execute on Default dispatcher.
 */

@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.negi.whispers.ui.main

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.negi.whispers.media.decodeWaveFile
import com.negi.whispers.recorder.Recorder
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "MainScreenViewModel"

/**
 * Provides state and logic for the main Whisper screen.
 * Manages user recording, transcription, and playback lifecycle.
 */
class MainScreenViewModel(private val app: Application) : ViewModel() {

    // ---------------------------------------------------------------------
    // UI States
    // ---------------------------------------------------------------------

    var selectedLanguage by mutableStateOf("en"); private set
    var selectedModel by mutableStateOf("ggml-model-q4_0.bin"); private set

    var canTranscribe by mutableStateOf(false); private set
    var isRecording by mutableStateOf(false); private set
    var isModelLoading by mutableStateOf(false); private set
    var hasAllRequiredPermissions by mutableStateOf(false); private set
    var translateToEnglish by mutableStateOf(false); private set
    var myRecords by mutableStateOf<List<MyRecord>>(emptyList()); private set

    // ---------------------------------------------------------------------
    // Internal Resources
    // ---------------------------------------------------------------------

    private val modelsDir = File(app.filesDir, "models")
    private val recDir = File(app.filesDir, "recordings")
    private var whisperCtx: WhisperContext? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentFile: File? = null

    private val transcribeJobRef = AtomicReference<Job?>(null)
    private val saveMutex = Mutex()
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private var recordStartMs: Long = 0L

    private val recorder = Recorder(app) { e ->
        Log.e(TAG, "Recorder error", e)
        isRecording = false
        addToastLog("⛔ Recorder error: ${e.message}")
    }

    // ---------------------------------------------------------------------
    // Initialization
    // ---------------------------------------------------------------------

    init {
        viewModelScope.launch {
            setupDirs()
            loadRecords()
            updatePermissionsStatus()
            loadModel(selectedModel)
        }
        viewModelScope.launch {
            var first = true
            snapshotFlow<List<MyRecord>> { myRecords }.collectLatest {
                if (first) first = false else saveRecords()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------

    fun getRequiredPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            list += Manifest.permission.POST_NOTIFICATIONS
        return list
    }

    fun updatePermissionsStatus() {
        hasAllRequiredPermissions = getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
        }
        Log.d(TAG, "Permissions: $hasAllRequiredPermissions")
    }

    // ---------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------

    fun updateSelectedLanguage(lang: String) { selectedLanguage = lang }
    fun updateTranslate(enable: Boolean) { translateToEnglish = enable }

    fun updateSelectedModel(model: String) {
        if (model == selectedModel) return
        selectedModel = model
        viewModelScope.launch { loadModel(model) }
    }

    // ---------------------------------------------------------------------
    // Model Loading
    // ---------------------------------------------------------------------

    private suspend fun loadModel(model: String) {
        if (isModelLoading) return
        isModelLoading = true
        canTranscribe = false
        try {
            releaseWhisper()
            releaseMediaPlayer()
            whisperCtx = withContext(Dispatchers.IO) {
                WhisperContext.createContextFromAsset(app.assets, "models/$model")
            }
            addToastLog("📦 Model loaded: $model")
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed", e)
            addToastLog("⛔ Model load failed: ${e.message}")
        } finally {
            isModelLoading = false
            canTranscribe = true
        }
    }

    // ---------------------------------------------------------------------
    // Recording Controls
    // ---------------------------------------------------------------------

    /**
     * Starts or stops recording.
     * When stopped, triggers automatic transcription.
     */
    fun toggleRecord(onScrollToIndex: (Int) -> Unit) =
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (isModelLoading) {
                addToastLog("⏳ Model loading...")
                return@launch
            }
            try {
                if (isRecording) {
                    // Stop recording
                    isRecording = false
                    addToastLog("⏹ Stopping...")
                    val elapsed = SystemClock.elapsedRealtime() - recordStartMs
                    if (elapsed < 800L) delay(800L - elapsed)

                    withContext(Dispatchers.IO) { recorder.stopRecording() }

                    val file = currentFile
                    currentFile = null
                    if (file == null || !file.exists()) {
                        addToastLog("⚠️ Recording missing")
                        return@launch
                    }
                    if (file.length() <= 44L) {
                        addToastLog("⚠️ WAV too short / silent")
                        return@launch
                    }

                    addNewRecordingLog(file.name, file.absolutePath)
                    val recIndex = myRecords.lastIndex
                    onScrollToIndex(recIndex)
                    addResultLog("🧠 Transcribing...", recIndex)
                    startTranscriptionJob(file, recIndex)
                } else {
                    // Start recording
                    if (!hasAllRequiredPermissions) {
                        addToastLog("⚠️ Grant microphone permission")
                        return@launch
                    }
                    stopPlayback()
                    val file = withContext(Dispatchers.IO) { createNewAudioFile() }
                    currentFile = file
                    recordStartMs = SystemClock.elapsedRealtime()
                    addToastLog("🎙️ Recording started...")
                    recorder.startRecording(file, intArrayOf(16_000, 48_000, 44_100))
                    isRecording = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "toggleRecord failed", e)
                isRecording = false
                addToastLog("⛔ toggleRecord failed: ${e.message}")
            }
        }

    // ---------------------------------------------------------------------
    // Re-Transcription
    // ---------------------------------------------------------------------

    private var lastReTranscribeMs = 0L

    /**
     * Re-runs transcription for an existing record.
     * Prevents rapid re-trigger with debounce guard.
     */
    fun reTranscribe(index: Int) {
        viewModelScope.launch {
            val now = SystemClock.elapsedRealtime()
            if (now - lastReTranscribeMs < 1000L) return@launch
            lastReTranscribeMs = now

            if (isRecording || isModelLoading) {
                addToastLog("⚠️ Busy (recording or loading)")
                return@launch
            }

            if (index !in myRecords.indices) {
                addToastLog("⚠️ Invalid record index: $index")
                return@launch
            }

            val rec = myRecords[index]
            val file = File(rec.absolutePath)
            if (!file.exists()) {
                addResultLog("⛔ Missing file: ${file.name}", index)
                return@launch
            }

            addResultLog("🔁 Re-transcribing ${file.name}...", index)
            startTranscriptionJob(file, index)
        }
    }

    // ---------------------------------------------------------------------
    // Transcription
    // ---------------------------------------------------------------------

    private fun startTranscriptionJob(file: File, index: Int) {
        transcribeJobRef.getAndSet(null)?.cancel()
        val job = viewModelScope.launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            transcribeAudio(file, index)
        }
        job.invokeOnCompletion { e ->
            addResultLog(
                if (e == null) "✅ Transcription completed" else "⛔ Transcription failed: ${e.message}",
                index
            )
        }
        transcribeJobRef.set(job)
    }

    private suspend fun transcribeAudio(file: File, index: Int = -1) {
        val ctx = whisperCtx ?: run {
            addResultLog("⛔ Model not loaded", index)
            return
        }
        canTranscribe = false
        try {
            val samples = withContext(Dispatchers.IO) { decodeWaveFile(file) }
            if (samples.isEmpty()) {
                addResultLog("⛔ No audio samples", index)
                return
            }
            val start = System.currentTimeMillis()
            val text = ctx.transcribeData(samples, selectedLanguage, translateToEnglish)
                ?: "(no result)"
            val elapsed = System.currentTimeMillis() - start
            addResultLog(
                """
                ✅ Transcribed (${elapsed} ms)
                Model: $selectedModel
                Lang: $selectedLanguage${if (translateToEnglish) "→en" else ""}
                $text
                """.trimIndent(), index
            )
        } catch (e: Exception) {
            Log.e(TAG, "Transcribe failed", e)
            addResultLog("⛔ Transcribe failed: ${e.message}", index)
        } finally {
            canTranscribe = true
        }
    }

    // ---------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------

    fun playRecording(path: String, index: Int) = viewModelScope.launch {
        if (isRecording) return@launch
        val f = File(path)
        if (!f.exists()) {
            addResultLog("⛔ Missing: ${f.name}", index)
            return@launch
        }
        stopPlayback()
        startPlayback(f)
        addResultLog("▶ Playing: ${f.name}", index)
    }

    private suspend fun startPlayback(f: File) = withContext(Dispatchers.Main.immediate) {
        releaseMediaPlayer()
        mediaPlayer = MediaPlayer.create(app, f.absolutePath.toUri())?.apply {
            setOnCompletionListener {
                runCatching { stop() }
                runCatching { release() }
                mediaPlayer = null
            }
            start()
        }
    }

    suspend fun stopPlayback() = withContext(Dispatchers.Main.immediate) {
        releaseMediaPlayer()
    }

    // ---------------------------------------------------------------------
    // Record Management
    // ---------------------------------------------------------------------

    fun removeRecordAt(index: Int) {
        if (index !in myRecords.indices) return
        runCatching { File(myRecords[index].absolutePath).delete() }
        myRecords = myRecords.toMutableList().apply { removeAt(index) }
        addToastLog("🗑 Deleted recording #$index")
    }

    private fun addNewRecordingLog(name: String, path: String) {
        val ts = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(Date())
        myRecords = myRecords + MyRecord("🎤 $name recorded at $ts", path)
    }

    private fun addResultLog(text: String, index: Int) {
        val i = if (index == -1) myRecords.lastIndex else index
        if (i in myRecords.indices) {
            val m = myRecords.toMutableList()
            m[i] = m[i].copy(logs = m[i].logs + "\n" + text)
            myRecords = m
        }
    }

    private fun addToastLog(msg: String) {
        myRecords = (myRecords + MyRecord(msg, "")).takeLast(200)
    }

    suspend fun saveRecords() = withContext(NonCancellable + Dispatchers.IO) {
        saveMutex.withLock {
            runCatching {
                File(app.filesDir, "records.json").writeText(
                    json.encodeToString(ListSerializer(MyRecordSerializer), myRecords)
                )
            }.onFailure { Log.e(TAG, "Save failed", it) }
        }
    }

    private suspend fun loadRecords() = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(app.filesDir, "records.json")
            if (f.exists()) {
                myRecords = json.decodeFromString(ListSerializer(MyRecordSerializer), f.readText())
            }
        }.onFailure { Log.e(TAG, "Load failed", it) }
    }

    // ---------------------------------------------------------------------
    // Filesystem & Cleanup
    // ---------------------------------------------------------------------

    private suspend fun createNewAudioFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(recDir, "rec_$ts.wav")
    }

    private suspend fun setupDirs() = withContext(Dispatchers.IO) {
        modelsDir.mkdirs(); recDir.mkdirs()
    }

    private suspend fun releaseWhisper() = withContext(Dispatchers.IO) {
        runCatching { whisperCtx?.release() }
        whisperCtx = null
    }

    private suspend fun releaseMediaPlayer() = withContext(Dispatchers.Main.immediate) {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.Main.immediate) {
            transcribeJobRef.getAndSet(null)?.cancel()
            withContext(NonCancellable) {
                releaseWhisper()
                releaseMediaPlayer()
                runCatching { recorder.close() }
            }
        }
    }

    companion object {
        fun factory(app: Application) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(c: Class<T>): T =
                MainScreenViewModel(app) as T
        }
    }
}

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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "MainScreenViewModel"

class MainScreenViewModel(private val app: Application) : ViewModel() {

    // --- UI flags ---
    var canTranscribe by mutableStateOf(false); private set
    var isRecording by mutableStateOf(false); private set
    var isModelLoading by mutableStateOf(false); private set
    var hasAllRequiredPermissions by mutableStateOf(false); private set

    // --- Config ---
    var selectedLanguage by mutableStateOf("en"); private set
    var selectedModel by mutableStateOf("ggml-tiny-q5_1.bin"); private set
    var translateToEnglish by mutableStateOf(false); private set

    // --- Records ---
    var myRecords by mutableStateOf<List<MyRecord>>(emptyList()); private set

    // --- Resources ---
    private val modelsDir = File(app.filesDir, "models")
    private val recDir = File(app.filesDir, "recordings")
    private var whisperCtx: WhisperContext? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentFile: File? = null

    private val transcribeJobRef = AtomicReference<Job?>(null)
    private val recorder = Recorder(app) { e ->
        Log.e(TAG, "Recorder error", e)
        isRecording = false
        addToastLog("⛔ Recorder error: ${e.message}")
    }

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private var recordStartMs: Long = 0L

    init {
        viewModelScope.launch {
            setupDirs()
            loadRecords()
            updatePermissionsStatus()
            loadModel(selectedModel)
        }
        viewModelScope.launch {
            var first = true
            snapshotFlow { myRecords }.collectLatest {
                if (first) first = false else saveRecords()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------
    fun updateSelectedLanguage(lang: String) { selectedLanguage = lang }
    fun updateTranslate(enable: Boolean) { translateToEnglish = enable }
    fun updateSelectedModel(model: String) {
        if (model == selectedModel) return
        selectedModel = model
        viewModelScope.launch { loadModel(model) }
    }

    // -------------------------------------------------------------------------
    // Model loading
    // -------------------------------------------------------------------------
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

    // -------------------------------------------------------------------------
    // Recording start/stop
    // -------------------------------------------------------------------------
    fun toggleRecord(onScrollToIndex: (Int) -> Unit) =
        viewModelScope.launch(Dispatchers.Main.immediate) {
            Log.d(TAG, "toggleRecord() invoked: isRecording=$isRecording, isModelLoading=$isModelLoading")

            if (isModelLoading) {
                addToastLog("⏳ Model loading...")
                return@launch
            }

            try {
                if (isRecording) {
                    // ----- STOP -----
                    Log.d(TAG, "STOP block entered (isRecording=true)")
                    isRecording = false
                    addToastLog("⏹ Stopping...")

                    val minDurationMs = 800L
                    val elapsed = SystemClock.elapsedRealtime() - recordStartMs
                    if (elapsed < minDurationMs) {
                        withContext(Dispatchers.IO) { delay(minDurationMs - elapsed) }
                    }

                    // Run stop on IO dispatcher
                    withContext(Dispatchers.IO) {
                        Log.d(TAG, "Calling recorder.stopRecording() ...")
                        recorder.stopRecording()
                        Log.d(TAG, "recorder.stopRecording() returned")
                    }

                    val file = currentFile
                    currentFile = null
                    if (file == null || !file.exists()) {
                        addToastLog("⚠️ Recording missing")
                        return@launch
                    }
                    val len = file.length()
                    if (len <= 44L) {
                        val reason =
                            "WAV too short (<=44B). bytes=$len, elapsed=${elapsed}ms — probably silent or too quick tap."
                        addToastLog("⚠️ $reason")
                        Log.w(TAG, reason)
                        return@launch
                    }

                    addNewRecordingLog(file.name, file.absolutePath)
                    val recIndex = myRecords.lastIndex
                    onScrollToIndex(recIndex)
                    addResultLog("🧠 Transcribing...", recIndex)

                    // --- schedule transcription job ---
                    addResultLog("🧵 Scheduling transcription job for ${file.name}", recIndex)
                    transcribeJobRef.getAndSet(null)?.cancel()
                    val job = viewModelScope.launch(Dispatchers.Default) {
                        Log.d(TAG, "transcribeAudio(): ENTER file=${file.name} idx=$recIndex")
                        transcribeAudio(file, recIndex)
                    }
                    job.invokeOnCompletion { e ->
                        val msg = if (e == null) "✅ Transcription job completed"
                        else "⛔ Transcription job failed: ${e.message}"
                        addResultLog(msg, recIndex)
                        Log.d(TAG, "transcribeAudio(): EXIT err=$e")
                    }
                    transcribeJobRef.set(job)

                } else {
                    // ----- START -----
                    Log.d(TAG, "START block entered (isRecording=false)")
                    if (!hasAllRequiredPermissions) {
                        addToastLog("⚠️ Grant microphone permission")
                        return@launch
                    }
                    stopPlayback()

                    val file = withContext(Dispatchers.IO) { createNewAudioFile() }
                    currentFile = file
                    addToastLog("🎙️ Recording started...")
                    recordStartMs = SystemClock.elapsedRealtime()
                    recorder.startRecording(
                        output = file,
                        rates = intArrayOf(16_000, 48_000, 44_100)
                    )
                    isRecording = true
                }
            } catch (ce: CancellationException) {
                Log.w(TAG, "toggleRecord cancelled", ce)
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "toggleRecord failed", e)
                isRecording = false
                addToastLog("⛔ toggleRecord failed: ${e::class.simpleName} ${e.message}")
            }
        }

    // -------------------------------------------------------------------------
    // Transcription
    // -------------------------------------------------------------------------
    private suspend fun transcribeAudio(file: File, index: Int = -1) {
        val ctx = whisperCtx ?: run {
            addResultLog("⛔ Model not loaded", index)
            return
        }

        if (!canTranscribe) {
            addResultLog("ℹ️ Busy flag was true, proceeding anyway", index)
        }

        canTranscribe = false
        try {
            val samples = withContext(Dispatchers.IO) { decodeWaveFile(file) }
            if (samples.isEmpty()) {
                addResultLog("⛔ No audio samples", index)
                return
            }

            val start = System.currentTimeMillis()
            val text = ctx.transcribeData(
                data = samples,
                lang = selectedLanguage,
                translate = translateToEnglish
            ) ?: "(no result)"
            val elapsed = System.currentTimeMillis() - start

            addResultLog(
                buildString {
                    appendLine("✅ Transcribed (${elapsed} ms)")
                    appendLine("Model: $selectedModel")
                    appendLine("Lang: $selectedLanguage${if (translateToEnglish) "→en" else ""}")
                    appendLine()
                    append(text)
                },
                index
            )
            Log.i(TAG, "Transcribed in ${elapsed}ms, samples=${samples.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Transcribe failed", e)
            addResultLog("⛔ Transcribe failed: ${e::class.simpleName}: ${e.message}", index)
        } finally {
            canTranscribe = true
        }
    }

    // -------------------------------------------------------------------------
    // Playback
    // -------------------------------------------------------------------------
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

    private suspend fun startPlayback(f: File) = withContext(Dispatchers.Main) {
        releaseMediaPlayer()
        mediaPlayer = MediaPlayer.create(app, f.absolutePath.toUri()).apply {
            setOnCompletionListener {
                runCatching { stop() }
                runCatching { release() }
                mediaPlayer = null
            }
            start()
        }
    }

    private suspend fun stopPlayback() = withContext(Dispatchers.Main) {
        releaseMediaPlayer()
    }

    // -------------------------------------------------------------------------
    // Record management & persistence
    // -------------------------------------------------------------------------
    fun removeRecordAt(index: Int) {
        if (index !in myRecords.indices) return
        runCatching { File(myRecords[index].absolutePath).delete() }
        myRecords = myRecords.toMutableList().apply { removeAt(index) }
        addToastLog("🗑 Recording #$index deleted")
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

    fun saveRecords() = runCatching {
        File(app.filesDir, "records.json").writeText(
            json.encodeToString(ListSerializer(MyRecordSerializer), myRecords)
        )
    }.onFailure { Log.e(TAG, "Save failed", it) }

    private fun loadRecords() = runCatching {
        val f = File(app.filesDir, "records.json")
        if (f.exists()) {
            myRecords = json.decodeFromString(ListSerializer(MyRecordSerializer), f.readText())
        }
    }.onFailure { Log.e(TAG, "Load failed", it) }

    // -------------------------------------------------------------------------
    // FS / cleanup
    // -------------------------------------------------------------------------
    private suspend fun createNewAudioFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recDir, "rec_$ts.wav")
    }

    private suspend fun setupDirs() = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
        recDir.mkdirs()
    }

    private suspend fun releaseWhisper() = withContext(Dispatchers.IO) {
        runCatching { whisperCtx?.release() }
        whisperCtx = null
    }

    private suspend fun releaseMediaPlayer() = withContext(Dispatchers.Main) {
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
                recorder.close()
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

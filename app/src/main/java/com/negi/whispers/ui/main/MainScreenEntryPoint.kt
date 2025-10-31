/**
 * Main Screen Entry Point and Core UI Logic
 * ----------------------------------------
 * This file defines the composables for the main UI of Whisper App.
 * It includes:
 * - Permission handling for microphone access
 * - Main screen layout and state coordination
 * - Recording list display and user interaction logic
 * - Debounced recording button behavior for safe user input
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package com.negi.whispers.ui.main

import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.negi.whispers.ui.composables.ConfirmDeleteDialog
import com.negi.whispers.ui.composables.RecordingList
import com.negi.whispers.ui.composables.StyledButton
import com.negi.whispers.ui.composables.TopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Entry point composable for the Whisper app's main screen.
 * Handles permission checks and passes state down to [MainScreen].
 *
 * @param viewModel The [MainScreenViewModel] managing core recording logic.
 */
@Composable
fun MainScreenEntryPoint(viewModel: MainScreenViewModel) {
    val context = LocalContext.current

    // Launcher for runtime permissions (microphone access)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.updatePermissionsStatus() }

    // Request missing permissions on launch
    LaunchedEffect(viewModel) {
        val missing = viewModel.getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else viewModel.updatePermissionsStatus()
    }

    var selectedIndex by rememberSaveable { mutableIntStateOf(-1) }

    MainScreen(
        viewModel = viewModel,
        canTranscribe = viewModel.canTranscribe,
        isRecording = viewModel.isRecording,
        selectedIndex = selectedIndex,
        onSelect = { selectedIndex = it },
        onRecordTapped = {
            Log.d("UI", "Record tapped: isRecording=${viewModel.isRecording}")
            selectedIndex = viewModel.myRecords.lastIndex
            viewModel.toggleRecord { selectedIndex = it }
        },
        onCardClick = viewModel::playRecording
    )
}

/**
 * Main screen composable showing the recording list and record button.
 * Handles record start/stop, swipe-to-delete, and dialog confirmation.
 *
 * @param viewModel The [MainScreenViewModel] containing audio recording logic.
 * @param canTranscribe Indicates whether transcription is available.
 * @param isRecording Indicates whether the app is currently recording.
 * @param selectedIndex The currently selected recording index.
 * @param onSelect Callback invoked when a recording is selected.
 * @param onRecordTapped Callback to start or stop recording.
 * @param onCardClick Callback to play back a selected recording.
 */
@Composable
private fun MainScreen(
    viewModel: MainScreenViewModel,
    canTranscribe: Boolean,
    isRecording: Boolean,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onRecordTapped: () -> Unit,
    onCardClick: (String, Int) -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var pendingDeleteIndex by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val uiScope = rememberCoroutineScope()

    // Debounce mechanism for preventing rapid repeated clicks
    var clickLocked by remember { mutableStateOf(false) }
    val lockDurationMs = 400L

    // Controls record button enable state
    val recordButtonEnabled by remember(
        viewModel.hasAllRequiredPermissions,
        canTranscribe,
        isRecording,
        clickLocked
    ) {
        derivedStateOf {
            if (isRecording) return@derivedStateOf true
            if (!viewModel.hasAllRequiredPermissions) return@derivedStateOf false
            canTranscribe && !clickLocked
        }
    }

    Scaffold(topBar = { TopBar(viewModel) }) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Recording list section
            RecordingList(
                viewModel = viewModel,
                records = viewModel.myRecords,
                listState = listState,
                selectedIndex = selectedIndex,
                canTranscribe = canTranscribe,
                onSelect = onSelect,
                onCardClick = onCardClick,
                onDeleteRequest = {
                    pendingDeleteIndex = it
                    showDeleteDialog = true
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            // Record button section
            StyledButton(
                text = if (isRecording) "Stop" else "Record",
                onClick = {
                    if (isRecording) {
                        onRecordTapped()
                    } else if (!clickLocked) {
                        clickLocked = true
                        onRecordTapped()
                        uiScope.launch {
                            delay(lockDurationMs)
                            clickLocked = false
                        }
                    }
                },
                enabled = recordButtonEnabled,
                color = if (isRecording)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // Confirm delete dialog
    if (showDeleteDialog && pendingDeleteIndex != -1) {
        ConfirmDeleteDialog(
            onConfirm = {
                viewModel.removeRecordAt(pendingDeleteIndex)
                showDeleteDialog = false
                pendingDeleteIndex = -1
            },
            onCancel = {
                showDeleteDialog = false
                pendingDeleteIndex = -1
            }
        )
    }
}
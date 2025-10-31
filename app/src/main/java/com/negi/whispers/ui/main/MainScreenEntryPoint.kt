/**
 * Main Screen Entry Point and Core UI Logic
 * ----------------------------------------
 * This file defines the main composables for the Whisper App UI.
 *
 * Features:
 * - Handles runtime microphone permission requests
 * - Coordinates recording, playback, and transcription state
 * - Displays a scrollable list of recordings
 * - Implements debounced record button for safe user input
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
 * Entry point composable for the Whisper app’s main screen.
 * Handles permission checks and passes recording state down to [MainScreen].
 *
 * @param viewModel The [MainScreenViewModel] managing audio and transcription logic.
 */
@Composable
fun MainScreenEntryPoint(viewModel: MainScreenViewModel) {
    val context = LocalContext.current

    // Permission launcher for microphone access
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.updatePermissionsStatus()
    }

    // Check and request missing permissions when first launched
    LaunchedEffect(viewModel) {
        val missing = viewModel.getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Log.i("MainScreen", "Requesting permissions: $missing")
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            viewModel.updatePermissionsStatus()
        }
    }

    var selectedIndex by rememberSaveable { mutableIntStateOf(-1) }

    MainScreen(
        viewModel = viewModel,
        canTranscribe = viewModel.canTranscribe,
        isRecording = viewModel.isRecording,
        selectedIndex = selectedIndex,
        onSelect = { selectedIndex = it },
        onRecordTapped = {
            Log.d("UI", "Record button tapped (isRecording=${viewModel.isRecording})")
            selectedIndex = viewModel.myRecords.lastIndex
            viewModel.toggleRecord { selectedIndex = it }
        },
        onCardClick = viewModel::playRecording
    )
}

/**
 * Displays the main screen layout: TopBar, recording list, and record button.
 * Handles start/stop logic, swipe-to-delete, and delete confirmation.
 *
 * @param viewModel The [MainScreenViewModel] providing recording control.
 * @param canTranscribe Whether transcription is currently available.
 * @param isRecording Whether the app is actively recording.
 * @param selectedIndex Currently selected recording index.
 * @param onSelect Callback when a recording is selected.
 * @param onRecordTapped Callback when the record button is tapped.
 * @param onCardClick Callback when a recording card is double-tapped or played.
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

    // Debounce protection to prevent rapid button taps
    var clickLocked by remember { mutableStateOf(false) }
    val lockDurationMs = 400L

    // Determine when the record button should be enabled
    val recordButtonEnabled by remember(
        viewModel.hasAllRequiredPermissions,
        canTranscribe,
        isRecording,
        clickLocked
    ) {
        derivedStateOf {
            when {
                isRecording -> true
                !viewModel.hasAllRequiredPermissions -> false
                else -> canTranscribe && !clickLocked
            }
        }
    }

    Scaffold(
        topBar = { TopBar(viewModel) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Recordings list
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

            // Record / Stop button with debounce handling
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

    // Delete confirmation dialog
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

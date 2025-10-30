// file: app/src/main/java/com/negi/whispers/ui/main/MainScreenEntryPoint.kt
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.negi.whispers.ui.main

import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// Entry Point
// -----------------------------------------------------------------------------
@Composable
fun MainScreenEntryPoint(viewModel: MainScreenViewModel) {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.updatePermissionsStatus() }

    LaunchedEffect(viewModel) {
        val missing = viewModel.getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
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
            Log.d("UI", "Record button tapped. isRecording=${viewModel.isRecording}")
            selectedIndex = viewModel.myRecords.lastIndex
            viewModel.toggleRecord { selectedIndex = it }
        },
        onCardClick = viewModel::playRecording
    )
}

// -----------------------------------------------------------------------------
// Main Screen
// -----------------------------------------------------------------------------
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

    // Debounce only for "Record" (Stop must always be immediate)
    var clickLocked by remember { mutableStateOf(false) }
    val lockDurationMs = 400L

    // Stop must be enabled even if permissions are missing
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
            RecordingList(
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

// -----------------------------------------------------------------------------
// Top Bar + About Dialog + LanguageLabel
// -----------------------------------------------------------------------------
@Composable
private fun TopBar(viewModel: MainScreenViewModel) {
    var showAboutDialog by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Mic", tint = Color(0xFF2196F3))
                Text(
                    text = "Whisper App",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Color.Black
                )
                LanguageLabel(
                    languageCode = viewModel.selectedLanguage,
                    selectedModel = viewModel.selectedModel
                )
            }
        },
        actions = {
            IconButton(onClick = { showAboutDialog = true }) {
                Icon(Icons.Default.Info, contentDescription = "App Info")
            }
            ConfigButtonWithDialog(viewModel)
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFFFFF176),
            titleContentColor = Color.Black,
            actionIconContentColor = Color.DarkGray
        )
    )

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("このアプリについて") },
            text = {
                Column {
                    Text("Whisper App v0.0.1")
                    Spacer(Modifier.height(8.dp))
                    Text("Whisper.cpp を使用したオフライン音声認識アプリです。")
                    Spacer(Modifier.height(4.dp))
                    Text("対応言語: 日本語 / 英語 / スワヒリ語")
                    Spacer(Modifier.height(8.dp))
                    Text("開発者: Shu Ishizuki (石附 支)")
                }
            },
            confirmButton = { TextButton(onClick = { showAboutDialog = false }) { Text("Close") } }
        )
    }
}

// -----------------------------------------------------------------------------
// Config Dialog
// -----------------------------------------------------------------------------
@Composable
fun ConfigButtonWithDialog(viewModel: MainScreenViewModel) {
    var showDialog by remember { mutableStateOf(false) }

    IconButton(onClick = { showDialog = true }) {
        Icon(Icons.Default.Settings, contentDescription = "Settings")
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("設定") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("言語を選択")
                    val languages = listOf("en" to "English", "ja" to "日本語", "sw" to "Swahili")
                    DropdownSelector(
                        currentValue = viewModel.selectedLanguage,
                        options = languages,
                        onSelect = { viewModel.updateSelectedLanguage(it) }
                    )

                    Text("モデルを選択")
                    val models = listOf(
                        "ggml-tiny-q5_1.bin",
                        "ggml-base-q5_1.bin",
                        "ggml-small-q5_1.bin"
                    )
                    DropdownSelector(
                        currentValue = viewModel.selectedModel,
                        options = models.map { it to it },
                        onSelect = { viewModel.updateSelectedModel(it) }
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = viewModel.translateToEnglish,
                            onCheckedChange = { viewModel.updateTranslate(it) }
                        )
                        Text("英語に翻訳する")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("OK") }
            }
        )
    }
}

// -----------------------------------------------------------------------------
// DropdownSelector
// -----------------------------------------------------------------------------
@Composable
private fun DropdownSelector(
    currentValue: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val onSelectUpdated by rememberUpdatedState(onSelect)
    val selectedLabel = options.firstOrNull { it.first == currentValue }?.second ?: "Select"

    Box(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) { Text(selectedLabel) }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelectUpdated(value)
                    }
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Recording List
// -----------------------------------------------------------------------------
@Composable
private fun RecordingList(
    records: List<MyRecord>,
    listState: LazyListState,
    selectedIndex: Int,
    canTranscribe: Boolean,
    onSelect: (Int) -> Unit,
    onCardClick: (String, Int) -> Unit,
    onDeleteRequest: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(records.size) {
        if (records.isNotEmpty()) listState.animateScrollToItem(records.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        itemsIndexed(
            records,
            key = { index, record ->
                record.absolutePath.takeIf { it.isNotEmpty() }?.hashCode() ?: index
            }
        ) { index, record ->
            val swipeState = rememberSwipeToDismissBoxState()

            LaunchedEffect(swipeState.currentValue) {
                if (swipeState.currentValue == SwipeToDismissBoxValue.StartToEnd) {
                    onDeleteRequest(index)
                    swipeState.snapTo(SwipeToDismissBoxValue.Settled)
                }
            }

            val isSelected = index == selectedIndex
            val animatedCorner by animateDpAsState(
                targetValue = if (isSelected && !canTranscribe) 48.dp else 16.dp,
                animationSpec = tween(400),
                label = "cornerAnim"
            )

            val scale = if (isSelected && !canTranscribe) {
                rememberInfiniteTransition().animateFloat(
                    initialValue = 0.95f,
                    targetValue = 1.05f,
                    animationSpec = infiniteRepeatable(
                        tween(800, easing = FastOutSlowInEasing),
                        RepeatMode.Reverse
                    ),
                    label = "pulse"
                ).value
            } else 1f

            val tapModifier = if (canTranscribe) {
                Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onSelect(index) },
                        onDoubleTap = {
                            onSelect(index)
                            onCardClick(record.absolutePath, index)
                        }
                    )
                }
            } else Modifier

            SwipeToDismissBox(
                state = swipeState,
                modifier = Modifier.fillMaxWidth(),
                enableDismissFromStartToEnd = true,
                enableDismissFromEndToStart = false,
                backgroundContent = {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(start = 20.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text("削除", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(scaleX = scale, scaleY = scale)
                        .then(tapModifier),
                    shape = RoundedCornerShape(animatedCorner),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                            canTranscribe -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Text(record.logs, Modifier.padding(16.dp))
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Confirm Delete Dialog
// -----------------------------------------------------------------------------
@Composable
private fun ConfirmDeleteDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Delete Recording") },
        text = { Text("Are you sure you want to delete this recording?") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

// -----------------------------------------------------------------------------
// Styled Button
// -----------------------------------------------------------------------------
@Composable
fun StyledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(16.dp),
        elevation = ButtonDefaults.buttonElevation(6.dp),
        modifier = modifier
    ) { Text(text) }
}

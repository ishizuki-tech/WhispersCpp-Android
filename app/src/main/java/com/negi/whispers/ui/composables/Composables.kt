/**
 * Whisper UI Composables
 * -----------------------
 * This file defines all composable UI elements used in the Whisper app:
 * - Styled buttons, configuration dialogs, and dropdown selectors
 * - Recording list with animations and swipe-to-delete behavior
 * - Top bar with app info and language/model display
 *
 * Each composable is documented in Kotlin's standard development doc style.
 */

package com.negi.whispers.ui.composables

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.negi.whispers.ui.main.MainScreenViewModel
import com.negi.whispers.ui.main.MyRecord
import kotlinx.coroutines.launch
import java.io.File

/**
 * Renders a rounded, elevated button with primary color by default.
 *
 * @param text Label text for the button.
 * @param onClick Action executed when the button is tapped.
 * @param modifier Optional layout modifier.
 * @param enabled Enables/disables button interactivity.
 * @param color Button background color (default = primary color).
 */
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
    ) {
        Text(text)
    }
}

/**
 * Settings button and dialog that allow users to select language, model, and translation options.
 *
 * @param viewModel Main screen ViewModel managing current configuration state.
 */
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

/**
 * Dropdown menu selector used in the configuration dialog.
 *
 * @param currentValue Currently selected value key.
 * @param options List of key/label pairs.
 * @param onSelect Callback when a new value is chosen.
 */
@Composable
private fun DropdownSelector(
    currentValue: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
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
        ) {
            Text(selectedLabel)
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    }
                )
            }
        }
    }
}

/**
 * Displays a scrollable list of recordings.
 * Includes swipe-to-delete, tap-to-select, and double-tap-to-retranscribe gestures.
 *
 * @param viewModel Reference to the main screen's ViewModel.
 * @param records List of recorded items.
 * @param listState LazyColumn scroll state.
 * @param selectedIndex Currently selected recording index.
 * @param canTranscribe Whether the app is ready to transcribe.
 * @param onSelect Called when a single tap selects a record.
 * @param onCardClick Called when the user double taps (to re-transcribe).
 * @param onDeleteRequest Called when a swipe-to-delete occurs.
 * @param modifier Optional layout modifier.
 */
@Composable
fun RecordingList(
    viewModel: MainScreenViewModel,
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
        try {
            if (records.isNotEmpty()) listState.animateScrollToItem(records.lastIndex)
        } catch (e: Exception) {
            Log.w("UI", "Scroll error: ${e.message}")
        }
    }

    val globalTransition = rememberInfiniteTransition(label = "globalPulse")
    val pulsingScale by globalTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAnim"
    )

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        itemsIndexed(records, key = { i, r -> r.absolutePath.ifBlank { "rec_$i" } }) { index, record ->
            val swipeState = rememberSwipeToDismissBoxState()
            val scope = rememberCoroutineScope()

            // Swipe-to-delete handler
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
            val effectiveScale = if (isSelected && !canTranscribe) pulsingScale else 1f

            val lastTapTime = remember(index) { mutableStateOf(0L) }

            val tapModifier = if (canTranscribe) {
                Modifier.pointerInput(index) {
                    detectTapGestures(
                        onTap = { onSelect(index) },
                        onDoubleTap = {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime.value < 800) return@detectTapGestures
                            lastTapTime.value = now

                            scope.launch {
                                viewModel.stopPlayback()
                                val file = File(record.absolutePath)
                                if (file.exists()) viewModel.reTranscribe(index)
                                else onCardClick(record.absolutePath, index)
                            }
                        }
                    )
                }
            } else Modifier

            SwipeToDismissBox(
                state = swipeState,
                enableDismissFromStartToEnd = true,
                enableDismissFromEndToStart = false,
                backgroundContent = {
                    Box(
                        Modifier.fillMaxSize().padding(start = 20.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text("削除", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .graphicsLayer(scaleX = effectiveScale, scaleY = effectiveScale)
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
                    Text(record.logs, Modifier.padding(16.dp), softWrap = true)
                }
            }
        }
    }
}

/**
 * Confirmation dialog for deleting a recording.
 *
 * @param onConfirm Callback when delete is confirmed.
 * @param onCancel Callback when user cancels.
 */
@Composable
fun ConfirmDeleteDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
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

/**
 * Small text label showing currently selected language and model.
 */
@Composable
private fun LanguageLabel(languageCode: String, selectedModel: String) {
    val code = languageCode.ifBlank { "—" }
    val model = selectedModel.ifBlank { "—" }
    Text(
        "[$code · $model]",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
    )
}

/**
 * Top application bar showing app name, info, and settings buttons.
 *
 * @param viewModel MainScreenViewModel providing current settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(viewModel: MainScreenViewModel) {
    var showAboutDialog by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Mic", tint = Color(0xFF2196F3))
                Text(
                    "Whisper App",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Color.Black
                )
                LanguageLabel(viewModel.selectedLanguage, viewModel.selectedModel)
            }
        },
        actions = {
            IconButton(onClick = { showAboutDialog = true }) {
                Icon(Icons.Default.Info, contentDescription = "Info")
            }
            ConfigButtonWithDialog(viewModel)
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFFFFF176),
            titleContentColor = Color.Black
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
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("Close") }
            }
        )
    }
}

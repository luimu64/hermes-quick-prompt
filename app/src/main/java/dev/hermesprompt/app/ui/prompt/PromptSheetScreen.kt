package dev.hermesprompt.app.ui.prompt

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermesprompt.app.data.RunState
import dev.hermesprompt.app.ui.theme.ScrimColor

/**
 * The full-screen translucent prompt sheet.
 *
 * Layout:
 *   - A [Box] fills the entire screen:
 *       - A semi-transparent scrim covers the top area; tapping it calls [onDismiss].
 *       - A rounded-top Surface is anchored to the bottom, containing the sheet contents.
 *
 * The activity is already configured as translucent (Theme.HermesPrompt.Overlay),
 * so the system UI beneath shows through the scrim.
 */
@Composable
fun PromptSheetScreen(
    viewModel: PromptViewModel,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val isConfigured = uiState.settings.isConfigured
    val runState = uiState.runState
    val isRunning = runState is RunState.Running

    // Dismiss handler — cancels any in-flight run first
    val handleDismiss: () -> Unit = {
        viewModel.cancelRun()
        onDismiss()
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        // Scrim — tap to dismiss
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ScrimColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = handleDismiss,
                )
        )

        // Bottom sheet surface
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}, // consume clicks so they don't reach the scrim
                ),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 20.dp)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                        .align(Alignment.CenterHorizontally),
                )

                Spacer(Modifier.height(12.dp))

                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Hermes",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (isRunning) {
                        IconButton(onClick = handleDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Stop and close")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Open Settings")
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (!isConfigured) {
                    // Not configured — show hint
                    NotConfiguredHint(onOpenSettings = onOpenSettings)
                } else {
                    // Prompt input
                    OutlinedTextField(
                        value = uiState.promptText,
                        onValueChange = viewModel::onPromptChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        label = { Text("Ask Hermes anything…") },
                        minLines = 1,
                        maxLines = 6,
                        enabled = !isRunning,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = { if (!isRunning && uiState.promptText.isNotBlank()) viewModel.sendPrompt() },
                        ),
                        trailingIcon = {
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .padding(end = 4.dp),
                                    strokeWidth = 2.5.dp,
                                )
                            } else {
                                IconButton(
                                    onClick = viewModel::sendPrompt,
                                    enabled = uiState.promptText.isNotBlank(),
                                ) {
                                    Icon(
                                        Icons.Default.Send,
                                        contentDescription = "Send",
                                        tint = if (uiState.promptText.isNotBlank())
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    )
                                }
                            }
                        },
                    )

                    Spacer(Modifier.height(12.dp))

                    // Streamed / result / error text
                    AnimatedVisibility(
                        visible = runState !is RunState.Idle,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        when (runState) {
                            is RunState.Running -> {
                                if (runState.streamedText.isNotBlank()) {
                                    SelectionContainer {
                                        Text(
                                            text = runState.streamedText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                            is RunState.Done -> {
                                SelectionContainer {
                                    Text(
                                        text = runState.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            is RunState.Error -> {
                                Text(
                                    text = runState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            is RunState.Idle -> { /* nothing */ }
                        }
                    }
                }
            }
        }
    }

    // Auto-focus the text field and show IME when the sheet appears
    LaunchedEffect(isConfigured) {
        if (isConfigured) {
            focusRequester.requestFocus()
        }
    }
}

@Composable
private fun NotConfiguredHint(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Hermes is not configured yet.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Open Settings to enter your Hermes server address and API key.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Open Settings")
        }
    }
}

package dev.hermesprompt.app.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.hermesprompt.app.ui.theme.ScrimColor

/**
 * State driving the overlay UI.
 *
 * Held by the overlay service (or by a test harness) and pushed into
 * [OverlayPromptScreen] on each change. The composable itself is stateless:
 * it renders [promptText]/[answerText] and forwards user intent through the
 * callbacks, which is what makes it unit-testable.
 */
data class OverlayUiState(
    val promptText: String = "",
    val answerText: String = "",
    val isRunning: Boolean = false,
    val isConfigured: Boolean = true,
)

/**
 * The overlay UI attached to the overlay service.
 *
 * Layout:
 *   - A [Box] fills the overlay root window.
 *       - A semi-transparent scrim covers the root; tapping it (outside the
 *         card) calls [onDismiss] — the tap-outside dismissal.
 *       - A rounded-top Surface is anchored to the bottom, containing the
 *         prompt input, submit action, answer area and close affordance.
 *
 * Callbacks (the unit-testable surface):
 *   - [onQuestionSubmitted] — fired with the trimmed question when the user
 *     submits (IME Send or the Send button), never while a run is in flight.
 *   - [onAnswerRendered] — fired with the text every time the answer area
 *     renders non-blank text (initial render + each streaming update).
 *   - [onDismiss] — fired on tap-outside, the close affordance, or any other
 *     dismissal; the hosting service is responsible for removing the window
 *     and restoring input focus to the app below.
 *
 * Auto-focus: the input requests focus and shows the IME when summoned. The
 * hosting window must be focusable for the IME to attach — the overlay service
 * owns the FLAG_NOT_FOCUSABLE flag and must clear it while the user types.
 */
@Composable
fun OverlayPromptScreen(
    state: OverlayUiState,
    onPromptChange: (String) -> Unit,
    onQuestionSubmitted: (String) -> Unit,
    onAnswerRendered: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Auto-focus the input and pop the IME when the overlay appears.
    LaunchedEffect(state.isConfigured) {
        if (state.isConfigured) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // Notify whenever the answer area renders non-blank text.
    LaunchedEffect(state.answerText) {
        if (state.answerText.isNotBlank()) {
            onAnswerRendered(state.answerText)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim — the tap-outside dismissal region. Any tap on the root that
        // lands outside the card surface dismisses the overlay.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("overlay_scrim")
                .background(ScrimColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
        )

        // Card surface
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("overlay_card")
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

                // Header row: title + close affordance
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Hermes",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("overlay_close")) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = if (state.isRunning) "Stop and close" else "Close",
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (!state.isConfigured) {
                    NotConfiguredHint(onOpenSettings = onOpenSettings)
                } else {
                    // Prompt input
                    OutlinedTextField(
                        value = state.promptText,
                        onValueChange = onPromptChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("overlay_input")
                            .focusRequester(focusRequester),
                        label = { Text("Ask Hermes anything…") },
                        minLines = 1,
                        maxLines = 6,
                        enabled = !state.isRunning,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (!state.isRunning && state.promptText.isNotBlank()) {
                                    onQuestionSubmitted(state.promptText.trim())
                                }
                            },
                        ),
                        trailingIcon = {
                            if (state.isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .padding(end = 4.dp),
                                    strokeWidth = 2.5.dp,
                                )
                            } else {
                                IconButton(
                                    onClick = { onQuestionSubmitted(state.promptText.trim()) },
                                    enabled = state.promptText.isNotBlank(),
                                    modifier = Modifier.testTag("overlay_send"),
                                ) {
                                    Icon(
                                        Icons.Default.Send,
                                        contentDescription = "Send",
                                        tint = if (state.promptText.isNotBlank())
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    )
                                }
                            }
                        },
                    )

                    Spacer(Modifier.height(12.dp))

                    // Answer area — renders the app's response
                    AnimatedVisibility(
                        visible = state.isRunning || state.answerText.isNotBlank(),
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        if (state.isRunning && state.answerText.isBlank()) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            SelectionContainer {
                                Text(
                                    text = state.answerText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
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
            Text("Open Settings")
        }
    }
}

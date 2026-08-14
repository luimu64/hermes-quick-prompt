package dev.hermesprompt.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.hermesprompt.app.HermesPromptApp
import dev.hermesprompt.app.R
import dev.hermesprompt.app.ui.prompt.PromptSheetScreen
import dev.hermesprompt.app.ui.prompt.PromptViewModel
import dev.hermesprompt.app.ui.settings.SettingsScreen
import dev.hermesprompt.app.ui.settings.SettingsViewModel
import dev.hermesprompt.app.ui.theme.HermesPromptTheme

/**
 * Single-activity host.
 *
 * Intent dispatch logic (called in [onCreate]):
 *   1. If the intent action is ASSIST or VOICE_COMMAND, or EXTRA_ASSIST_CONTEXT is present →
 *      apply the translucent overlay theme and show the [PromptSheetScreen].
 *   2. Otherwise (normal launcher launch) → apply the opaque base theme and show [SettingsScreen].
 *
 * Theme must be swapped via [setTheme] **before** [super.onCreate] so that the
 * window's translucency attribute takes effect before the window is first rendered.
 */
class MainActivity : ComponentActivity() {

    private val isAssistLaunch: Boolean
        get() = intent?.action == Intent.ACTION_ASSIST ||
                intent?.action == "android.intent.action.VOICE_COMMAND" ||
                intent?.hasExtra("android.intent.extra.ASSIST_CONTEXT") == true

    private val container get() = (application as HermesPromptApp).container

    // ViewModels are scoped to this Activity; if the user taps the gear icon
    // from the prompt sheet, we just finish() and re-launch Settings from the
    // normal launcher (singleTask means there's always one instance).
    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(container.settingsStore, container.hermesApi)
    }
    private val promptViewModel: PromptViewModel by viewModels {
        PromptViewModel.Factory(container.settingsStore, container.hermesApi)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Swap theme BEFORE super.onCreate so the window attributes are applied.
        if (isAssistLaunch) {
            setTheme(R.style.Theme_HermesPrompt_Overlay)
        } else {
            setTheme(R.style.Theme_HermesPrompt)
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (isAssistLaunch) {
            setContent {
                HermesPromptTheme {
                    PromptSheetScreen(
                        viewModel = promptViewModel,
                        onDismiss = { finish() },
                        onOpenSettings = { openSettings() },
                    )
                }
            }
        } else {
            setContent {
                HermesPromptTheme {
                    SettingsScreen(viewModel = settingsViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask: if the user long-presses power while the app is already
        // open in Settings mode, re-create to show the prompt sheet.
        recreate()
    }

    private fun openSettings() {
        // Launch a fresh Settings instance via the normal MAIN/LAUNCHER intent.
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }
}

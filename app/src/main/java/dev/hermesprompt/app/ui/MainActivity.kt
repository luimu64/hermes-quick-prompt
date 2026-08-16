package dev.hermesprompt.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.hermesprompt.app.HermesPromptApp
import dev.hermesprompt.app.R
import dev.hermesprompt.app.overlay.OverlayService
import dev.hermesprompt.app.ui.overlay.OverlayPromptHost
import dev.hermesprompt.app.ui.settings.SettingsScreen
import dev.hermesprompt.app.ui.settings.SettingsViewModel
import dev.hermesprompt.app.ui.theme.HermesPromptTheme

/**
 * Single-activity host.
 *
 * Intent dispatch:
 *   1. ASSIST / VOICE_COMMAND / EXTRA_ASSIST_CONTEXT (the summon) → hand off to
 *      the overlay service. The prompt UI is drawn as a WindowManager
 *      application overlay (see [OverlayPromptHost]), never as an activity
 *      window, and this activity finishes immediately — no new task, no
 *      backgrounding of the app underneath.
 *   2. Otherwise (normal launcher launch) → apply the opaque base theme and show [SettingsScreen].
 *
 * Theme must be swapped via [setTheme] **before** [super.onCreate] so that the
 * window's attributes take effect before the window is first rendered. The
 * summon path keeps the translucent theme: the window only exists for the few
 * frames needed to start the overlay service, and a transparent background
 * avoids flashing over the app below.
 */
class MainActivity : ComponentActivity() {

    private val isAssistLaunch: Boolean
        get() = intent?.action == Intent.ACTION_ASSIST ||
                intent?.action == "android.intent.action.VOICE_COMMAND" ||
                intent?.hasExtra("android.intent.extra.ASSIST_CONTEXT") == true

    private val container get() = (application as HermesPromptApp).container

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(container.settingsStore, container.hermesApi)
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
            launchOverlay()
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
        // singleTask: a summon while the app is already open (Settings screen)
        // must route to the overlay instead of re-showing the sheet activity.
        // A MAIN/LAUNCHER intent needs nothing — singleTask already brought the
        // Settings screen forward.
        if (isAssistLaunch) {
            launchOverlay()
        }
    }

    /**
     * The summon path. Never renders prompt UI in this activity: gates the
     * SYSTEM_ALERT_WINDOW special access, hands off to [OverlayPromptHost]
     * (which starts and binds the overlay service and attaches the overlay UI),
     * then finishes immediately so no sheet window or task entry remains and
     * the app underneath is never paused by a new task.
     */
    private fun launchOverlay() {
        if (!OverlayService.canDrawOverlays(this)) {
            OverlayService.openOverlayPermissionSettings(this)
            finish()
            return
        }
        OverlayPromptHost.summon(this)
        finish()
    }
}
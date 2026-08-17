package dev.hermesprompt.app.ui.overlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider
import dev.hermesprompt.app.data.RunState
import dev.hermesprompt.app.overlay.OverlayContent
import dev.hermesprompt.app.overlay.OverlayService
import dev.hermesprompt.app.ui.MainActivity
import dev.hermesprompt.app.ui.prompt.PromptViewModel
import dev.hermesprompt.app.ui.theme.HermesPromptTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wiring between the ASSIST summon trigger and [OverlayService].
 *
 * The summon activity (MainActivity ASSIST branch) never renders the sheet — it
 * hands off here, which:
 *   1. checks the SYSTEM_ALERT_WINDOW special access (primary gate),
 *   2. starts the overlay foreground service (must happen while the summoning
 *      activity is still foreground),
 *   3. binds to the service and attaches the Compose [OverlayPromptScreen] UI
 *      plus this object as the [OverlayContent] adapter,
 *   4. the caller then finishes its activity immediately — no sheet window, no
 *      new task, the app below never pauses.
 *
 * This object is the single app-level owner of the overlay session. The
 * [PromptViewModel] for the run lives in the overlay service's
 * [androidx.lifecycle.ViewModelStore] (the service is the session-scoped
 * [androidx.lifecycle.ViewModelStoreOwner] for the Compose tree): the run must
 * outlive the summon activity's `finish()`, so it cannot be scoped to the
 * activity — and it is cleared when the overlay is dismissed, mirroring the
 * old sheet-scoped ViewModel that died the moment the activity closed.
 *
 * Dismissal (tap-outside scrim, close affordance, or the "Open Settings" hint)
 * cancels the in-flight run, asks the service to remove the overlay window, and
 * drops the binding so the service is destroyed.
 */
object OverlayPromptHost : OverlayContent {

    /**
     * Host-scoped coroutine scope. Lives for the whole process: it only drives
     * view-model state into the overlay UI, and every collect job is cancelled
     * when a session ends, so it leaks nothing.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var service: OverlayService? = null
    private var connection: ServiceConnection? = null
    private var bound = false
    private var viewModel: PromptViewModel? = null
    private var viewModelJob: Job? = null

    /**
     * Answer text pushed through [OverlayContent.setAnswer] while no run is in
     * flight. The run's own SSE stream is the authoritative answer source; this
     * only covers pushes from non-bound callers (service intent helpers).
     */
    private var externalAnswer: String? = null

    private val _uiState = mutableStateOf(OverlayUiState())
    val uiState: State<OverlayUiState> = _uiState

    /**
     * Summon entry point. MUST be called while the app process is foreground
     * (from the ASSIST activity's `onCreate`/`onNewIntent`) because it starts
     * a foreground service — the system rejects background starts on API 31+.
     *
     * @param context the summoning activity context (used for the permission
     *   settings launch and the foreground-service start).
     * @param initialText optional pre-filled question text.
     */
    fun summon(context: Context, initialText: String = "") {
        val app = context.applicationContext
        appContext = app

        if (!OverlayService.canDrawOverlays(app)) {
            // Primary permission gate. SYSTEM_ALERT_WINDOW is special access —
            // it cannot be requested via requestPermissions(); the user must
            // grant "Display over other apps" in Settings. The service re-checks
            // as defense in depth before addView().
            OverlayService.openOverlayPermissionSettings(context)
            return
        }

        // Drop any previous session (cancel in-flight run, remove window, unbind)
        // before starting fresh — repeated summons must not stack overlays.
        reset()

        // Start the foreground service that owns the window. This must happen
        // while the caller is still foreground, i.e. before the activity's
        // finish() (the caller does that immediately after this returns).
        OverlayService.show(app, initialText)

        // Bind with the application context so the connection survives the
        // summon activity's finish(); onServiceConnected attaches the UI.
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = (binder as OverlayService.LocalBinder).getService()
                service = svc
                beginSession(svc)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // Service process died or was killed while bound; the window is
                // gone with it. Clear the handle; a fresh summon rebinds.
                service = null
            }
        }
        connection = conn
        bound = app.bindService(
            Intent(app, OverlayService::class.java),
            conn,
            Context.BIND_AUTO_CREATE,
        )
    }

    /** Dismisses the overlay: cancels the run, removes the window, stops the service. */
    fun dismiss() = reset()

    // --- OverlayContent (pushed by the service into this adapter) ---

    override fun setInitialText(text: String) {
        if (text.isNotBlank()) {
            viewModel?.onPromptChange(text)
        }
    }

    override fun setAnswer(text: String) {
        externalAnswer = text
        val vm = viewModel ?: return
        // Reflect immediately when no run is in flight; while Running, the run's
        // own streamed text is authoritative and this shows on the next Idle push.
        if (vm.uiState.value.runState !is RunState.Running) {
            _uiState.value = _uiState.value.copy(answerText = text)
        }
    }

    // --- UI callbacks ---

    fun onPromptChange(text: String) {
        viewModel?.onPromptChange(text)
    }

    fun onQuestionSubmitted(question: String) {
        val vm = viewModel ?: return
        externalAnswer = null
        vm.onPromptChange(question)
        vm.sendPrompt()
    }

    /**
     * "Open Settings" affordance (shown when the app is not configured yet).
     * Dismisses the overlay so it does not hover above Settings, then launches
     * the launcher entry point — the intentional in-app direct launch whose
     * behavior is unchanged by the overlay migration.
     */
    fun openSettings() {
        val app = appContext ?: return
        reset()
        val intent = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        app.startActivity(intent)
    }

    // --- session internals ---

    private fun beginSession(svc: OverlayService) {
        val app = appContext ?: return

        // Fresh ViewModel per summon and per overlay session — mirrors the old
        // per-sheet scope where each summon started from an empty prompt with
        // an Idle run state. The store is owned by the service and cleared on
        // dismiss/destroy, so the run scope dies with the overlay.
        val vm = ViewModelProvider(
            svc,
            PromptViewModel.Factory(svc.appContainer.settingsStore, svc.appContainer.hermesApi),
        )[PromptViewModel::class.java]
        viewModel = vm
        externalAnswer = null
        _uiState.value = OverlayUiState()

        viewModelJob?.cancel()
        viewModelJob = scope.launch {
            vm.uiState.collect { s ->
                val runState = s.runState
                val answerText = when (runState) {
                    is RunState.Running -> runState.streamedText
                    is RunState.Done -> runState.text
                    is RunState.Error -> runState.message
                    // A run that finished stays in Done until the next summon;
                    // externalAnswer only applies while truly idle.
                    is RunState.Idle -> externalAnswer.orEmpty()
                }
                _uiState.value = OverlayUiState(
                    promptText = s.promptText,
                    answerText = answerText,
                    isRunning = runState is RunState.Running,
                    isConfigured = s.settings.isConfigured,
                    model = s.settings.model,
                )
            }
        }

        val contentView = ComposeView(app).apply {
            setContent {
                HermesPromptTheme {
                    OverlayPromptContent()
                }
            }
        }
        svc.setOverlayContent(contentView, this@OverlayPromptHost)
    }

    private fun reset() {
        val vm = viewModel
        viewModel = null
        if (vm != null) {
            // Cancel the in-flight run (client coroutine + fire-and-forget
            // server-side stop) before tearing the session down.
            vm.cancelRun()
        }
        viewModelJob?.cancel()
        viewModelJob = null
        // The service clears its own viewModelStore in dismissOverlay(); nothing
        // to clear here.
        externalAnswer = null
        _uiState.value = OverlayUiState()

        val svc = service
        service = null
        if (svc != null) {
            // Remove the window, stop the foreground state, and request stop.
            // While we are still bound the service stays alive; the unbind
            // below releases it so it is destroyed.
            runCatching { svc.dismissOverlay() }
        }
        val app = appContext
        val conn = connection
        connection = null
        if (app != null && conn != null && bound) {
            runCatching { app.unbindService(conn) }
        }
        bound = false
    }
}

/**
 * Renders the overlay UI driven by [OverlayPromptHost]'s state. Stateless
 * wrapper: all behavior lives in the host (the service-facing adapter).
 */
@Composable
private fun OverlayPromptContent() {
    OverlayPromptScreen(
        state = OverlayPromptHost.uiState.value,
        onPromptChange = OverlayPromptHost::onPromptChange,
        onQuestionSubmitted = OverlayPromptHost::onQuestionSubmitted,
        onAnswerRendered = {}, // production hook — the state already renders answers
        onDismiss = OverlayPromptHost::dismiss,
        onOpenSettings = OverlayPromptHost::openSettings,
    )
}
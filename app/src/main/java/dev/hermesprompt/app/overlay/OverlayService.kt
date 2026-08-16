package dev.hermesprompt.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.hermesprompt.app.AppContainer
import dev.hermesprompt.app.HermesPromptApp
import dev.hermesprompt.app.R
import dev.hermesprompt.app.ui.MainActivity

/**
 * Foreground service that hosts the summon overlay as an application overlay
 * window ([WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]) instead of
 * launching a full-screen activity in a new task.
 *
 * Why an overlay: the ASSIST summon used to start [MainActivity] as an
 * activity window in a brand-new task, which backgrounds + pauses whatever
 * app was in the foreground. An overlay window is composited above the
 * current display content — it never creates a task and never pauses the app
 * underneath (see the root-cause report for t_4227421f).
 *
 * Window behaviour:
 *  - Full-screen, transparent window so the app below stays visible.
 *  - Starts `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` so the app below keeps
 *    input focus and stays resumed. Any touch on the overlay flips the window
 *    focusable so the soft keyboard can attach when the user taps the input
 *    field; `setInputFocusable` is also exposed for explicit control.
 *  - The overlay UI decides what a touch means (tap-off scrim → dismiss); the
 *    service only hosts the window.
 *
 * Permission: TYPE_APPLICATION_OVERLAY requires SYSTEM_ALERT_WINDOW, which is
 * special-access only — it cannot be requested via `requestPermissions()`.
 * The service checks [Settings.canDrawOverlays] before adding the window and
 * routes the user to Settings when it is missing.
 *
 * Public API: [showOverlay], [updateAnswer], [dismissOverlay] (plus
 * [setOverlayContent] to attach the UI). Intent-based equivalents live in the
 * companion so any app component can drive the overlay without binding.
 *
 * DI follows the app's manual-DI pattern: the container is reachable via
 * [appContainer] (see HermesPromptApp/AppContainer) for whoever needs the
 * API client / settings store to build the overlay UI's ViewModel.
 */
class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    companion object {
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 1

        const val ACTION_SHOW = "dev.hermesprompt.app.overlay.SHOW"
        const val ACTION_UPDATE_ANSWER = "dev.hermesprompt.app.overlay.UPDATE_ANSWER"
        const val ACTION_DISMISS = "dev.hermesprompt.app.overlay.DISMISS"

        const val EXTRA_INITIAL_TEXT = "dev.hermesprompt.app.overlay.INITIAL_TEXT"
        const val EXTRA_ANSWER_TEXT = "dev.hermesprompt.app.overlay.ANSWER_TEXT"

        /** True when this app holds the SYSTEM_ALERT_WINDOW special access. */
        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** Opens the "Display over other apps" settings screen for this app. */
        fun openOverlayPermissionSettings(context: Context) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        /**
         * Shows the overlay with [initialText]. Must be called while the app is
         * in the foreground (e.g. from the ASSIST activity), because this starts
         * a foreground service — the system rejects background starts on API 31+.
         */
        fun show(context: Context, initialText: String) {
            context.startForegroundService(
                Intent(context, OverlayService::class.java).apply {
                    action = ACTION_SHOW
                    putExtra(EXTRA_INITIAL_TEXT, initialText)
                }
            )
        }

        /**
         * Pushes streamed answer text into the overlay. Best-effort: a no-op
         * when the service is not running (nothing to update) or when a
         * background start is blocked.
         */
        fun updateAnswer(context: Context, text: String) {
            runCatching {
                context.startService(
                    Intent(context, OverlayService::class.java).apply {
                        action = ACTION_UPDATE_ANSWER
                        putExtra(EXTRA_ANSWER_TEXT, text)
                    }
                )
            }
        }

        /** Dismisses the overlay and stops the service. Best-effort, see [updateAnswer]. */
        fun dismiss(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, OverlayService::class.java).apply {
                        action = ACTION_DISMISS
                    }
                )
            }
        }
    }

    private val binder = LocalBinder()

    /** App-level DI container, following the project's manual-DI pattern. */
    val appContainer: AppContainer
        get() = (application as HermesPromptApp).container

    /** UI content attached via [setOverlayContent]; null until then. */
    var overlayContent: OverlayContent? = null
        private set

    private var windowManager: WindowManager? = null
    private var overlayRoot: FrameLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var foregroundStarted = false

    private var pendingInitialText: String? = null
    private var pendingAnswer: String? = null

    /**
     * Lifecycle for the Compose UI hosted in the overlay window. The overlay is
     * a bare WindowManager window owned by this service — not an activity — so
     * Compose's recomposer cannot find a [LifecycleOwner] in the view tree by
     * itself; we provide one and drive it with the window's visibility.
     *
     * The registry starts at [Lifecycle.State.INITIALIZED] (not CREATED) on
     * purpose: [SavedStateRegistryController.performRestore] requires the
     * owner's lifecycle to be exactly INITIALIZED — the same "initialization
     * stage" an Activity is in when its controller is wired up. The controller
     * is restored in [onCreate], and the window path moves the registry up to
     * RESUMED when the overlay is on screen.
     *
     * The registry is deliberately mutable: `dismissOverlay()` moves it to
     * [Lifecycle.State.DESTROYED], but the service instance can outlive the
     * window (it stays bound until the wiring layer unbinds), and a re-summon
     * may reuse the same instance — the registry is re-armed to CREATED before
     * the window comes back up.
     */
    private var lifecycleRegistry: LifecycleRegistry =
        LifecycleRegistry(this).apply { currentState = Lifecycle.State.INITIALIZED }

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    /**
     * Compose (and any future `viewModel()`-using content) also needs a
     * [SavedStateRegistryOwner] and a [ViewModelStoreOwner] on the view tree —
     * the same service provides both. The store doubles as the natural home of
     * the run ViewModel for the session (see OverlayPromptHost): it is cleared
     * when the window goes away, so the run scope dies with the overlay.
     *
     * The controller is wired in [onCreate] while the lifecycle is INITIALIZED
     * (the same "initialization stage" guard an Activity satisfies), which
     * marks the registry restored so Compose's saveable machinery can consume
     * restored state. There is never any actual saved state — the overlay does
     * not survive process death — so this is purely the required init dance.
     */
    private val savedStateRegistryController: SavedStateRegistryController =
        SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    inner class LocalBinder : Binder() {
        fun getService(): OverlayService = this@OverlayService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        // Wire the SavedStateRegistryController while the lifecycle is still
        // INITIALIZED — the same "initialization stage" an Activity's controller
        // is attached in. This sets isRestored=true on the registry (Compose's
        // saveable machinery calls consumeRestoredStateForKey and requires it),
        // registering the Recreator observer against this service's lifecycle.
        // There is no saved state to restore (the overlay never survives
        // process death), so the call is just the required init dance.
        savedStateRegistryController.performRestore(null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                // startForegroundService() requires startForeground() promptly,
                // otherwise the system ANRs the service.
                startForegroundIfNeeded()
                showOverlay(intent.getStringExtra(EXTRA_INITIAL_TEXT).orEmpty())
            }
            ACTION_UPDATE_ANSWER -> {
                if (overlayRoot == null && overlayContent == null) {
                    // Nothing on screen — ignore a stale update (e.g. sent
                    // after dismiss) instead of idling a pointless service.
                    stopSelf()
                } else {
                    updateAnswer(intent.getStringExtra(EXTRA_ANSWER_TEXT).orEmpty())
                }
            }
            ACTION_DISMISS -> dismissOverlay()
        }
        // Never restart after process death — the overlay should not reappear
        // unexpectedly on the user's screen.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        viewModelStore.clear()
        overlayRoot?.let { root -> runCatching { windowManager?.removeView(root) } }
        overlayRoot = null
        overlayParams = null
    }

    /**
     * Attaches the overlay UI (a [View] plus its [OverlayContent] adapter).
     * The wiring layer calls this after binding to the service. Any initial
     * text / answer pushed before attach is applied immediately.
     */
    fun setOverlayContent(view: View, content: OverlayContent) {
        val root = ensureWindow() ?: return
        // The overlay window is not an activity window, so Compose cannot find
        // a lifecycle/saved-state/view-model owner on its own — provide this
        // service as all three for the attached UI tree (required before the
        // view is attached, because composition starts during
        // dispatchAttachedToWindow).
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        root.setViewTreeViewModelStoreOwner(this)
        root.removeAllViews()
        root.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )
        overlayContent = content
        pendingInitialText?.let { content.setInitialText(it) }
        pendingAnswer?.let { content.setAnswer(it) }
        pendingInitialText = null
        pendingAnswer = null
    }

    /** Shows (or re-shows) the overlay window with the given initial question text. */
    fun showOverlay(initialText: String) {
        if (overlayRoot == null) addOverlayWindow()
        val content = overlayContent
        if (content != null) {
            content.setInitialText(initialText)
        } else {
            pendingInitialText = initialText
        }
    }

    /** Pushes streamed answer text into the overlay UI (no-op while no UI is attached). */
    fun updateAnswer(text: String) {
        val content = overlayContent
        if (content != null) {
            content.setAnswer(text)
        } else {
            pendingAnswer = text
        }
    }

    /** Removes the overlay window, stops the foreground state, and stops the service. */
    fun dismissOverlay() {
        // Tear down the Compose UI lifecycle before removing the window. If the
        // instance is reused for a later summon, addOverlayWindow re-arms it.
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        // Kill the session-scoped run ViewModel (cancels its scope) with the
        // overlay; the next summon builds a fresh one.
        viewModelStore.clear()
        // Re-add NOT_FOCUSABLE before removal so a re-shown window starts
        // non-focusable (per the root-cause report's flag guidance).
        setInputFocusable(false)
        overlayRoot?.let { root -> runCatching { windowManager?.removeView(root) } }
        overlayRoot = null
        overlayParams = null
        overlayContent = null
        pendingInitialText = null
        pendingAnswer = null
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    /**
     * Flips the overlay window between non-focusable (default, so the app below
     * keeps input focus) and focusable (so the soft keyboard can attach when
     * the user is typing). Touches on the overlay call this automatically; the
     * wiring layer may also call it explicitly from the UI's focus callbacks.
     */
    fun setInputFocusable(focusable: Boolean) {
        val root = overlayRoot ?: return
        val params = overlayParams ?: return
        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { windowManager?.updateViewLayout(root, params) }
    }

    private fun ensureWindow(): FrameLayout? {
        overlayRoot?.let { return it }
        addOverlayWindow()
        return overlayRoot
    }

    private fun addOverlayWindow() {
        if (!canDrawOverlays(this)) {
            // Special access missing (revoked, or a fresh install) — route the
            // user to Settings instead of crashing on addView().
            openOverlayPermissionSettings(this)
            dismissOverlay()
            return
        }

        // A previous session may have torn the lifecycle down to DESTROYED on
        // this (still-bound) instance; re-arm before the window comes back up.
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            lifecycleRegistry = LifecycleRegistry(this).apply {
                currentState = Lifecycle.State.CREATED
            }
        }

        val wm = windowManager ?: return
        val root = FrameLayout(this).apply {
            // Any touch on the overlay means the user is interacting with it:
            // make the window focusable so tapping the input field opens the
            // IME. The UI's own scrim/close handlers decide whether to dismiss.
            setOnTouchListener { _, _ ->
                setInputFocusable(true)
                false // don't consume — let children/UI handle the touch
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )

        wm.addView(root, params)
        overlayRoot = root
        overlayParams = params
        // The window is now on screen: give the attached Compose UI an active
        // lifecycle so its LaunchedEffects/state collection run.
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    private fun startForegroundIfNeeded() {
        if (foregroundStarted) return
        foregroundStarted = true
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_overlay_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .setContentIntent(notificationContentIntent())
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun notificationContentIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.overlay_notification_channel_description)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}

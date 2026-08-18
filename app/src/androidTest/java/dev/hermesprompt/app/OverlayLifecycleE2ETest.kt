package dev.hermesprompt.app

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.hermesprompt.app.data.AppSettings
import dev.hermesprompt.app.data.SettingsStore
import dev.hermesprompt.app.ui.overlay.OverlayPromptHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.FileInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end lifecycle tests for the summon overlay on a real device.
 *
 * These tests drive the REAL system, not a harness:
 *   - a foreground "other app" (LineageOS Jelly browser) is launched first,
 *   - the summon is dispatched as an actual ASSIST intent to MainActivity,
 *   - the overlay is the real TYPE_APPLICATION_OVERLAY window owned by
 *     OverlayService, asserted via dumpsys (window present / topResumedActivity,
 *     no Hermes ActivityRecord, no Hermes task, service stopped after dismiss),
 *   - the ask/answer path runs against a tiny in-process mock Hermes SSE
 *     server on 127.0.0.1, with settings injected through the real
 *     SettingsStore (DataStore), so the production PromptViewModel ->
 *     HermesApi -> overlay UI pipeline is exercised end to end.
 *
 * Unlike OverlayPromptScreenTest this class deliberately avoids
 * ComposeTestRule and kotlinx-coroutines-test: no runTest, no ServiceLoader,
 * so it is unaffected by the ChuckerTeam/chucker#1348 coroutines bug that
 * causes sibling UI tests to SKIP on this class of device.
 *
 * Ordering note: tests are ordered (@FixMethodOrder) so each leaves the
 * device in a known state for the next; teardown force-stops nothing (the
 * instrumentation shares the app process) but dismisses any live overlay.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OverlayLifecycleE2ETest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    private val appPackage = "dev.hermesprompt.app"
    private val previousApp = "org.lineageos.jelly"
    private val previousAppActivity = "org.lineageos.jelly/.MainActivity"
    private lateinit var originalSettings: AppSettings

    // ── mock Hermes server ────────────────────────────────────────────────────

    private class MockHermesServer(private val port: Int) {
        private val server = ServerSocket()
        private val executor = Executors.newFixedThreadPool(2)
        val receivedPrompt = AtomicReference<String?>(null)
        @Volatile var isRunning = false

        fun start() {
            isRunning = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
            executor.submit {
                while (isRunning) {
                    try {
                        val socket = server.accept()
                        executor.submit { handle(socket) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }

        fun stop() {
            isRunning = false
            runCatching { server.close() }
            executor.shutdownNow()
        }

        private fun handle(socket: Socket) {
            try {
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return
                    val parts = requestLine.split(" ")
                    val method = parts[0]
                    val path = parts[1]
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) {
                            contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                    }
                    val body = if (contentLength > 0) {
                        CharArray(contentLength).also { reader.read(it) }.concatToString()
                    } else ""

                    val out = s.getOutputStream()
                    when {
                        method == "POST" && (path == "/v1/runs" || path == "/v1/chat/completions") -> {
                            receivedPrompt.set(body)
                            if (path == "/v1/runs") {
                                val resp = "{\"run_id\":\"e2e-run\",\"status\":\"queued\"}"
                                write(
                                    out,
                                    "HTTP/1.1 202 Accepted\r\n" +
                                        "Content-Type: application/json\r\n" +
                                        "Content-Length: ${resp.toByteArray().size}\r\n" +
                                        "Connection: close\r\n\r\n" +
                                        resp
                                )
                            } else {
                                // OpenAI chat completions streaming
                                write(out, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
                                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n".toByteArray())
                                out.flush()
                                Thread.sleep(120)
                                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"from e2e mock\"}}]}\n\n".toByteArray())
                                out.flush()
                                Thread.sleep(120)
                                out.write("data: [DONE]\n\n".toByteArray())
                                out.flush()
                            }
                        }
                        method == "GET" && path == "/v1/runs/e2e-run/events" -> {
                            write(out, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
                            // Two deltas + run.completed, as the real server streams.
                            out.write("data: {\"event\":\"message.delta\",\"delta\":\"Hello \"}\n\n".toByteArray())
                            out.flush()
                            Thread.sleep(120)
                            out.write("data: {\"event\":\"message.delta\",\"delta\":\"from e2e mock\"}\n\n".toByteArray())
                            out.flush()
                            Thread.sleep(120)
                            out.write("data: {\"event\":\"run.completed\",\"output\":\"Hello from e2e mock\"}\n\n".toByteArray())
                            out.flush()
                            Thread.sleep(120)
                            out.write("data: {\"event\":\"done\"}\n\n".toByteArray())
                            out.flush()
                        }
                        method == "GET" && path == "/v1/health" -> {
                            write(out, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 15\r\nConnection: close\r\n\r\n{\"status\":\"ok\"}")
                        }
                        else -> {
                            write(out, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        }
                    }
                }
            } catch (_: Exception) {
                // client disconnected mid-stream — normal when the app stops reading
            }
        }

        private fun write(out: java.io.OutputStream, text: String) {
            runCatching { out.write(text.toByteArray()); out.flush() }
        }
    }

    // ── shell helpers (UiAutomation runs as SHELL -> DUMP/input allowed) ──────

    private fun shell(cmd: String): String {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(cmd) as ParcelFileDescriptor
        pfd.use {
            val text = FileInputStream(it.fileDescriptor).readBytes().decodeToString()
            return text
        }
    }

    private fun shellLines(cmd: String): List<String> =
        shell(cmd).lineSequence().filter { it.isNotBlank() }.toList()

    private fun topResumedActivity(): String? =
        shellLines("dumpsys activity activities")
            .firstOrNull { it.contains("topResumedActivity") }
            ?.substringAfter("=")

    private fun overlayWindowCount(): Int =
        shellLines("dumpsys window windows").count { it.contains(appPackage) }

    private fun hermesActivityRecordCount(): Int =
        shellLines("dumpsys activity activities").count { it.contains("ActivityRecord") && it.contains(appPackage) }

    /**
     * Active tasks containing the app. The overlay must never create one: it is
     * a bare WindowManager window, and the summon activity finishes immediately.
     * The platform's ASSIST dispatch may leave a *hidden* recents entry
     * (mHiddenTasks) that is not user-visible and is reaped with the process —
     * that is NOT a task in the user-visible sense and is ignored here.
     */
    private fun hermesActiveTaskCount(): Int =
        shellLines("dumpsys activity tasks").count { it.contains(appPackage) }

    /** User-visible Recents CARD entries for the app (one line per task). */
    private fun hermesRecentsTaskCount(): Int =
        shellLines("dumpsys activity recents")
            .count { it.contains("Recent #") && it.contains(appPackage) }

    /** Removes any lingering Hermes recent task so counts start from zero. */
    private fun clearHermesRecents() {
        val ids = shellLines("dumpsys activity recents")
            .filter { it.contains("Recent #") && it.contains(appPackage) }
            .mapNotNull { Regex("Task\\{[0-9a-f]+ #(\\d+)").find(it)?.groupValues?.get(1) }
        ids.forEach { shell("am task remove $it") }
        Thread.sleep(500)
    }

    private fun hermesServiceActive(): Boolean {
        val dump = shell("dumpsys activity services $appPackage")
        val record = dump.substringAfter("ServiceRecord", "")
        // The record lingers while the instrumentation owns the process; the
        // real signal is the destroying flag (destroy initiated) or absence.
        return dump.contains("OverlayService") && !record.contains("destroying=true")
    }

    private fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(200)
        }
        return false
    }

    private fun dump(name: String) {
        val log = listOf(
            "=== $name ===",
            "top: ${topResumedActivity()}",
            "overlay windows: ${overlayWindowCount()}",
            "hermes ActivityRecords: ${hermesActivityRecordCount()}",
            "hermes active tasks: ${hermesActiveTaskCount()}",
            "hermes recents task entries: ${hermesRecentsTaskCount()}",
            "service active: ${hermesServiceActive()}",
        ).joinToString("\n")
        android.util.Log.e("OVERLAY_E2E", log)
    }

    private fun screenshot(name: String) {
        // screencap via shell — reliable on API 34+ where UiAutomation
        // takeScreenshot needs display-scoped capture and shell identity.
        shell("mkdir -p /data/local/tmp/e2e")
        shell("screencap -p /data/local/tmp/e2e/$name.png")
    }

    // ── summon / dismiss ──────────────────────────────────────────────────────

    private fun summon() {
        // Real ASSIST dispatch to MainActivity's summon branch. Equivalent to
        // KEYCODE_ASSIST when the app is the default assistant role holder.
        shell("am start -a android.intent.action.ASSIST -n $appPackage/.ui.MainActivity")
    }

    private fun dismissViaHost() {
        instrumentation.runOnMainSync { OverlayPromptHost.dismiss() }
    }

    private fun expectOverlayUp() {
        assertTrue(
            "overlay window should appear within 12s",
            waitFor(12_000, "overlay") { overlayWindowCount() > 0 }
        )
    }

    private fun expectOverlayGone() {
        assertTrue(
            "overlay window should be removed within 12s",
            waitFor(12_000, "overlay-gone") { overlayWindowCount() == 0 }
        )
    }

    @Before
    fun setUp() {
        device.wakeUp()
        device.pressHome()
        Thread.sleep(500)
        // Ensure no live overlay from a previous run.
        if (overlayWindowCount() > 0) dismissViaHost()
        expectOverlayGone()
        // Clear any lingering Hermes recent-task entries so task assertions
        // start from a clean baseline (leftovers from earlier runs pollute
        // dumpsys activity recents).
        clearHermesRecents()
        // SYSTEM_ALERT_WINDOW is special access — a reinstall or test-apk swap
        // resets the appop. Grant it through the shell (which may), so the
        // suite is self-sufficient instead of failing deep in the summon path.
        shell("cmd appops set $appPackage SYSTEM_ALERT_WINDOW allow")
        assertTrue(
            "SYSTEM_ALERT_WINDOW must be granted — summon path exits to Settings otherwise",
            shell("cmd appops get $appPackage SYSTEM_ALERT_WINDOW").contains("allow")
        )
        // Configure the app through the REAL store: the overlay input is only
        // shown when the app isConfigured; the mock server URL is harmless for
        // tests that never type a question.
        runBlocking {
            val store = (targetContext.applicationContext as HermesPromptApp).container.settingsStore
            originalSettings = store.settingsFlow.first()
            store.save(AppSettings(serverUrl = "http://127.0.0.1:1", apiKey = "test-key", profile = ""))
        }
    }

    @After
    fun tearDown() {
        if (overlayWindowCount() > 0) dismissViaHost()
        expectOverlayGone()
        device.pressHome()
        // Leave the device app configured as it was — never pointing at a
        // throwaway mock URL from this suite.
        if (::originalSettings.isInitialized) {
            runBlocking {
                (targetContext.applicationContext as HermesPromptApp).container.settingsStore
                    .save(originalSettings)
            }
        }
    }

    private fun clickableAncestorOrSelf(node: androidx.test.uiautomator.UiObject2): androidx.test.uiautomator.UiObject2 {
        var current = node
        while (!current.isClickable && current.parent != null) {
            current = current.parent
        }
        return current
    }

    /**
     * Latest root of the overlay window's OWN accessibility tree.
     * Re-enumerates windows each call so results are never stale.
     */
    private fun overlayA11yRoot(): android.view.accessibility.AccessibilityNodeInfo? {
        return try {
            for (w in instrumentation.uiAutomation.windows) {
                val root = w.root ?: continue
                if (root.packageName?.toString() == appPackage) return root
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun findInOverlay(
        timeoutMs: Long,
        predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = overlayA11yRoot()
            if (root != null) {
                val found = findInTree(root, predicate)
                if (found != null) return found
            }
            Thread.sleep(200)
        }
        return null
    }

    private fun findInTree(
        root: android.view.accessibility.AccessibilityNodeInfo,
        predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findInTree(child, predicate)
            if (found != null) return found
        }
        return null
    }

    private fun clickableAncestorA11y(n: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        var current: android.view.accessibility.AccessibilityNodeInfo? = n
        while (current != null && !current.isClickable) {
            current = current.parent
        }
        return current
    }

    private fun setTextOnNode(n: android.view.accessibility.AccessibilityNodeInfo, text: String): Boolean {
        val args = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun clickNode(n: android.view.accessibility.AccessibilityNodeInfo): Boolean =
        n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)

    private fun dumpOverlayA11y(name: String) {
        // Best-effort a11y tree text dump of the OVERLAY window for diagnosis.
        runCatching {
            val root = overlayA11yRoot()
            if (root != null) {
                val texts = mutableListOf<String>()
                fun walk(n: android.view.accessibility.AccessibilityNodeInfo) {
                    val t = n.text?.toString()
                    val d = n.contentDescription?.toString()
                    if (!t.isNullOrBlank()) texts += "text=$t"
                    if (!d.isNullOrBlank()) texts += "desc=$d"
                    for (i in 0 until n.childCount) walk(n.getChild(i))
                }
                walk(root)
                android.util.Log.e("OVERLAY_E2E", "a11y[$name]: ${texts.joinToString(" | ")}")
            } else {
                android.util.Log.e("OVERLAY_E2E", "a11y[$name]: NO overlay window root found")
            }
        }
    }

    // ── (a) other app stays RESUMED, not destroyed ─────────────────────────────

    @Test
    fun a_foregroundAppSurvivesSummon() {
        shell("am start -n $previousAppActivity")
        assertTrue(
            "previous app should become topResumed",
            waitFor(15_000, "jelly-top") { topResumedActivity()?.contains("org.lineageos.jelly") == true }
        )
        val before = topResumedActivity()
        dump("a-before-summon")

        summon()
        expectOverlayUp()
        // Give the summon activity time to finish and any transient record to reap.
        Thread.sleep(2000)

        val after = topResumedActivity()
        dump("a-after-summon")

        // The previous app's Activity is still resumed — same activity, not
        // destroyed (the record would disappear + topResumed would change).
        assertNotNull("previous app lost topResumedActivity", after)
        assertEquals("the app below must stay topResumed", before, after)
        assertEquals(
            "no Hermes ActivityRecord may exist (summon activity finished)",
            0, hermesActivityRecordCount()
        )
        screenshot("a-foreground-app-survives")
    }

    // ── (b) show + dismiss without creating a task ─────────────────────────────

    @Test
    fun b_showDismissWithoutNewTask() {
        summon()
        expectOverlayUp()
        assertTrue(
            "service should be running while overlay is up",
            waitFor(10_000, "service-up") { hermesServiceActive() }
        )
        Thread.sleep(1500)
        assertEquals(
            "no active Hermes task while overlay shown",
            0, hermesActiveTaskCount()
        )
        assertEquals(
            "no ActivityRecord while overlay shown (summon activity finished)",
            0, hermesActivityRecordCount()
        )
        dump("b-shown")

        // Dismiss through the real UI: the scrim's tap-outside is test (d);
        // here dismiss via close affordance (content-desc "Close" on the icon,
        // clickable ancestor = the IconButton).
        val closeIcon = findInOverlay(5_000) { n ->
            n.contentDescription?.toString() == "Close"
        }
        assertNotNull("overlay close affordance should exist", closeIcon)
        val closeButton = clickableAncestorA11y(closeIcon!!)
        assertNotNull("close affordance should have a clickable ancestor", closeButton)
        assertTrue("close click should be accepted", clickNode(closeButton!!))
        expectOverlayGone()

        assertTrue(
            "service should stop after dismiss",
            waitFor(10_000, "service-gone") { !hermesServiceActive() }
        )
        assertEquals(
            "no active Hermes task after dismiss",
            0, hermesActiveTaskCount()
        )
        assertEquals(
            "no Hermes recents task entry after dismiss",
            0, hermesRecentsTaskCount()
        )
        dump("b-dismissed")
        screenshot("b-dismissed")

        // Re-show: the overlay can be shown again after dismissal (no stacking).
        summon()
        expectOverlayUp()
        Thread.sleep(1000)
        assertEquals("re-shown overlay still no task", 0, hermesActiveTaskCount())
        assertEquals("re-shown overlay still no ActivityRecord", 0, hermesActivityRecordCount())
        screenshot("b-reshown")
        dismissViaHost()
        expectOverlayGone()
    }

    // ── (c) type a question, receive an answer inside the overlay ──────────────

    @Test
    fun c_questionAnswerInOverlay() {
        val port = ServerSocket(0).use { it.localPort }
        val mock = MockHermesServer(port)
        mock.start()
        try {
            val container = (targetContext.applicationContext as HermesPromptApp).container
            val originalSettings = runBlocking { container.settingsStore.settingsFlow.first() }
            runBlocking {
                container.settingsStore.save(
                    originalSettings.copy(
                        serverUrl = "http://127.0.0.1:$port",
                        apiKey = "test-key",
                        profile = "",
                    )
                )
            }

            summon()
            expectOverlayUp()

            // Find the overlay's OWN input node. UiDevice is useless here: it
            // walks only the *active* window, and the overlay is deliberately
            // NOT_FOCUSABLE. Enumerate the overlay window's a11y tree instead.
            val input = findInOverlay(12_000) { n ->
                n.className?.toString() == "android.widget.EditText"
            }
            if (input == null) dumpOverlayA11y("c-no-input")
            assertNotNull("overlay should expose the prompt input", input)
            assertTrue(
                "typing into the overlay input should be accepted",
                setTextOnNode(input!!, "What is 2+2?")
            )
            Thread.sleep(300)

            // The content-desc "Send" sits on the icon; the clickable node is
            // its IconButton ancestor. Click THAT (a11y ACTION_CLICK works on
            // the overlay window regardless of focus).
            val sendIcon = findInOverlay(5_000) { n ->
                n.contentDescription?.toString() == "Send"
            }
            if (sendIcon == null) dumpOverlayA11y("c-no-send")
            assertNotNull("overlay should expose a send affordance", sendIcon)
            val sendButton = clickableAncestorA11y(sendIcon!!)
            assertNotNull("send affordance should have a clickable ancestor", sendButton)
            assertTrue("send click should be accepted", clickNode(sendButton!!))

            // Wait for the streamed answer to render inside the overlay.
            val answer = findInOverlay(20_000) { n ->
                n.text?.toString()?.contains("from e2e mock") == true
            }
            if (answer == null) {
                dumpOverlayA11y("c-answer-missing")
                screenshot("c-answer-missing")
                android.util.Log.e(
                    "OVERLAY_E2E",
                    "mock received prompt: ${mock.receivedPrompt.get()}"
                )
            }
            assertNotNull("answer text should render inside the overlay", answer)
            assertTrue(
                "answer node should carry the mock response",
                answer!!.text.toString().contains("Hello from e2e mock")
            )
            assertTrue(
                "mock server should have received the typed question",
                mock.receivedPrompt.get()?.contains("What is 2+2?") == true
            )
            screenshot("c-answer-rendered")
            dump("c-answer")

            // Restore whatever settings existed before this test.
            runBlocking { container.settingsStore.save(originalSettings) }
        } finally {
            mock.stop()
        }
    }

    // ── (d) tap outside dismisses and returns to the previous app ──────────────

    @Test
    fun d_tapOutsideDismissesAndReturns() {
        shell("am start -n $previousAppActivity")
        assertTrue(
            "previous app should become topResumed",
            waitFor(15_000, "jelly-top") { topResumedActivity()?.contains("org.lineageos.jelly") == true }
        )

        summon()
        expectOverlayUp()
        Thread.sleep(1000)

        // Tap in the scrim region (top-center, well outside the bottom card).
        val w = device.displayWidth
        val h = device.displayHeight
        device.click(w / 2, h / 4)

        expectOverlayGone()
        assertTrue(
            "previous app must return to topResumed",
            waitFor(10_000, "jelly-return") { topResumedActivity()?.contains("org.lineageos.jelly") == true }
        )
        assertEquals("no Hermes ActivityRecord after tap-outside", 0, hermesActivityRecordCount())
        dump("d-after-tap-outside")
        screenshot("d-tap-outside-dismissed")
    }
}
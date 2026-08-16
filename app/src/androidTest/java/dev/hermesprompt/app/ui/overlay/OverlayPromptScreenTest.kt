package dev.hermesprompt.app.ui.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hermesprompt.app.ui.theme.HermesPromptTheme
import kotlinx.coroutines.CoroutineExceptionHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.util.ServiceLoader

/**
 * Skips Compose UI tests when the upstream kotlinx-coroutines ServiceLoader bug
 * (ChuckerTeam/chucker#1348) is present on the device.
 *
 * coroutines-test registers its exception collector (`ExceptionCollectorAsService`)
 * in the TEST APK's `META-INF/services`. On Android instrumentation the
 * `CoroutineExceptionHandler` interface resolves parent-first to the APP
 * classloader, so `ServiceLoader.load` only ever sees `AndroidExceptionPreHandler`
 * and `runTest()` throws "Exception handler was not found via a ServiceLoader"
 * before any test body runs.
 *
 * This rule is applied OUTSIDE the compose rule so it can detect the condition
 * and skip BEFORE the compose harness starts `runTest`. On environments where
 * the collector is visible (healthy classloader), the wrapped tests run normally.
 */
class SkipWhenCoroutinesServiceLoaderBroken : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val handlerClass = CoroutineExceptionHandler::class.java
                val handlers = ServiceLoader.load(handlerClass, handlerClass.classLoader).toList()
                val seesTestCollector = handlers.any { it.javaClass.simpleName == "ExceptionCollectorAsService" }
                if (!seesTestCollector) {
                    throw AssumptionViolatedException(
                        "Upstream kotlinx-coroutines ServiceLoader bug (ChuckerTeam/chucker#1348): " +
                            "test APK's ExceptionCollectorAsService is invisible to the app classloader, " +
                            "so Compose's runTest cannot start. Skipping on-device UI test."
                    )
                }
                base.evaluate()
            }
        }
    }
}

/**
 * Smoke test for [OverlayPromptScreen]'s unit-testable callback surface.
 *
 * Renders the composable in a plain test activity (NOT the overlay window — that
 * is covered by the lifecycle tests) and asserts that the three public callbacks
 * fire with the expected payloads:
 *   - [OverlayPromptScreen] submit → onQuestionSubmitted(trimmed text)
 *   - answer state → onAnswerRendered(text)
 *   - tap on scrim (outside card) → onDismiss()
 *   - close affordance → onDismiss()
 *
 * On devices hit by the upstream coroutines ServiceLoader bug the whole class
 * skips (see [SkipWhenCoroutinesServiceLoaderBroken]); it is not a UI defect.
 */
@RunWith(AndroidJUnit4::class)
class OverlayPromptScreenTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(SkipWhenCoroutinesServiceLoaderBroken())
        .around(composeRule)

    /** Minimal state holder mimicking what the overlay service will maintain. */
    private inner class FakeHost {
        var state by mutableStateOf(OverlayUiState())
        var submitted: String? = null
        var rendered: String? = null
        var dismissCount = 0

        fun render() {
            composeRule.setContent {
                HermesPromptTheme {
                    OverlayPromptScreen(
                        state = state,
                        onPromptChange = { state = state.copy(promptText = it) },
                        onQuestionSubmitted = { submitted = it },
                        onAnswerRendered = { rendered = it },
                        onDismiss = { dismissCount++ },
                    )
                }
            }
        }
    }

    @Test
    fun submitFiresCallbackWithTrimmedText() {
        val host = FakeHost()
        host.render()

        composeRule.onNodeWithTag("overlay_input").performTextInput("  hello world  ")
        composeRule.onNodeWithTag("overlay_send").performClick()
        composeRule.waitForIdle()

        assertEquals("hello world", host.submitted)
    }

    @Test
    fun imeSendFiresCallbackWithTrimmedText() {
        val host = FakeHost()
        host.render()

        composeRule.onNodeWithTag("overlay_input").performTextInput("via ime")
        composeRule.onNodeWithTag("overlay_input").performImeAction()
        composeRule.waitForIdle()

        assertEquals("via ime", host.submitted)
    }

    @Test
    fun answerStateFiresRenderedCallback() {
        val host = FakeHost()
        host.render()

        // Push an answer into the state — the answer area renders it and
        // onAnswerRendered fires with the text.
        host.state = host.state.copy(answerText = "The answer is 42")
        composeRule.waitForIdle()

        assertEquals("The answer is 42", host.rendered)
    }

    @Test
    fun tapOutsideCardDismisses() {
        val host = FakeHost()
        host.render()

        // Tap on the scrim region (outside the card surface).
        composeRule.onNodeWithTag("overlay_scrim").performClick()
        composeRule.waitForIdle()

        assertTrue("onDismiss should fire on tap-outside", host.dismissCount > 0)
    }

    @Test
    fun closeAffordanceDismisses() {
        val host = FakeHost()
        host.render()

        composeRule.onNodeWithTag("overlay_close").performClick()
        composeRule.waitForIdle()

        assertTrue("onDismiss should fire on close", host.dismissCount > 0)
    }

    @Test
    fun submitUnavailableWhileRunning() {
        val host = FakeHost()
        host.state = host.state.copy(isRunning = true)
        host.render()

        // While a run is in flight the Send button is replaced by the progress
        // indicator, so there is no submit affordance at all.
        composeRule.onNodeWithTag("overlay_send").assertDoesNotExist()
        composeRule.waitForIdle()

        assertEquals(null, host.submitted)
    }
}

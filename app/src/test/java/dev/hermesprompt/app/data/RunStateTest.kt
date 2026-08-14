package dev.hermesprompt.app.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [RunState] sealed class and [appendDelta] extension.
 *
 * These tests run on the JVM (no Android dependencies).
 */
class RunStateTest {

    // ── State identity ────────────────────────────────────────────────────────

    @Test
    fun `Idle is a singleton object`() {
        assertSame(RunState.Idle, RunState.Idle)
    }

    @Test
    fun `Running holds run ID and text`() {
        val state = RunState.Running(runId = "run_abc", streamedText = "Hello")
        assertEquals("run_abc", state.runId)
        assertEquals("Hello", state.streamedText)
    }

    @Test
    fun `Running defaults to empty streamed text`() {
        val state = RunState.Running(runId = "run_xyz")
        assertEquals("", state.streamedText)
    }

    @Test
    fun `Done holds final text`() {
        val state = RunState.Done(text = "The answer is 42.")
        assertEquals("The answer is 42.", state.text)
    }

    @Test
    fun `Error holds error message`() {
        val state = RunState.Error(message = "Cannot reach server")
        assertEquals("Cannot reach server", state.message)
    }

    // ── appendDelta ───────────────────────────────────────────────────────────

    @Test
    fun `appendDelta concatenates text correctly`() {
        val initial = RunState.Running(runId = "run_1", streamedText = "Hello")
        val next = initial.appendDelta(", world!")
        assertEquals("Hello, world!", next.streamedText)
        assertEquals("run_1", next.runId)
    }

    @Test
    fun `appendDelta on empty streamed text produces the delta as the text`() {
        val initial = RunState.Running(runId = "run_2", streamedText = "")
        val next = initial.appendDelta("First chunk")
        assertEquals("First chunk", next.streamedText)
    }

    @Test
    fun `appendDelta does not mutate the original state`() {
        val original = RunState.Running(runId = "run_3", streamedText = "Before")
        original.appendDelta(" After")
        // Original must be unchanged (data class copy semantics)
        assertEquals("Before", original.streamedText)
    }

    @Test
    fun `multiple appendDelta calls accumulate correctly`() {
        var state = RunState.Running(runId = "run_4", streamedText = "")
        state = state.appendDelta("chunk1")
        state = state.appendDelta(" chunk2")
        state = state.appendDelta(" chunk3")
        assertEquals("chunk1 chunk2 chunk3", state.streamedText)
    }

    // ── Type checks (exhaustive sealed class) ─────────────────────────────────

    @Test
    fun `RunState types are exhaustive via when expression`() {
        val states: List<RunState> = listOf(
            RunState.Idle,
            RunState.Running("id"),
            RunState.Done("text"),
            RunState.Error("err"),
        )
        val labels = states.map { state ->
            when (state) {
                is RunState.Idle -> "idle"
                is RunState.Running -> "running"
                is RunState.Done -> "done"
                is RunState.Error -> "error"
            }
        }
        assertEquals(listOf("idle", "running", "done", "error"), labels)
    }
}

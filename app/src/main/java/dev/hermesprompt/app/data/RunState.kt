package dev.hermesprompt.app.data

/**
 * Sealed class representing the lifecycle states of a Hermes agent run.
 *
 * State machine transitions:
 *   Idle -> Running  (user sends a prompt, POST /v1/runs succeeds)
 *   Running -> Running  (message.delta events accumulate text)
 *   Running -> Done  (run.completed or assistant.completed event received)
 *   Running -> Error  (error event or network failure)
 *   Running -> Idle  (user cancels mid-run)
 *   Done -> Running  (user sends another prompt)
 *   Error -> Running  (user retries)
 */
sealed class RunState {
    /** No active run. Input field is editable, Send is enabled (if text is non-blank). */
    data object Idle : RunState()

    /**
     * A run is in flight. [runId] is the server-assigned run identifier used to
     * stream events and to cancel the run on dismissal. [streamedText] accumulates
     * message.delta chunks for live display while the run is still streaming.
     */
    data class Running(val runId: String, val streamedText: String = "") : RunState()

    /** The run finished successfully. [text] is the final output from run.completed. */
    data class Done(val text: String) : RunState()

    /** The run terminated with an error. [message] is user-visible. */
    data class Error(val message: String) : RunState()
}

/**
 * Returns a new [RunState.Running] with [delta] appended to the existing streamed text,
 * or throws [IllegalStateException] if this is not a Running state.
 */
fun RunState.Running.appendDelta(delta: String): RunState.Running =
    copy(streamedText = streamedText + delta)

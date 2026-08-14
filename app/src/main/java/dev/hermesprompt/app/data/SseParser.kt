package dev.hermesprompt.app.data

/**
 * Pure SSE (Server-Sent Events) frame parser.
 *
 * The Hermes event stream uses the standard SSE format:
 *   - Each frame is `data: {json}\n\n`
 *   - Comment lines start with `:` (keepalives, stream-closed markers) — ignored
 *   - Empty lines act as frame delimiters
 *
 * This object is stateless and unit-testable with no Android dependencies.
 */
object SseParser {

    /**
     * A single parsed SSE frame. Only `data:` field lines are collected;
     * `event:`, `id:`, and `retry:` fields are not used by the Hermes protocol.
     */
    data class SseFrame(val data: String)

    /**
     * Parses a sequence of raw SSE lines into a list of data frames.
     *
     * Lines are expected without trailing `\n`. An empty line signals the end
     * of a frame. Comment lines (starting with `:`) and blank data values are
     * silently dropped.
     *
     * @param lines Raw text lines from the SSE response body.
     * @return List of [SseFrame] objects, one per complete SSE event.
     */
    fun parseLines(lines: Sequence<String>): List<SseFrame> {
        val frames = mutableListOf<SseFrame>()
        val dataBuffer = StringBuilder()

        for (line in lines) {
            when {
                line.startsWith(":") -> {
                    // SSE comment — keepalive or stream-closed marker, ignore
                }
                line.startsWith("data:") -> {
                    val payload = line.removePrefix("data:").trimStart()
                    dataBuffer.append(payload)
                }
                line.isBlank() && dataBuffer.isNotEmpty() -> {
                    // End of frame
                    val data = dataBuffer.toString().trim()
                    if (data.isNotEmpty()) {
                        frames.add(SseFrame(data))
                    }
                    dataBuffer.clear()
                }
                else -> {
                    // Other SSE fields (event:, id:, retry:) — not used
                }
            }
        }

        // Flush any remaining data that didn't end with a blank line
        val remaining = dataBuffer.toString().trim()
        if (remaining.isNotEmpty()) {
            frames.add(SseFrame(remaining))
        }

        return frames
    }

    /**
     * Convenience overload that accepts a [List] of lines.
     */
    fun parseLines(lines: List<String>): List<SseFrame> = parseLines(lines.asSequence())
}

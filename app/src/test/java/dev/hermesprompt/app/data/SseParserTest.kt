package dev.hermesprompt.app.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [SseParser] — pure SSE frame parsing.
 *
 * These tests run on the JVM (no Android dependencies).
 */
class SseParserTest {

    // ── Basic frame parsing ───────────────────────────────────────────────────

    @Test
    fun `single data frame is parsed correctly`() {
        val lines = listOf(
            """data: {"event":"message.delta","delta":"Hello"}""",
            "",
        )
        val frames = SseParser.parseLines(lines)
        assertEquals(1, frames.size)
        assertEquals("""{"event":"message.delta","delta":"Hello"}""", frames[0].data)
    }

    @Test
    fun `multiple frames are all parsed`() {
        val lines = listOf(
            """data: {"event":"message.delta","delta":"Hello"}""",
            "",
            """data: {"event":"run.completed","output":"Hello World"}""",
            "",
        )
        val frames = SseParser.parseLines(lines)
        assertEquals(2, frames.size)
    }

    @Test
    fun `comment lines are ignored`() {
        val lines = listOf(
            ": keepalive",
            """data: {"event":"message.delta","delta":"Hi"}""",
            "",
            ": stream closed",
        )
        val frames = SseParser.parseLines(lines)
        assertEquals(1, frames.size)
        assertTrue(frames[0].data.contains("message.delta"))
    }

    @Test
    fun `stream closed comment produces no frame`() {
        val lines = listOf(": stream closed")
        val frames = SseParser.parseLines(lines)
        assertEquals(0, frames.size)
    }

    @Test
    fun `empty lines between frames do not produce empty frames`() {
        val lines = listOf(
            "",
            "",
            """data: {"event":"done"}""",
            "",
        )
        val frames = SseParser.parseLines(lines)
        assertEquals(1, frames.size)
    }

    @Test
    fun `data prefix with space is trimmed correctly`() {
        val lines = listOf(
            "data: {\"event\":\"done\"}",
            "",
        )
        val frames = SseParser.parseLines(lines)
        assertEquals(1, frames.size)
        assertEquals("{\"event\":\"done\"}", frames[0].data)
    }

    @Test
    fun `data prefix without space is handled`() {
        val lines = listOf(
            "data:{\"event\":\"done\"}",
            "",
        )
        val frames = SseParser.parseLines(lines)
        assertEquals(1, frames.size)
    }

    @Test
    fun `sequence overload produces same results as list overload`() {
        val lines = listOf(
            """data: {"event":"done"}""",
            "",
        )
        val fromList = SseParser.parseLines(lines)
        val fromSeq = SseParser.parseLines(lines.asSequence())
        assertEquals(fromList.size, fromSeq.size)
        assertEquals(fromList[0].data, fromSeq[0].data)
    }

    @Test
    fun `remaining data without trailing blank line is flushed`() {
        // Some streams don't end with a blank line
        val lines = listOf(
            """data: {"event":"run.completed","output":"done"}""",
            // no trailing blank
        )
        val frames = SseParser.parseLines(lines)
        assertEquals(1, frames.size)
    }

    @Test
    fun `real hermes message delta frame is parsed`() {
        val json = """{"event":"message.delta","run_id":"run_abc123","timestamp":1000,"delta":" world"}"""
        val lines = listOf("data: $json", "")
        val frames = SseParser.parseLines(lines)
        assertEquals(1, frames.size)
        assertTrue(frames[0].data.contains("message.delta"))
        assertTrue(frames[0].data.contains("world"))
    }

    @Test
    fun `real hermes run completed frame is parsed`() {
        val json = """{"event":"run.completed","run_id":"run_abc123","output":"Hello World","usage":{}}"""
        val lines = listOf("data: $json", "")
        val frames = SseParser.parseLines(lines)
        assertEquals(1, frames.size)
        assertTrue(frames[0].data.contains("run.completed"))
    }
}

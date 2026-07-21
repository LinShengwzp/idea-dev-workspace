package com.anmi.devworkspace.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalOutputMarkerParserTest {
    private val executionId = "execution-2"
    private val parser = TerminalOutputMarkerParser(executionId)

    @Test
    fun `recognizes markers split across output chunks`() {
        assertTrue(parser.accept("noise __DEV_TASK_BEG").isEmpty())
        assertEquals(listOf(MarkerEvent.Began), parser.accept("IN__:execution-2\r\n"))

        assertTrue(parser.accept("__DEV_TASK_EXIT__:exec").isEmpty())
        assertEquals(listOf(MarkerEvent.Exited(17)), parser.accept("ution-2:17\r\n"))
    }

    @Test
    fun `ignores markers for an old execution`() {
        val events = parser.accept(
            "__DEV_TASK_BEGIN__:execution-1\n__DEV_TASK_EXIT__:execution-1:0\n",
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun `recognizes begin and successful exit markers`() {
        val events = parser.accept(
            "__DEV_TASK_BEGIN__:execution-2\n__DEV_TASK_EXIT__:execution-2:0\n",
        )

        assertEquals(listOf(MarkerEvent.Began, MarkerEvent.Exited(0)), events)
    }

    @Test
    fun `ignores negative and malformed exit codes`() {
        val events = parser.accept(
            "__DEV_TASK_EXIT__:execution-2:-1\n" +
                "__DEV_TASK_EXIT__:execution-2:not-a-number\n",
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun `ignores ordinary terminal output`() {
        assertTrue(parser.accept("server ready on port 8080\r\n").isEmpty())
    }

    @Test
    fun `ignores exit marker observed before begin marker`() {
        assertTrue(parser.accept("__DEV_TASK_EXIT__:execution-2:9\n").isEmpty())
        assertEquals(listOf(MarkerEvent.Began), parser.accept("__DEV_TASK_BEGIN__:execution-2\n"))
        assertEquals(listOf(MarkerEvent.Exited(0)), parser.accept("__DEV_TASK_EXIT__:execution-2:0\n"))
    }
}

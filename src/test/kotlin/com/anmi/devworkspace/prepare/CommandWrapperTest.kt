package com.anmi.devworkspace.prepare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommandWrapperTest {
    @Test
    fun `PowerShell wrapper captures LASTEXITCODE and null as zero`() {
        val wrapped = CommandWrapper.wrap(ShellType.POWERSHELL, "Invoke-Task", "exec-1")

        assertMarkers(wrapped, "exec-1")
        assertTrue("Invoke-Task\n${'$'}__devTaskExitCode" in wrapped.text)
        assertTrue("${'$'}LASTEXITCODE" in wrapped.text)
        assertTrue("${'$'}null" in wrapped.text)
    }

    @Test
    fun `CMD wrapper captures ERRORLEVEL on the next line`() {
        val wrapped = CommandWrapper.wrap(ShellType.CMD, "run-task", "exec-2")

        assertMarkers(wrapped, "exec-2")
        assertTrue("run-task\r\nset \"__DEV_TASK_EXIT_CODE=%ERRORLEVEL%\"" in wrapped.text)
        assertTrue("__DEV_TASK_EXIT__:exec-2:%__DEV_TASK_EXIT_CODE%" in wrapped.text)
    }

    @Test
    fun `POSIX family captures status immediately`() {
        listOf(ShellType.BASH, ShellType.ZSH, ShellType.POSIX_SH).forEach { shell ->
            val wrapped = CommandWrapper.wrap(shell, "run-task", "exec-3")

            assertMarkers(wrapped, "exec-3")
            assertTrue("run-task\n__dev_task_exit_code=${'$'}?" in wrapped.text)
            assertTrue("${'$'}__dev_task_exit_code" in wrapped.text)
        }
    }

    @Test
    fun `direct execution cannot be marker wrapped`() {
        assertFailsWith<IllegalArgumentException> {
            CommandWrapper.wrap(ShellType.DIRECT, "run-task", "exec-4")
        }
    }

    private fun assertMarkers(wrapped: WrappedCommand, executionId: String) {
        assertEquals("__DEV_TASK_BEGIN__:$executionId", wrapped.beginMarker)
        assertEquals("__DEV_TASK_EXIT__:$executionId:", wrapped.exitMarkerPrefix)
        assertTrue(wrapped.beginMarker in wrapped.text)
        assertTrue(wrapped.exitMarkerPrefix in wrapped.text)
    }
}

package com.anmi.devworkspace.runtime

import com.anmi.devworkspace.domain.RunTrigger
import com.anmi.devworkspace.domain.TaskExecution
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskStatus
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskExecutionRegistryTest {
    private val registry = InMemoryTaskExecutionRegistry()

    @Test
    fun `rejects a duplicate active execution for the same task`() = runBlocking {
        assertTrue(registry.begin(execution("first")).isSuccess)

        val duplicate = registry.begin(execution("second"))

        assertTrue(duplicate.isFailure)
        assertEquals("first", registry.active("backend")?.executionId)
    }

    @Test
    fun `rejects an illegal state transition`() = runBlocking {
        registry.begin(execution("first"))

        val result = registry.transition(
            taskId = "backend",
            expectedExecutionId = "first",
            status = TaskStatus.SUCCEEDED,
        )

        assertTrue(result.isFailure)
        assertEquals(TaskStatus.PREPARING, registry.active("backend")?.status)
    }

    @Test
    fun `rejects a transition for a stale execution id`() = runBlocking {
        registry.begin(execution("current"))

        val result = registry.transition(
            taskId = "backend",
            expectedExecutionId = "old",
            status = TaskStatus.WAITING_FOR_TERMINAL,
        )

        assertTrue(result.isFailure)
        assertEquals("current", registry.active("backend")?.executionId)
    }

    @Test
    fun `terminal state releases the active task slot`() = runBlocking {
        registry.begin(execution("first"))
        registry.transition("backend", "first", TaskStatus.WAITING_FOR_TERMINAL)
        registry.transition("backend", "first", TaskStatus.RUNNING)

        val completed = registry.transition(
            taskId = "backend",
            expectedExecutionId = "first",
            status = TaskStatus.SUCCEEDED,
            exitCode = 0,
        )

        assertTrue(completed.isSuccess)
        assertNull(registry.active("backend"))
        assertEquals(TaskStatus.SUCCEEDED, registry.executions.value.getValue("backend").status)
        assertEquals(0, registry.executions.value.getValue("backend").exitCode)
    }

    private fun execution(executionId: String) = TaskExecution(
        taskId = "backend",
        executionId = executionId,
        trigger = RunTrigger.MANUAL,
        status = TaskStatus.PREPARING,
        startedAt = Instant.parse("2026-07-21T00:00:00Z"),
        sourceScope = TaskScope.PROJECT_SHARED,
    )
}

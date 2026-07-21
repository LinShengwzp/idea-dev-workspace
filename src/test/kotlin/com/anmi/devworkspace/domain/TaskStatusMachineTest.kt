package com.anmi.devworkspace.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskStatusMachineTest {
    @Test
    fun `running can complete or begin stopping`() {
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.SUCCEEDED))
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.FAILED))
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.STOPPING))
    }

    @Test
    fun `terminal states cannot transition directly`() {
        assertFalse(TaskStatus.SUCCEEDED.canTransitionTo(TaskStatus.RUNNING))
        assertFalse(TaskStatus.FAILED.canTransitionTo(TaskStatus.STOPPING))
        assertFalse(TaskStatus.STOPPED.canTransitionTo(TaskStatus.RUNNING))
    }
}

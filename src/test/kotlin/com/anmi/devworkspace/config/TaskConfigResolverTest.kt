package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskSource
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskConfigResolverTest {
    private fun task(id: String, scope: TaskScope, command: String) = DevTask(
        id = id,
        scope = scope,
        source = TaskSource.InlineCommand(command),
    )

    @Test
    fun `private fully replaces shared and global`() {
        val resolved = TaskConfigResolver.resolve(
            global = listOf(task("server", TaskScope.GLOBAL, "global")),
            shared = listOf(task("server", TaskScope.PROJECT_SHARED, "shared")),
            privateTasks = listOf(task("server", TaskScope.PROJECT_PRIVATE, "private")),
        ).single()

        assertEquals("private", (resolved.effective.source as TaskSource.InlineCommand).command)
        assertEquals(
            listOf(TaskScope.PROJECT_SHARED, TaskScope.GLOBAL),
            resolved.shadowed.map { it.scope },
        )
    }

    @Test
    fun `removing private reveals shared`() {
        val resolved = TaskConfigResolver.resolve(
            global = listOf(task("server", TaskScope.GLOBAL, "global")),
            shared = listOf(task("server", TaskScope.PROJECT_SHARED, "shared")),
            privateTasks = emptyList(),
        ).single()

        assertEquals(TaskScope.PROJECT_SHARED, resolved.effective.scope)
    }
}

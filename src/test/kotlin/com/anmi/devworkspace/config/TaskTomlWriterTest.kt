package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TaskTomlWriterTest {
    @Test
    fun `writer output round trips through parser`() {
        val task = DevTask(
            id = "front-end",
            name = "Frontend \"Dev\"",
            scope = TaskScope.PROJECT_SHARED,
            source = TaskSource.InlineCommand("npm run dev"),
            workingDirectory = "${'$'}{PROJECT_DIR}/frontend",
        )

        val content = TaskTomlWriter().write(listOf(task))
        val parsed = TaskTomlParser().parse(
            content,
            TaskScope.PROJECT_SHARED,
            Path.of("tasks.toml"),
        )

        val success = assertIs<TaskConfigLoadResult.Success>(parsed)
        assertEquals(task, success.snapshot.tasks.single())
    }
}

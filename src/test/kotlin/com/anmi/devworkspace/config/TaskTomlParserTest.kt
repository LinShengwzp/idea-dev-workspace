package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.EnvironmentValue
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TaskTomlParserTest {
    private val parser = TaskTomlParser()

    @Test
    fun `parses script and environment references`() {
        val result = parser.parse(
            content = """
                version = 1

                [[tasks]]
                id = "backend"
                name = "Backend"
                enabled = true
                sourceType = "script"
                script = "${'$'}{PROJECT_DIR}/scripts/start.ps1"
                arguments = ["--profile", "dev"]
                terminalPolicy = "reuse-task"
                exitDetection = true

                [tasks.environment]
                MODE = "dev"
                JAVA_HOME = "${'$'}{ENV:JAVA_HOME}"
                PASSWORD = "${'$'}{SECRET:db-password}"
            """.trimIndent(),
            scope = TaskScope.PROJECT_SHARED,
            source = Path.of("tasks.toml"),
        )

        val success = assertIs<TaskConfigLoadResult.Success>(result)
        val task = success.snapshot.tasks.single()
        assertEquals("backend", task.id)
        assertIs<TaskSource.ScriptFile>(task.source)
        assertIs<EnvironmentValue.SystemReference>(task.environment.getValue("JAVA_HOME"))
        assertIs<EnvironmentValue.SecretReference>(task.environment.getValue("PASSWORD"))
    }

    @Test
    fun `returns positioned syntax error`() {
        val result = parser.parse(
            "version = 1\n[[tasks]]\nid = \"broken",
            TaskScope.GLOBAL,
            Path.of("tasks.toml"),
        )
        val failure = assertIs<TaskConfigLoadResult.Failure>(result)
        assertTrue(failure.errors.first().line >= 1)
        assertTrue(failure.errors.first().column >= 1)
    }

    @Test
    fun `rejects duplicate task ids within one file`() {
        val result = parser.parse(
            """
                version = 1
                [[tasks]]
                id = "same"
                sourceType = "inline"
                command = "echo one"
                [[tasks]]
                id = "same"
                sourceType = "inline"
                command = "echo two"
            """.trimIndent(),
            TaskScope.GLOBAL,
            Path.of("tasks.toml"),
        )
        assertIs<TaskConfigLoadResult.Failure>(result)
    }

    @Test
    fun `configuration without tasks is valid and empty`() {
        val result = parser.parse(
            content = "version = 1",
            scope = TaskScope.GLOBAL,
            source = Path.of("tasks.toml"),
        )

        val success = assertIs<TaskConfigLoadResult.Success>(result)
        assertTrue(success.snapshot.tasks.isEmpty())
    }

    @Test
    fun `rejects field with wrong TOML type`() {
        val result = parser.parse(
            content = """
                version = 1

                [[tasks]]
                id = "backend"
                sourceType = "inline"
                command = "echo ready"
                enabled = "yes"
            """.trimIndent(),
            scope = TaskScope.PROJECT_SHARED,
            source = Path.of("tasks.toml"),
        )

        val failure = assertIs<TaskConfigLoadResult.Failure>(result)
        assertTrue(failure.errors.any { it.message.contains("enabled") })
    }
}

package com.anmi.devworkspace.prepare

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.EnvironmentValue
import com.anmi.devworkspace.domain.FailureCategory
import com.anmi.devworkspace.domain.ResolvedTask
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskSource
import com.anmi.devworkspace.secrets.SecretStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TaskPreparationServiceTest {
    @Test
    fun `existing script and working directory produce prepared task`() = withTemporaryDirectory { directory ->
        val script = directory.resolve("start.sh")
        Files.writeString(script, "#!/bin/sh\necho ready\n")
        val task = task(
            id = "server",
            source = TaskSource.ScriptFile("${'$'}{PROJECT_DIR}/start.sh", arguments = listOf("--dev")),
            workingDirectory = "${'$'}{PROJECT_DIR}",
        )

        val result = prepare(task, directory)

        val prepared = assertIs<PreparationResult.Success>(result).task
        assertEquals("start.sh", prepared.displayName)
        assertEquals(directory, prepared.workingDirectory)
        assertEquals(ShellType.POSIX_SH, prepared.shellType)
        assertTrue("--dev" in prepared.command)
    }

    @Test
    fun `missing script is preparation failure`() = withTemporaryDirectory { directory ->
        val result = prepare(
            task("missing", TaskSource.ScriptFile("${'$'}{PROJECT_DIR}/missing.sh")),
            directory,
        )

        val failure = assertIs<PreparationResult.Failure>(result).failure
        assertEquals(FailureCategory.PREPARATION, failure.category)
    }

    @Test
    fun `missing secret is security failure with key but no value`() = withTemporaryDirectory { directory ->
        val task = task(
            id = "secret-task",
            source = TaskSource.InlineCommand("echo ready"),
            environment = mapOf("TOKEN" to EnvironmentValue.SecretReference("api-token")),
        )

        val result = prepare(task, directory, FakeSecretStore())

        val failure = assertIs<PreparationResult.Failure>(result).failure
        assertEquals(FailureCategory.SECURITY, failure.category)
        assertTrue("api-token" in failure.technicalMessage.orEmpty())
        assertFalse("secret-value" in failure.technicalMessage.orEmpty())
    }

    @Test
    fun `secret store exception message is never propagated`() = withTemporaryDirectory { directory ->
        val task = task(
            id = "secret-task",
            source = TaskSource.InlineCommand("echo ready"),
            environment = mapOf("TOKEN" to EnvironmentValue.SecretReference("api-token")),
        )
        val throwingStore = object : SecretStore {
            override suspend fun get(key: String): String? = error("secret-value")
            override suspend fun set(key: String, value: String?) = Unit
        }

        val result = prepare(task, directory, throwingStore)

        val failure = assertIs<PreparationResult.Failure>(result).failure
        assertEquals(FailureCategory.SECURITY, failure.category)
        assertTrue("api-token" in failure.technicalMessage.orEmpty())
        assertFalse("secret-value" in failure.technicalMessage.orEmpty())
    }

    @Test
    fun `display name follows alias script filename name then id`() = withTemporaryDirectory { directory ->
        Files.writeString(directory.resolve("run.sh"), "#!/bin/sh\n")

        val alias = prepare(
            task("one", TaskSource.ScriptFile("${'$'}{PROJECT_DIR}/run.sh"), name = "Name", terminalAlias = "Alias"),
            directory,
        )
        val script = prepare(
            task("two", TaskSource.ScriptFile("${'$'}{PROJECT_DIR}/run.sh"), name = "Name"),
            directory,
        )
        val name = prepare(task("three", TaskSource.InlineCommand("echo ready"), name = "Name"), directory)
        val id = prepare(task("four", TaskSource.InlineCommand("echo ready")), directory)

        assertEquals("Alias", assertIs<PreparationResult.Success>(alias).task.displayName)
        assertEquals("run.sh", assertIs<PreparationResult.Success>(script).task.displayName)
        assertEquals("Name", assertIs<PreparationResult.Success>(name).task.displayName)
        assertEquals("four", assertIs<PreparationResult.Success>(id).task.displayName)
    }

    private fun prepare(
        task: DevTask,
        projectDir: Path,
        secretStore: SecretStore = FakeSecretStore(),
    ): PreparationResult = runBlocking {
        TaskPreparationService(secretStore) { "execution-1" }.prepare(
            ResolvedTask(task),
            PreparationContext(
                projectDir = projectDir,
                userHome = projectDir,
                moduleDir = null,
                environment = emptyMap(),
                operatingSystem = OperatingSystem.LINUX,
                shellEnvironment = "/bin/sh",
            ),
        )
    }

    private fun task(
        id: String,
        source: TaskSource,
        name: String? = null,
        terminalAlias: String? = null,
        workingDirectory: String? = null,
        environment: Map<String, EnvironmentValue> = emptyMap(),
    ) = DevTask(
        id = id,
        name = name,
        terminalAlias = terminalAlias,
        scope = TaskScope.PROJECT_PRIVATE,
        source = source,
        workingDirectory = workingDirectory,
        environment = environment,
        exitDetection = false,
    )

    private class FakeSecretStore(
        private val values: Map<String, String> = emptyMap(),
    ) : SecretStore {
        override suspend fun get(key: String): String? = values[key]
        override suspend fun set(key: String, value: String?) = Unit
    }

    private fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("dev-workspace-preparation-test")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}

package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.EnvironmentValue
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TaskConfigurationServiceTest {
    @Test
    fun `initial published collections reject mutation`() = withService { service, _ ->
        val published = service.state.value

        assertFailsWith<UnsupportedOperationException> { (published.tasks as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (published.errors as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (published.snapshots as MutableMap).clear() }
    }

    @Test
    fun `loads all layers and resolves private over shared over global`() = withService { service, repositories ->
        repositories.success(TaskScope.GLOBAL, task("server", TaskScope.GLOBAL, "global"))
        repositories.success(TaskScope.PROJECT_SHARED, task("server", TaskScope.PROJECT_SHARED, "shared"))
        repositories.success(TaskScope.PROJECT_PRIVATE, task("server", TaskScope.PROJECT_PRIVATE, "private"))

        service.reload()

        val resolved = service.state.value.tasks.single()
        assertEquals("private", (resolved.effective.source as TaskSource.InlineCommand).command)
        assertEquals(listOf(TaskScope.PROJECT_SHARED, TaskScope.GLOBAL), resolved.shadowed.map { it.scope })
        assertEquals(TaskScope.entries.toSet(), service.state.value.snapshots.keys)
    }

    @Test
    fun `failed scope retains its last valid snapshot without erasing other scopes`() = withService { service, repositories ->
        repositories.success(TaskScope.GLOBAL, task("global", TaskScope.GLOBAL, "one"))
        repositories.success(TaskScope.PROJECT_SHARED, task("shared", TaskScope.PROJECT_SHARED, "one"))
        service.reload()
        val error = TaskConfigError(repositories.path(TaskScope.GLOBAL), 7, 4, "Invalid TOML syntax")
        repositories.result(TaskScope.GLOBAL, TaskConfigLoadResult.Failure(listOf(error)))
        repositories.success(TaskScope.PROJECT_SHARED, task("shared", TaskScope.PROJECT_SHARED, "two"))

        service.reload()

        assertEquals(setOf("global", "shared"), service.state.value.tasks.map { it.effective.id }.toSet())
        assertEquals(listOf(error), service.state.value.errors[TaskScope.GLOBAL])
        assertFalse(service.state.value.errors.containsKey(TaskScope.PROJECT_SHARED))
    }

    @Test
    fun `repair replaces only repaired snapshot and clears its errors`() = withService { service, repositories ->
        repositories.success(TaskScope.GLOBAL, task("old", TaskScope.GLOBAL, "old"))
        service.reload(TaskScope.GLOBAL)
        repositories.failure(TaskScope.GLOBAL, "Invalid task configuration structure")
        service.reload(TaskScope.GLOBAL)
        repositories.success(TaskScope.GLOBAL, task("new", TaskScope.GLOBAL, "new"))

        service.reload(TaskScope.GLOBAL)

        assertEquals("new", service.state.value.snapshots.getValue(TaskScope.GLOBAL).tasks.single().id)
        assertFalse(service.state.value.errors.containsKey(TaskScope.GLOBAL))
    }

    @Test
    fun `missing scope is a valid empty snapshot`() = withService { service, repositories ->
        repositories.success(TaskScope.GLOBAL)

        service.reload(TaskScope.GLOBAL)

        assertTrue(service.state.value.snapshots.getValue(TaskScope.GLOBAL).tasks.isEmpty())
        assertTrue(service.state.value.tasks.isEmpty())
    }

    @Test
    fun `failed first load restores only that scopes persistent last valid snapshot`() = runBlocking {
        val directory = Files.createTempDirectory("task-config-last-valid")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repositories = FakeRepositories()
            val source = repositories.path(TaskScope.GLOBAL)
            val content = """
                version = 1
                [[tasks]]
                id = "cached"
                sourceType = "inline"
                command = "safe"
            """.trimIndent()
            val snapshot = assertIs<TaskConfigLoadResult.Success>(
                TaskTomlParser().parse(content, TaskScope.GLOBAL, source),
            ).snapshot
            val store = LastValidSnapshotStore(TaskTomlParser(), AtomicFileWriter(), directory.resolve("cache"))
            store.save(snapshot, content)
            repositories.failure(TaskScope.GLOBAL, "Invalid TOML syntax")
            val service = TaskConfigurationService(scope, repositories.all, store)

            service.reload(TaskScope.GLOBAL)

            assertEquals("cached", service.state.value.tasks.single().effective.id)
            assertTrue(service.state.value.errors.containsKey(TaskScope.GLOBAL))
            service.dispose()
        } finally {
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ordinary persistent cache load failures do not abort other scopes`() = runBlocking {
        listOf(IOException("secret-value"), IllegalStateException("secret-value")).forEach { cacheFailure ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val repositories = FakeRepositories().apply {
                failure(TaskScope.GLOBAL, "Invalid TOML syntax")
                success(TaskScope.PROJECT_SHARED, task("shared", TaskScope.PROJECT_SHARED))
            }
            val service = TaskConfigurationService(scope, repositories.all, ThrowingSnapshotCache(loadFailure = cacheFailure))
            try {
                service.reload()

                assertEquals("shared", service.state.value.tasks.single().effective.id)
                assertEquals("Invalid TOML syntax", service.state.value.errors.getValue(TaskScope.GLOBAL).single().message)
                assertFalse(service.state.value.toString().contains("secret-value"))
            } finally {
                service.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun `ordinary persistent cache save failures do not abort other scopes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repositories = FakeRepositories().apply {
            success(TaskScope.GLOBAL, task("global", TaskScope.GLOBAL))
            success(TaskScope.PROJECT_SHARED, task("shared", TaskScope.PROJECT_SHARED))
        }
        val service = TaskConfigurationService(
            scope,
            repositories.all,
            ThrowingSnapshotCache(saveFailure = IllegalStateException("secret-value")),
        )
        try {
            service.reload()

            assertEquals(setOf("global", "shared"), service.state.value.tasks.map { it.effective.id }.toSet())
            assertFalse(service.state.value.toString().contains("secret-value"))
        } finally {
            service.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `expected hash mismatch is typed sanitized and does not write`() = runBlocking {
        val directory = Files.createTempDirectory("task-config-conflict")
        try {
            val path = directory.resolve("tasks.toml")
            Files.writeString(path, "version = 1")
            val repository = FileTaskRepository(TaskScope.GLOBAL, path)
            val exception = assertFailsWith<TaskConfigConflictException> {
                repository.save(listOf(task("secret", TaskScope.GLOBAL, "token=top-secret")), "stale-hash")
            }

            assertEquals(0, repository.load().successTasks().size)
            assertFalse(exception.message.orEmpty().contains("top-secret"))
            assertFalse(Files.readString(path).contains("top-secret"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `external edit during serialization window fails final hash check without overwrite`() = runBlocking {
        val directory = Files.createTempDirectory("task-config-final-conflict")
        try {
            val path = directory.resolve("tasks.toml")
            Files.writeString(path, "version = 1")
            val externalContent = """
                version = 1
                [[tasks]]
                id = "external"
                sourceType = "inline"
                command = "keep external"
            """.trimIndent()
            val atomicWriter = AtomicFileWriter(
                beforeReplace = { Files.writeString(path, externalContent) },
            )
            val repository = FileTaskRepository(TaskScope.GLOBAL, path, atomicWriter = atomicWriter)
            val expectedHash = assertIs<TaskConfigLoadResult.Success>(repository.load()).snapshot.contentHash

            assertFailsWith<TaskConfigConflictException> {
                repository.save(listOf(task("plugin", TaskScope.GLOBAL, "plugin write")), expectedHash)
            }

            assertEquals(externalContent, Files.readString(path))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `config errors retain location and sanitized message`() = withService { service, repositories ->
        val error = TaskConfigError(
            repositories.path(TaskScope.PROJECT_PRIVATE),
            11,
            9,
            "Task 'secret-value' command token=secret-value is invalid",
        )
        repositories.result(TaskScope.PROJECT_PRIVATE, TaskConfigLoadResult.Failure(listOf(error)))

        service.reload(TaskScope.PROJECT_PRIVATE)

        val published = service.state.value.errors.getValue(TaskScope.PROJECT_PRIVATE).single()
        assertEquals(error.sourceFile, published.sourceFile)
        assertEquals(11, published.line)
        assertEquals(9, published.column)
        assertEquals("Invalid task configuration", published.message)
        assertFalse(service.state.value.errors.toString().contains("secret-value"))
    }

    @Test
    fun `published state does not retain mutable task collections`() = withService { service, repositories ->
        val environment = mutableMapOf<String, EnvironmentValue>("MODE" to EnvironmentValue.Plain("safe"))
        val configured = task("immutable", TaskScope.GLOBAL).copy(environment = environment)
        repositories.success(TaskScope.GLOBAL, configured)

        service.reload(TaskScope.GLOBAL)
        environment["MODE"] = EnvironmentValue.Plain("changed")

        assertEquals(EnvironmentValue.Plain("safe"), service.state.value.tasks.single().effective.environment["MODE"])
    }

    @Test
    fun `every published collection rejects mutation`() = withService { service, repositories ->
        val global = DevTask(
            id = "immutable",
            scope = TaskScope.GLOBAL,
            source = TaskSource.ScriptFile("script.ps1", arguments = mutableListOf("--safe")),
            environment = mutableMapOf("MODE" to EnvironmentValue.Plain("safe")),
        )
        val shared = global.copy(scope = TaskScope.PROJECT_SHARED)
        repositories.success(TaskScope.GLOBAL, global)
        repositories.success(TaskScope.PROJECT_SHARED, shared)
        repositories.failure(TaskScope.PROJECT_PRIVATE, "Invalid TOML syntax")
        service.reload()
        val published = service.state.value
        val original = published.copy()

        assertFailsWith<UnsupportedOperationException> { (published.tasks as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (published.errors as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (published.snapshots as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (published.errors.getValue(TaskScope.PROJECT_PRIVATE) as MutableList).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (published.snapshots.getValue(TaskScope.GLOBAL).tasks as MutableList).clear()
        }
        assertFailsWith<UnsupportedOperationException> { (published.tasks.single().shadowed as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (published.tasks.single().effective.environment as MutableMap).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            ((published.tasks.single().effective.source as TaskSource.ScriptFile).arguments as MutableList).clear()
        }
        assertEquals(original, service.state.value)
    }

    @Test
    fun `listener accepts only exact normalized configuration paths`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val debouncer = ManualDebouncer()
        val watched = Path.of("config", "tasks.toml").toAbsolutePath().normalize()
        var reloads = 0
        val listener = TaskConfigFileListener(
            watchedPaths = mapOf(TaskScope.GLOBAL to watched),
            scope = scope,
            debouncer = debouncer,
            contentHash = { "external" },
            reload = { reloads++ },
        )
        try {
            listener.pathsChanged(
                listOf(
                    watched.parent.resolve("other/tasks.toml"),
                    Path.of(watched.toString() + ".backup"),
                    watched.parent.resolve("nested").resolve(watched.fileName),
                ),
            )
            debouncer.flush()
            assertEquals(0, reloads)

            listener.pathsChanged(listOf(watched.parent.resolve("nested/../tasks.toml")))
            debouncer.flush()
            assertEquals(1, reloads)
        } finally {
            listener.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `multiple external events debounce to one reload without sleeping`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val debouncer = ManualDebouncer()
        val path = Path.of("config/tasks.toml").toAbsolutePath().normalize()
        var reloads = 0
        val listener = TaskConfigFileListener(
            mapOf(TaskScope.GLOBAL to path), scope, debouncer, { "external" }, { reloads++ },
        )
        try {
            listener.pathsChanged(listOf(path))
            listener.pathsChanged(listOf(path))
            listener.pathsChanged(listOf(path))

            assertEquals(0, reloads)
            debouncer.flush()
            assertEquals(1, reloads)
        } finally {
            listener.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `matching plugin write event is consumed without reload`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val debouncer = ManualDebouncer()
        val path = Path.of("config/tasks.toml").toAbsolutePath().normalize()
        var reloads = 0
        val listener = TaskConfigFileListener(
            mapOf(TaskScope.GLOBAL to path), scope, debouncer, { "saved-hash" }, { reloads++ },
        )
        try {
            listener.expectPluginWrite(path, "saved-hash")
            listener.pathsChanged(listOf(path))
            debouncer.flush()
            assertEquals(0, reloads)
        } finally {
            listener.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `nonmatching plugin write event is external and reloads`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val debouncer = ManualDebouncer()
        val path = Path.of("config/tasks.toml").toAbsolutePath().normalize()
        var reloads = 0
        val listener = TaskConfigFileListener(
            mapOf(TaskScope.GLOBAL to path), scope, debouncer, { "external-hash" }, { reloads++ },
        )
        try {
            listener.expectPluginWrite(path, "saved-hash")
            listener.pathsChanged(listOf(path))
            debouncer.flush()
            assertEquals(1, reloads)
        } finally {
            listener.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `content hash security failure is treated as an external reload`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val debouncer = ManualDebouncer()
        val path = Path.of("config/tasks.toml").toAbsolutePath().normalize()
        var reloads = 0
        val listener = TaskConfigFileListener(
            mapOf(TaskScope.GLOBAL to path),
            scope,
            debouncer,
            { throw SecurityException("secret-value") },
            { reloads++ },
        )
        try {
            listener.expectPluginWrite(path, "saved-hash")
            listener.pathsChanged(listOf(path))

            debouncer.flush()

            assertEquals(1, reloads)
        } finally {
            listener.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `disposing listener cancels pending debounce`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val debouncer = ManualDebouncer()
        val path = Path.of("config/tasks.toml").toAbsolutePath().normalize()
        var reloads = 0
        val listener = TaskConfigFileListener(
            mapOf(TaskScope.GLOBAL to path), scope, debouncer, { "external" }, { reloads++ },
        )

        listener.pathsChanged(listOf(path))
        listener.dispose()
        debouncer.flush()

        assertTrue(debouncer.disposed)
        assertEquals(0, reloads)
        scope.cancel()
    }

    private fun withService(block: suspend (TaskConfigurationService, FakeRepositories) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repositories = FakeRepositories()
        val service = TaskConfigurationService(scope, repositories.all)
        try {
            block(service, repositories)
        } finally {
            service.dispose()
            scope.cancel()
        }
    }

    private fun task(id: String, scope: TaskScope, command: String = id) = DevTask(
        id = id,
        scope = scope,
        source = TaskSource.InlineCommand(command),
    )

    private fun TaskConfigLoadResult.successTasks(): List<DevTask> =
        assertIs<TaskConfigLoadResult.Success>(this).snapshot.tasks

    private class FakeRepositories {
        private val repositories = TaskScope.entries.associateWith { FakeTaskRepository(it) }
        val all: List<TaskRepository> = repositories.values.toList()

        fun path(scope: TaskScope): Path = repositories.getValue(scope).path

        fun result(scope: TaskScope, result: TaskConfigLoadResult) {
            repositories.getValue(scope).next = result
        }

        fun success(scope: TaskScope, vararg tasks: DevTask) {
            result(scope, TaskConfigLoadResult.Success(snapshot(scope, tasks.toList())))
        }

        fun failure(scope: TaskScope, message: String) {
            result(scope, TaskConfigLoadResult.Failure(listOf(TaskConfigError(path(scope), 1, 1, message))))
        }

        private fun snapshot(scope: TaskScope, tasks: List<DevTask>) = TaskConfigSnapshot(
            scope = scope,
            tasks = tasks,
            sourceFile = path(scope),
            contentHash = "hash-${scope.name}-${tasks.joinToString { it.id }}",
            loadedAt = Instant.EPOCH,
        )
    }

    private class FakeTaskRepository(override val scope: TaskScope) : TaskRepository {
        override val path: Path = Path.of("fake", scope.name.lowercase(), "tasks.toml").toAbsolutePath().normalize()
        var next: TaskConfigLoadResult = TaskConfigLoadResult.Success(
            TaskConfigSnapshot(scope, emptyList(), path, "empty-${scope.name}", Instant.EPOCH),
        )

        override suspend fun load(): TaskConfigLoadResult = next

        override suspend fun save(tasks: List<DevTask>, expectedHash: String?): TaskConfigSnapshot {
            val current = (next as? TaskConfigLoadResult.Success)?.snapshot
            if (expectedHash != null && current?.contentHash != expectedHash) {
                throw TaskConfigConflictException(scope, path, expectedHash, current?.contentHash)
            }
            return TaskConfigSnapshot(scope, tasks, path, "saved", Instant.EPOCH).also {
                next = TaskConfigLoadResult.Success(it)
            }
        }
    }

    private class ManualDebouncer : TaskConfigDebouncer {
        private var pending: (suspend () -> Unit)? = null
        var disposed = false
            private set

        override fun submit(action: suspend () -> Unit) {
            if (!disposed) pending = action
        }

        suspend fun flush() {
            val action = pending
            pending = null
            action?.invoke()
        }

        override fun dispose() {
            disposed = true
            pending = null
        }
    }

    private class ThrowingSnapshotCache(
        private val loadFailure: Exception? = null,
        private val saveFailure: Exception? = null,
    ) : TaskSnapshotCache {
        override fun save(snapshot: TaskConfigSnapshot, content: String) {
            saveFailure?.let { throw it }
        }

        override fun load(source: Path, scope: TaskScope): TaskConfigSnapshot? {
            loadFailure?.let { throw it }
            return null
        }
    }
}

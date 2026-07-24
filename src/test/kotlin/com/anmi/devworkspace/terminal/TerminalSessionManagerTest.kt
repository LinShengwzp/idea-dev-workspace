package com.anmi.devworkspace.terminal

import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TerminalPolicy
import com.anmi.devworkspace.prepare.PreparedTask
import com.anmi.devworkspace.prepare.ShellType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class TerminalSessionManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun `reuse task terminal returns the same session for a project task`() = runBlocking {
        val gateway = FakeGateway()
        val manager = TerminalSessionManager(gateway, scope)
        val task = preparedTask(policy = TerminalPolicy.REUSE_TASK_TERMINAL)

        val first = manager.acquire("project-a", task)
        val second = manager.acquire("project-a", task.copy(executionId = "execution-2"))

        assertSame(first, second)
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun `identical environment reuses task session regardless of map order`() = runBlocking {
        val gateway = FakeGateway()
        val manager = TerminalSessionManager(gateway, scope)
        val firstTask = preparedTask(
            policy = TerminalPolicy.REUSE_TASK_TERMINAL,
            environment = linkedMapOf("B" to "two", "A" to "one"),
        )
        val secondTask = firstTask.copy(
            executionId = "execution-2",
            environment = linkedMapOf("A" to "one", "B" to "two"),
        )

        val first = manager.acquire("project-a", firstTask)
        val second = manager.acquire("project-a", secondTask)

        assertSame(first, second)
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun `changed plain environment creates new task session without closing old tab`() = runBlocking {
        val gateway = FakeGateway()
        val manager = TerminalSessionManager(gateway, scope)
        val task = preparedTask(
            policy = TerminalPolicy.REUSE_TASK_TERMINAL,
            environment = mapOf("MODE" to "old"),
        )

        val first = manager.acquire("project-a", task) as FakeSession
        val second = manager.acquire(
            "project-a",
            task.copy(executionId = "execution-2", environment = mapOf("MODE" to "new")),
        )
        val reused = manager.acquire(
            "project-a",
            task.copy(executionId = "execution-3", environment = mapOf("MODE" to "new")),
        )

        assertNotSame(first, second)
        assertSame(second, reused)
        assertEquals(0, first.closeCount)
        assertEquals(2, gateway.requests.size)
    }

    @Test
    fun `changed resolved secret value creates new task session`() = runBlocking {
        val gateway = FakeGateway()
        val manager = TerminalSessionManager(gateway, scope)
        val task = preparedTask(
            policy = TerminalPolicy.REUSE_TASK_TERMINAL,
            environment = mapOf("API_TOKEN" to "resolved-secret-old"),
        )

        val first = manager.acquire("project-a", task)
        val second = manager.acquire(
            "project-a",
            task.copy(
                executionId = "execution-2",
                environment = mapOf("API_TOKEN" to "resolved-secret-new"),
            ),
        )

        assertNotSame(first, second)
        assertEquals(2, gateway.requests.size)
    }

    @Test
    fun `changed working directory creates new task session`() = runBlocking {
        val gateway = FakeGateway()
        val manager = TerminalSessionManager(gateway, scope)
        val task = preparedTask(
            policy = TerminalPolicy.REUSE_TASK_TERMINAL,
            workingDirectory = java.nio.file.Path.of("project", "one"),
        )

        val first = manager.acquire("project-a", task)
        val second = manager.acquire(
            "project-a",
            task.copy(executionId = "execution-2", workingDirectory = java.nio.file.Path.of("project", "two")),
        )

        assertNotSame(first, second)
        assertEquals(2, gateway.requests.size)
    }

    @Test
    fun `changed shell creates new task session`() = runBlocking {
        val gateway = FakeGateway()
        val manager = TerminalSessionManager(gateway, scope)
        val task = preparedTask(
            policy = TerminalPolicy.REUSE_TASK_TERMINAL,
            shellType = ShellType.POWERSHELL,
        )

        val first = manager.acquire("project-a", task)
        val second = manager.acquire(
            "project-a",
            task.copy(executionId = "execution-2", shellType = ShellType.CMD),
        )

        assertNotSame(first, second)
        assertEquals(2, gateway.requests.size)
    }

    @Test
    fun `shared reuse also requires matching launch signature`() = runBlocking {
        val gateway = FakeGateway()
        val manager = TerminalSessionManager(gateway, scope)
        val task = preparedTask(
            taskId = "backend",
            policy = TerminalPolicy.REUSE_SHARED,
            environment = mapOf("PROFILE" to "backend"),
        )

        val first = manager.acquire("project-a", task)
        val second = manager.acquire(
            "project-a",
            task.copy(
                taskId = "frontend",
                executionId = "execution-2",
                environment = mapOf("PROFILE" to "frontend"),
            ),
        )

        assertNotSame(first, second)
        assertEquals(2, gateway.requests.size)
    }

    @Test
    fun `launch signature output never exposes environment or secret values`() {
        val signature = TerminalLaunchSignature.from(
            preparedTask(
                policy = TerminalPolicy.REUSE_TASK_TERMINAL,
                environment = mapOf(
                    "VISIBLE_NAME" to "plain-sensitive-value",
                    "API_TOKEN" to "resolved-secret-value",
                ),
            ),
        )

        val output = signature.toString()

        assertFalse(output.contains("VISIBLE_NAME"))
        assertFalse(output.contains("API_TOKEN"))
        assertFalse(output.contains("plain-sensitive-value"))
        assertFalse(output.contains("resolved-secret-value"))
    }

    @Test
    fun `always new creates different sessions with numbered titles`() = runBlocking {
        val gateway = FakeGateway()
        val manager = TerminalSessionManager(gateway, scope)
        val task = preparedTask(policy = TerminalPolicy.ALWAYS_NEW)

        val first = manager.acquire("project-a", task)
        val second = manager.acquire("project-a", task.copy(executionId = "execution-2"))

        assertNotSame(first, second)
        assertEquals(listOf("Backend", "Backend (2)"), gateway.requests.map { it.task.displayName })
    }

    @Test
    fun `reuse shared returns the same project session across tasks`() = runBlocking {
        val gateway = FakeGateway()
        val manager = TerminalSessionManager(gateway, scope)

        val first = manager.acquire(
            "project-a",
            preparedTask(taskId = "backend", policy = TerminalPolicy.REUSE_SHARED),
        )
        val second = manager.acquire(
            "project-a",
            preparedTask(taskId = "frontend", policy = TerminalPolicy.REUSE_SHARED),
        )

        assertSame(first, second)
        assertEquals(1, gateway.requests.size)
        assertEquals("Dev Tasks", gateway.requests.single().task.displayName)
    }

    @Test
    fun `closed reusable session is removed and recreated`() = runBlocking {
        val gateway = FakeGateway()
        val manager = TerminalSessionManager(gateway)
        val task = preparedTask(policy = TerminalPolicy.REUSE_TASK_TERMINAL)

        val first = manager.acquire("project-a", task) as FakeSession
        first.emit(TerminalCommandState.SESSION_CLOSED)
        assertNull(manager.withSession(task.taskId, task.executionId) { it })
        val second = manager.acquire("project-a", task.copy(executionId = "execution-2"))

        assertNotSame(first, second)
        assertEquals(2, gateway.requests.size)
    }

    @Test
    fun `closed shared session is removed and recreated`() = runBlocking {
        val gateway = FakeGateway()
        val manager = TerminalSessionManager(gateway)
        val task = preparedTask(policy = TerminalPolicy.REUSE_SHARED)

        val first = manager.acquire("project-a", task) as FakeSession
        first.emit(TerminalCommandState.SESSION_CLOSED)
        val second = manager.acquire("project-a", task.copy(executionId = "execution-2"))

        assertNotSame(first, second)
        assertEquals(2, gateway.requests.size)
    }

    private fun preparedTask(
        taskId: String = "backend",
        policy: TerminalPolicy,
        environment: Map<String, String> = emptyMap(),
        workingDirectory: java.nio.file.Path = java.nio.file.Path.of("project"),
        shellType: ShellType = ShellType.POWERSHELL,
    ) = PreparedTask(
        taskId = taskId,
        executionId = "execution-1",
        displayName = "Backend",
        command = "Write-Output ready",
        workingDirectory = workingDirectory,
        environment = environment,
        shellType = shellType,
        terminalPolicy = policy,
        exitDetection = true,
        sourceScope = TaskScope.PROJECT_SHARED,
    )

    private class FakeGateway : TerminalGateway {
        val requests = mutableListOf<Request>()

        override suspend fun acquire(
            key: TerminalSessionKey,
            preparedTask: PreparedTask,
            policy: TerminalPolicy,
        ): TerminalSession = FakeSession(
            id = "session-${requests.size + 1}",
            title = preparedTask.displayName,
        ).also { requests += Request(key, preparedTask, policy) }
    }

    private data class Request(
        val key: TerminalSessionKey,
        val task: PreparedTask,
        val policy: TerminalPolicy,
    )

    private class FakeSession(
        override val id: String,
        override val title: String,
    ) : TerminalSession {
        private val eventFlow = MutableSharedFlow<TerminalCommandEvent>(replay = 1)
        private var closed = false
        override val isClosed: Boolean get() = closed
        override val events: Flow<TerminalCommandEvent> = eventFlow
        var closeCount = 0

        override suspend fun awaitReady(timeoutMillis: Long): Boolean = true
        override suspend fun execute(text: String) = Unit
        override suspend fun sendInterrupt() = Unit
        override suspend fun activate() = Unit
        override suspend fun close() {
            closeCount++
        }

        suspend fun emit(state: TerminalCommandState) {
            if (state == TerminalCommandState.SESSION_CLOSED) closed = true
            eventFlow.emit(TerminalCommandEvent(executionId = null, state = state))
        }
    }
}

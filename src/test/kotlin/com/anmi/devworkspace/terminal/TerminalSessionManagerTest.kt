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
    ) = PreparedTask(
        taskId = taskId,
        executionId = "execution-1",
        displayName = "Backend",
        command = "Write-Output ready",
        workingDirectory = java.nio.file.Path.of("project"),
        environment = emptyMap(),
        shellType = ShellType.POWERSHELL,
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
        override val events: Flow<TerminalCommandEvent> = eventFlow

        override suspend fun awaitReady(timeoutMillis: Long): Boolean = true
        override suspend fun execute(text: String) = Unit
        override suspend fun sendInterrupt() = Unit
        override suspend fun activate() = Unit
        override suspend fun close() = Unit

        suspend fun emit(state: TerminalCommandState) {
            eventFlow.emit(TerminalCommandEvent(executionId = null, state = state))
        }
    }
}

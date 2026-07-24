package com.anmi.devworkspace.runtime

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.ResolvedTask
import com.anmi.devworkspace.domain.RunTrigger
import com.anmi.devworkspace.domain.TaskExecution
import com.anmi.devworkspace.domain.TaskFailure
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskSource
import com.anmi.devworkspace.domain.TaskStatus
import com.anmi.devworkspace.domain.TerminalPolicy
import com.anmi.devworkspace.prepare.OperatingSystem
import com.anmi.devworkspace.prepare.PreparationContext
import com.anmi.devworkspace.prepare.PreparationResult
import com.anmi.devworkspace.prepare.PreparedTask
import com.anmi.devworkspace.prepare.ShellType
import com.anmi.devworkspace.terminal.TerminalCommandEvent
import com.anmi.devworkspace.terminal.TerminalCommandState
import com.anmi.devworkspace.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskRunnerTest {
    @Test
    fun `command dispatch immediately succeeds and releases active slot`() = runBlocking {
        val registry = InMemoryTaskExecutionRegistry(fixedClock)
        val session = FakeTerminalSession(shellIntegrationReady = false) {}
        val runner = TaskRunner(
            registry = registry,
            prepareTask = { _, _, executionId ->
                assertEquals(TaskStatus.PREPARING, registry.active("backend")?.status)
                PreparationResult.Success(preparedTask(executionId, exitDetection = true))
            },
            acquireSession = { session },
            executionIdProvider = { "run-1" },
            clock = fixedClock,
        )

        val result = runner.run(task(), RunTrigger.MANUAL, context())

        val execution = assertNotNull(result.getOrNull())
        assertEquals(TaskStatus.SUCCEEDED, execution.status)
        assertNull(execution.exitCode)
        assertNull(registry.active("backend"))
    }

    @Test
    fun `launcher ignores exit detection compatibility field`() = runBlocking {
        val registry = InMemoryTaskExecutionRegistry(fixedClock)
        val runner = TaskRunner(
            registry = registry,
            prepareTask = { resolved, _, executionId ->
                assertFalse(resolved.effective.exitDetection)
                PreparationResult.Success(preparedTask(executionId, exitDetection = false))
            },
            acquireSession = { FakeTerminalSession(shellIntegrationReady = true) {} },
            executionIdProvider = { "run-2" },
            clock = fixedClock,
        )

        val execution = assertNotNull(runner.run(task(), RunTrigger.MANUAL, context()).getOrNull())

        assertEquals(TaskStatus.SUCCEEDED, execution.status)
        assertNull(registry.active("backend"))
    }

    @Test
    fun `later exit and close events do not replace sent result`() = runBlocking {
        val registry = InMemoryTaskExecutionRegistry(fixedClock)
        val session = FakeTerminalSession(shellIntegrationReady = true) {}
        val runner = TaskRunner(
            registry = registry,
            prepareTask = { _, _, executionId ->
                PreparationResult.Success(preparedTask(executionId, exitDetection = true))
            },
            acquireSession = { session },
            executionIdProvider = { "run-sent" },
            clock = fixedClock,
        )

        val sent = runner.run(task(), RunTrigger.MANUAL, context()).getOrThrow()
        session.emitOutput("__DEV_TASK_BEGIN__:run-sent\n__DEV_TASK_EXIT__:run-sent:17\n")
        session.emitEvent(TerminalCommandState.FINISHED, exitCode = 17)
        session.emitEvent(TerminalCommandState.SESSION_CLOSED)
        yield()

        assertEquals(TaskStatus.SUCCEEDED, sent.status)
        assertEquals(TaskStatus.SUCCEEDED, registry.executions.value.getValue("backend").status)
        assertNull(registry.executions.value.getValue("backend").exitCode)
        assertNull(registry.active("backend"))
    }

    @Test
    fun `same task can be dispatched again immediately`() = runBlocking {
        val registry = InMemoryTaskExecutionRegistry(fixedClock)
        var sequence = 0
        val session = FakeTerminalSession(shellIntegrationReady = true) {}
        val runner = TaskRunner(
            registry = registry,
            prepareTask = { _, _, executionId ->
                PreparationResult.Success(preparedTask(executionId, exitDetection = true))
            },
            acquireSession = { session },
            executionIdProvider = { "run-${++sequence}" },
            clock = fixedClock,
        )

        val first = runner.run(task(), RunTrigger.MANUAL, context()).getOrThrow()
        val second = runner.run(task(), RunTrigger.MANUAL, context()).getOrThrow()

        assertEquals(TaskStatus.SUCCEEDED, first.status)
        assertEquals("run-1", first.executionId)
        assertEquals(TaskStatus.SUCCEEDED, second.status)
        assertEquals("run-2", second.executionId)
        assertNull(registry.active("backend"))
    }

    @Test
    fun `native completion emitted during send does not become an exit result`() = runBlocking {
        val registry = InMemoryTaskExecutionRegistry(fixedClock)
        val session = FakeTerminalSession(shellIntegrationReady = true) {
            emitEvent(TerminalCommandState.FINISHED, exitCode = 99)
            emitOutput("__DEV_TASK_BEGIN__:run-3\n__DEV_TASK_EXIT__:run-3:9\n")
            emitEvent(TerminalCommandState.FINISHED, exitCode = 0)
        }
        val runner = TaskRunner(
            registry = registry,
            prepareTask = { _, _, executionId ->
                PreparationResult.Success(preparedTask(executionId, exitDetection = true))
            },
            acquireSession = { session },
            executionIdProvider = { "run-3" },
            clock = fixedClock,
        )

        val execution = assertNotNull(runner.run(task(), RunTrigger.MANUAL, context()).getOrNull())

        assertEquals(TaskStatus.SUCCEEDED, execution.status)
        assertNull(execution.exitCode)
    }

    @Test
    fun `does not swallow coroutine cancellation`() = runBlocking {
        val registry = InMemoryTaskExecutionRegistry(fixedClock)
        val runner = TaskRunner(
            registry = registry,
            prepareTask = { _, _, executionId ->
                PreparationResult.Success(preparedTask(executionId, exitDetection = false))
            },
            acquireSession = {
                FakeTerminalSession(shellIntegrationReady = true) {
                    throw CancellationException("cancelled")
                }
            },
            executionIdProvider = { "run-4" },
            clock = fixedClock,
        )

        assertFailsWith<CancellationException> {
            runner.run(task(), RunTrigger.MANUAL, context())
        }
        assertEquals(TaskStatus.UNKNOWN, registry.executions.value.getValue("backend").status)
    }

    @Test
    fun `serializes different tasks that acquire the same terminal session`() = runBlocking {
        val registry = InMemoryTaskExecutionRegistry(fixedClock)
        val firstExecuted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondExecuted = CompletableDeferred<Unit>()
        val session = CoordinatedTerminalSession(firstExecuted, releaseFirst, secondExecuted)
        var sequence = 0
        val runner = TaskRunner(
            registry = registry,
            prepareTask = { resolved, _, executionId ->
                PreparationResult.Success(
                    preparedTask(
                        executionId = executionId,
                        exitDetection = true,
                        taskId = resolved.effective.id,
                        command = executionId,
                    ),
                )
            },
            acquireSession = { session },
            executionIdProvider = { if (sequence++ == 0) "run-a" else "run-b" },
            clock = fixedClock,
        )

        val first = async { runner.run(task("backend"), RunTrigger.MANUAL, context()) }
        firstExecuted.await()
        val second = async { runner.run(task("frontend"), RunTrigger.MANUAL, context()) }

        assertNull(withTimeoutOrNull(100) { secondExecuted.await() })
        releaseFirst.complete(Unit)
        assertEquals(TaskStatus.SUCCEEDED, first.await().getOrThrow().status)
        assertEquals(TaskStatus.SUCCEEDED, second.await().getOrThrow().status)
    }

    @Test
    fun `sanitizes checked terminal exceptions`() = runBlocking {
        val registry = InMemoryTaskExecutionRegistry(fixedClock)
        val runner = TaskRunner(
            registry = registry,
            prepareTask = { _, _, executionId ->
                PreparationResult.Success(preparedTask(executionId, exitDetection = true))
            },
            acquireSession = { throw IOException("secret-value") },
            executionIdProvider = { "run-5" },
            clock = fixedClock,
        )

        val execution = runner.run(task(), RunTrigger.MANUAL, context()).getOrThrow()

        assertEquals(TaskStatus.FAILED, execution.status)
        val failureText = listOfNotNull(execution.failure?.userMessage, execution.failure?.technicalMessage)
            .joinToString(" ")
        assertFalse(failureText.contains("secret-value"))
    }

    private fun task(id: String = "backend") = ResolvedTask(
        DevTask(
            id = id,
            scope = TaskScope.PROJECT_SHARED,
            source = TaskSource.InlineCommand("ignored"),
        ),
    )

    private fun context() = PreparationContext(
        projectDir = Path.of("."),
        userHome = Path.of("."),
        moduleDir = null,
        environment = emptyMap(),
        operatingSystem = OperatingSystem.WINDOWS,
        shellEnvironment = null,
    )

    private fun preparedTask(
        executionId: String,
        exitDetection: Boolean,
        taskId: String = "backend",
        command: String = "wrapped command",
    ) = PreparedTask(
        taskId = taskId,
        executionId = executionId,
        displayName = "Backend",
        command = command,
        workingDirectory = Path.of("."),
        environment = emptyMap(),
        shellType = ShellType.POWERSHELL,
        terminalPolicy = TerminalPolicy.REUSE_TASK_TERMINAL,
        exitDetection = exitDetection,
        sourceScope = TaskScope.PROJECT_SHARED,
    )

    private class FakeTerminalSession(
        private val shellIntegrationReady: Boolean,
        private val onExecute: suspend FakeTerminalSession.() -> Unit,
    ) : TerminalSession {
        private val mutableEvents = MutableSharedFlow<TerminalCommandEvent>(extraBufferCapacity = 8)

        override val id = "terminal-1"
        override val title = "Backend"
        override val events: Flow<TerminalCommandEvent> = mutableEvents

        override suspend fun awaitReady(timeoutMillis: Long) = shellIntegrationReady
        override suspend fun execute(text: String) = onExecute()
        override suspend fun sendInterrupt() = Unit
        override suspend fun activate() = Unit
        override suspend fun close() = Unit

        suspend fun emitOutput(text: String) {
            emitEvent(TerminalCommandState.OUTPUT_CHANGED, outputText = text)
        }

        suspend fun emitEvent(
            state: TerminalCommandState,
            exitCode: Int? = null,
            outputText: String? = null,
        ) {
            mutableEvents.emit(TerminalCommandEvent(null, state, exitCode, outputText))
        }
    }

    private class CoordinatedTerminalSession(
        private val firstExecuted: CompletableDeferred<Unit>,
        private val releaseFirst: CompletableDeferred<Unit>,
        private val secondExecuted: CompletableDeferred<Unit>,
    ) : TerminalSession {
        private val mutableEvents = MutableSharedFlow<TerminalCommandEvent>(extraBufferCapacity = 8)

        override val id = "shared-terminal"
        override val title = "Dev Tasks"
        override val events: Flow<TerminalCommandEvent> = mutableEvents

        override suspend fun awaitReady(timeoutMillis: Long) = false

        override suspend fun execute(text: String) {
            if (text == "run-a") {
                firstExecuted.complete(Unit)
                releaseFirst.await()
            } else {
                secondExecuted.complete(Unit)
            }
            mutableEvents.emit(
                TerminalCommandEvent(
                    executionId = null,
                    state = TerminalCommandState.OUTPUT_CHANGED,
                    outputText = "__DEV_TASK_BEGIN__:$text\n__DEV_TASK_EXIT__:$text:0\n",
                ),
            )
        }

        override suspend fun sendInterrupt() = Unit
        override suspend fun activate() = Unit
        override suspend fun close() = Unit
    }

    private companion object {
        val fixedClock: Clock = Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC)
    }
}

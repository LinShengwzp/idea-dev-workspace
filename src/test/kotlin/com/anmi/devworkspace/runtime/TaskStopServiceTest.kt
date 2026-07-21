package com.anmi.devworkspace.runtime

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.ResolvedTask
import com.anmi.devworkspace.domain.RunTrigger
import com.anmi.devworkspace.domain.TaskExecution
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
import com.anmi.devworkspace.terminal.TerminalSessionLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskStopServiceTest {
    @Test
    fun `normal stop sends interrupt and finishes stopped`() = runBlocking {
        val fixture = fixture(TaskStatus.RUNNING)

        val stopping = async(start = CoroutineStart.UNDISPATCHED) { fixture.service.stop(TASK_ID) }
        fixture.session.interrupted.await()
        assertEquals(TaskStatus.STOPPING, fixture.registry.active(TASK_ID)?.status)
        fixture.session.emitOutput("__DEV_TASK_BEGIN__:$EXECUTION_ID\n")
        fixture.session.emit(TerminalCommandState.FINISHED)

        assertEquals(StopResult.Stopped, stopping.await())
        assertEquals(TaskStatus.STOPPED, fixture.registry.executions.value.getValue(TASK_ID).status)
        assertEquals(1, fixture.session.interruptCount)
    }

    @Test
    fun `timeout requests confirmation without closing terminal`() = runBlocking {
        val fixture = fixture(TaskStatus.RUNNING, title = "Backend\nsecret")

        val result = fixture.service.stop(TASK_ID, timeoutMillis = 0)

        assertEquals(StopResult.ForceCloseRequired(TASK_ID, EXECUTION_ID, "Task terminal"), result)
        assertEquals(0, fixture.session.closeCount)
    }

    @Test
    fun `already stopped execution is not running`() = runBlocking {
        val fixture = fixture(TaskStatus.STOPPED)

        assertEquals(StopResult.NotRunning, fixture.service.stop(TASK_ID))
        assertEquals(0, fixture.session.interruptCount)
    }

    @Test
    fun `missing execution is not running`() = runBlocking {
        val fixture = fixture(null)

        assertEquals(StopResult.NotRunning, fixture.service.stop(TASK_ID))
    }

    @Test
    fun `repeated stop calls share one interrupt`() = runBlocking {
        val fixture = fixture(TaskStatus.RUNNING)

        val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.service.stop(TASK_ID) }
        fixture.session.interrupted.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { fixture.service.stop(TASK_ID) }
        fixture.session.emitOutput("__DEV_TASK_BEGIN__:$EXECUTION_ID\n")
        fixture.session.emit(TerminalCommandState.FINISHED)

        assertEquals(StopResult.Stopped, first.await())
        assertEquals(StopResult.Stopped, second.await())
        assertEquals(1, fixture.session.interruptCount)
    }

    @Test
    fun `session close during stop finishes stopped`() = runBlocking {
        val fixture = fixture(TaskStatus.WAITING_FOR_TERMINAL)

        val stopping = async(start = CoroutineStart.UNDISPATCHED) { fixture.service.stop(TASK_ID) }
        fixture.session.interrupted.await()
        fixture.session.emit(TerminalCommandState.SESSION_CLOSED)

        assertEquals(StopResult.Stopped, stopping.await())
        assertEquals(TaskStatus.STOPPED, fixture.registry.executions.value.getValue(TASK_ID).status)
    }

    @Test
    fun `stale stop cannot interrupt a reused session before the atomic action`() = runBlocking {
        val fixture = fixture(TaskStatus.RUNNING)
        fixture.lookup.pauseNextAction()
        val stopping = async(start = CoroutineStart.UNDISPATCHED) { fixture.service.stop(TASK_ID) }
        fixture.lookup.actionRequested.await()
        fixture.registry.transition(TASK_ID, EXECUTION_ID, TaskStatus.STOPPED).getOrThrow()
        fixture.registry.begin(execution("new-execution", TaskStatus.PREPARING)).getOrThrow()
        fixture.lookup.replaceOwner("new-execution", fixture.session)
        fixture.lookup.allowAction.complete(Unit)

        assertEquals(StopResult.NotRunning, stopping.await())
        assertEquals(0, fixture.session.interruptCount)
        assertEquals("new-execution", fixture.registry.active(TASK_ID)?.executionId)
        assertEquals(TaskStatus.PREPARING, fixture.registry.active(TASK_ID)?.status)
    }

    @Test
    fun `force close closes only the active execution session`() = runBlocking {
        val fixture = fixture(TaskStatus.RUNNING)
        assertIsForceCloseRequired(fixture.service.stop(TASK_ID, timeoutMillis = 0))

        val result = fixture.service.forceClose(TASK_ID)

        assertTrue(result.isSuccess)
        assertEquals(1, fixture.session.closeCount)
        assertEquals(listOf(TASK_ID to EXECUTION_ID, TASK_ID to EXECUTION_ID), fixture.lookup.requests)
        assertEquals(TaskStatus.STOPPED, fixture.registry.executions.value.getValue(TASK_ID).status)
    }

    @Test
    fun `force close requires a prior stop confirmation`() = runBlocking {
        val fixture = fixture(TaskStatus.RUNNING)
        val stopping = async(start = CoroutineStart.UNDISPATCHED) { fixture.service.stop(TASK_ID) }
        fixture.session.interrupted.await()

        val result = fixture.service.forceClose(TASK_ID)

        assertTrue(result.isFailure)
        assertEquals(0, fixture.session.closeCount)
        fixture.session.emit(TerminalCommandState.SESSION_CLOSED)
        assertEquals(StopResult.Stopped, stopping.await())
    }

    @Test
    fun `stale force close cannot close a session reused by a newer execution`() = runBlocking {
        val fixture = fixture(TaskStatus.RUNNING)
        assertIsForceCloseRequired(fixture.service.stop(TASK_ID, timeoutMillis = 0))
        fixture.lookup.pauseNextAction()
        val closing = async(start = CoroutineStart.UNDISPATCHED) { fixture.service.forceClose(TASK_ID) }
        fixture.lookup.actionRequested.await()
        fixture.registry.transition(TASK_ID, EXECUTION_ID, TaskStatus.STOPPED).getOrThrow()
        fixture.registry.begin(execution("new-execution", TaskStatus.PREPARING)).getOrThrow()
        fixture.lookup.replaceOwner("new-execution", fixture.session)
        fixture.lookup.allowAction.complete(Unit)

        assertTrue(closing.await().isFailure)
        assertEquals(0, fixture.session.closeCount)
        assertEquals("new-execution", fixture.registry.active(TASK_ID)?.executionId)
    }

    @Test
    fun `stop cancellation propagates`() = runBlocking {
        val fixture = fixture(TaskStatus.RUNNING, suspendInterrupt = true)
        val stopping = async(start = CoroutineStart.UNDISPATCHED) { fixture.service.stop(TASK_ID) }

        stopping.cancel(CancellationException("cancelled"))

        val cancellation = assertFailsWith<CancellationException> { stopping.await() }
        assertEquals("cancelled", cancellation.message)
        assertEquals(TaskStatus.STOPPING, fixture.registry.active(TASK_ID)?.status)
        Unit
    }

    @Test
    fun `force close cancellation propagates and remains retryable`() = runBlocking {
        val fixture = fixture(TaskStatus.RUNNING, suspendClose = true)
        assertIsForceCloseRequired(fixture.service.stop(TASK_ID, timeoutMillis = 0))
        val closing = async(start = CoroutineStart.UNDISPATCHED) { fixture.service.forceClose(TASK_ID) }
        fixture.session.closeStarted.await()

        closing.cancel(CancellationException("cancelled"))

        val cancellation = assertFailsWith<CancellationException> { closing.await() }
        assertEquals("cancelled", cancellation.message)
        assertEquals(TaskStatus.STOPPING, fixture.registry.active(TASK_ID)?.status)
        fixture.session.suspendClose = false
        assertTrue(fixture.service.forceClose(TASK_ID).isSuccess)
        Unit
    }

    @Test
    fun `force close sanitizes exceptions`() = runBlocking {
        val fixture = fixture(TaskStatus.RUNNING, closeFailure = IOException("TOKEN=secret-value"))
        assertIsForceCloseRequired(fixture.service.stop(TASK_ID, timeoutMillis = 0))

        val failure = fixture.service.forceClose(TASK_ID).exceptionOrNull()

        assertEquals("Terminal session could not be closed", failure?.message)
        assertFalse(failure.toString().contains("secret-value"))
        assertEquals(TaskStatus.STOPPING, fixture.registry.active(TASK_ID)?.status)
        fixture.session.closeFailure = null
        assertTrue(fixture.service.forceClose(TASK_ID).isSuccess)
    }

    @Test
    fun `force close ownership exception is sanitized and remains retryable`() = runBlocking {
        val fixture = fixture(TaskStatus.RUNNING)
        assertIsForceCloseRequired(fixture.service.stop(TASK_ID, timeoutMillis = 0))
        fixture.lookup.failure = IOException("secret-value")

        val failure = fixture.service.forceClose(TASK_ID).exceptionOrNull()

        assertFalse(failure.toString().contains("secret-value"))
        assertEquals(TaskStatus.STOPPING, fixture.registry.active(TASK_ID)?.status)
        fixture.lookup.failure = null
        assertTrue(fixture.service.forceClose(TASK_ID).isSuccess)
    }

    @Test
    fun `native finish before matching begin does not complete stop`() = runBlocking {
        val fixture = fixture(TaskStatus.RUNNING)
        val stopping = async(start = CoroutineStart.UNDISPATCHED) { fixture.service.stop(TASK_ID) }
        fixture.session.interrupted.await()

        fixture.session.emit(TerminalCommandState.FINISHED)
        yield()

        assertFalse(stopping.isCompleted)
        fixture.session.emit(TerminalCommandState.SESSION_CLOSED)
        assertEquals(StopResult.Stopped, stopping.await())
    }

    @Test
    fun `checked interrupt exception is not disclosed`() = runBlocking {
        val fixture = fixture(TaskStatus.RUNNING, interruptFailure = IOException("secret-value"))

        val result = fixture.service.stop(TASK_ID, timeoutMillis = 0)

        assertFalse(result.toString().contains("secret-value"))
        assertEquals(StopResult.ForceCloseRequired(TASK_ID, EXECUTION_ID, "Task terminal"), result)
    }

    @Test
    fun `checked ownership exception is not disclosed`() = runBlocking {
        val fixture = fixture(TaskStatus.RUNNING)
        fixture.lookup.failure = IOException("secret-value")

        val result = fixture.service.stop(TASK_ID)

        assertEquals(StopResult.ForceCloseRequired(TASK_ID, EXECUTION_ID, "Task terminal"), result)
        assertFalse(result.toString().contains("secret-value"))
        assertEquals(TaskStatus.STOPPING, fixture.registry.active(TASK_ID)?.status)
    }

    @Test
    fun `checked event exception is sanitized and remains stopping`() = runBlocking {
        val fixture = fixture(TaskStatus.RUNNING, eventFailure = IOException("secret-value"))

        val result = fixture.service.stop(TASK_ID)

        assertEquals(StopResult.ForceCloseRequired(TASK_ID, EXECUTION_ID, "Task terminal"), result)
        assertFalse(result.toString().contains("secret-value"))
        assertEquals(TaskStatus.STOPPING, fixture.registry.active(TASK_ID)?.status)
    }

    @Test
    fun `stale timeout cannot authorize an execution after replacement`() = runBlocking {
        val fixture = fixture(TaskStatus.RUNNING, blockInterrupt = true)
        val stopping = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.service.stop(TASK_ID, timeoutMillis = 0)
        }
        fixture.session.interrupted.await()
        fixture.registry.transition(TASK_ID, EXECUTION_ID, TaskStatus.STOPPED).getOrThrow()
        fixture.registry.begin(execution("new-execution", TaskStatus.PREPARING)).getOrThrow()
        fixture.lookup.replaceOwner("new-execution", fixture.session)
        fixture.session.allowInterrupt.complete(Unit)

        assertEquals(StopResult.NotRunning, stopping.await())
        assertEquals("new-execution", fixture.registry.active(TASK_ID)?.executionId)
    }

    @Test
    fun `stop and runner completion race remains stopped`() = runBlocking {
        val registry = InMemoryTaskExecutionRegistry()
        val session = FakeSession()
        val runner = TaskRunner(
            registry = registry,
            prepareTask = { _, _, executionId -> PreparationResult.Success(preparedTask(executionId)) },
            acquireSession = { session },
            executionIdProvider = { EXECUTION_ID },
        )
        val service = TaskStopService(registry, FakeLookup(session))
        val running = async { runner.run(resolvedTask(), RunTrigger.MANUAL, context()) }
        session.executed.await()

        val stopping = async(start = CoroutineStart.UNDISPATCHED) { service.stop(TASK_ID) }
        session.interrupted.await()
        session.emit(TerminalCommandState.FINISHED, exitCode = 130)

        assertEquals(StopResult.Stopped, stopping.await())
        assertEquals(TaskStatus.STOPPED, running.await().getOrThrow().status)
        assertEquals(TaskStatus.STOPPED, registry.executions.value.getValue(TASK_ID).status)
    }

    @Test
    fun `interrupt induced runner failure remains stopped`() = runBlocking {
        val registry = InMemoryTaskExecutionRegistry()
        val session = FakeSession(executeFailureAfterInterrupt = true)
        val runner = TaskRunner(
            registry = registry,
            prepareTask = { _, _, executionId -> PreparationResult.Success(preparedTask(executionId)) },
            acquireSession = { session },
            executionIdProvider = { EXECUTION_ID },
        )
        val service = TaskStopService(registry, FakeLookup(session))
        val running = async { runner.run(resolvedTask(), RunTrigger.MANUAL, context()) }
        session.executed.await()

        service.stop(TASK_ID)

        assertEquals(TaskStatus.STOPPED, running.await().getOrThrow().status)
        assertEquals(TaskStatus.STOPPED, registry.executions.value.getValue(TASK_ID).status)
    }

    private suspend fun fixture(
        status: TaskStatus?,
        title: String = "Backend",
        suspendInterrupt: Boolean = false,
        closeFailure: Exception? = null,
        interruptFailure: Exception? = null,
        eventFailure: Exception? = null,
        suspendClose: Boolean = false,
        blockInterrupt: Boolean = false,
    ): Fixture {
        val registry = InMemoryTaskExecutionRegistry()
        if (status != null) {
            registry.begin(execution()).getOrThrow()
            when (status) {
                TaskStatus.PREPARING -> Unit
                TaskStatus.WAITING_FOR_TERMINAL -> registry.transition(TASK_ID, EXECUTION_ID, status).getOrThrow()
                TaskStatus.RUNNING, TaskStatus.STOPPING -> {
                    registry.transition(TASK_ID, EXECUTION_ID, TaskStatus.WAITING_FOR_TERMINAL).getOrThrow()
                    registry.transition(TASK_ID, EXECUTION_ID, TaskStatus.RUNNING).getOrThrow()
                    if (status == TaskStatus.STOPPING) {
                        registry.transition(TASK_ID, EXECUTION_ID, status).getOrThrow()
                    }
                }
                TaskStatus.STOPPED -> registry.transition(TASK_ID, EXECUTION_ID, status).getOrThrow()
                else -> error("Unsupported fixture status: $status")
            }
        }
        val session = FakeSession(
            title,
            suspendInterrupt,
            closeFailure,
            interruptFailure = interruptFailure,
            eventFailure = eventFailure,
            suspendClose = suspendClose,
            blockInterrupt = blockInterrupt,
        )
        val lookup = FakeLookup(session, EXECUTION_ID)
        return Fixture(registry, session, lookup, TaskStopService(registry, lookup))
    }

    private fun execution(
        executionId: String = EXECUTION_ID,
        status: TaskStatus = TaskStatus.PREPARING,
    ) = TaskExecution(
        taskId = TASK_ID,
        executionId = executionId,
        trigger = RunTrigger.MANUAL,
        status = status,
        startedAt = Instant.parse("2026-07-21T00:00:00Z"),
        sourceScope = TaskScope.PROJECT_SHARED,
    )

    private fun resolvedTask() = ResolvedTask(
        DevTask(TASK_ID, scope = TaskScope.PROJECT_SHARED, source = TaskSource.InlineCommand("ignored")),
    )

    private fun context() = PreparationContext(
        projectDir = Path.of("."),
        userHome = Path.of("."),
        moduleDir = null,
        environment = emptyMap(),
        operatingSystem = OperatingSystem.WINDOWS,
        shellEnvironment = null,
    )

    private fun preparedTask(executionId: String) = PreparedTask(
        taskId = TASK_ID,
        executionId = executionId,
        displayName = "Backend",
        command = "wrapped command",
        workingDirectory = Path.of("."),
        environment = emptyMap(),
        shellType = ShellType.POWERSHELL,
        terminalPolicy = TerminalPolicy.REUSE_TASK_TERMINAL,
        exitDetection = true,
        sourceScope = TaskScope.PROJECT_SHARED,
    )

    private data class Fixture(
        val registry: InMemoryTaskExecutionRegistry,
        val session: FakeSession,
        val lookup: FakeLookup,
        val service: TaskStopService,
    )

    private class FakeLookup(
        session: TerminalSession?,
        executionId: String = EXECUTION_ID,
    ) : TerminalSessionLookup {
        val requests = mutableListOf<Pair<String, String>>()
        val actionRequested = CompletableDeferred<Unit>()
        val allowAction = CompletableDeferred<Unit>()
        var failure: Exception? = null
        private var pause = false
        private var owner = executionId
        private var ownedSession = session

        override suspend fun <T> withSession(
            taskId: String,
            executionId: String,
            action: suspend (TerminalSession) -> T,
        ): T? {
            requests += taskId to executionId
            if (pause) {
                actionRequested.complete(Unit)
                allowAction.await()
            }
            failure?.let { throw it }
            val session = ownedSession?.takeIf { owner == executionId } ?: return null
            return action(session)
        }

        fun pauseNextAction() {
            pause = true
        }

        fun replaceOwner(executionId: String, session: TerminalSession?) {
            owner = executionId
            ownedSession = session
        }
    }

    private class FakeSession(
        override val title: String = "Backend",
        private val suspendInterrupt: Boolean = false,
        closeFailure: Exception? = null,
        private val executeFailureAfterInterrupt: Boolean = false,
        private val interruptFailure: Exception? = null,
        eventFailure: Exception? = null,
        suspendClose: Boolean = false,
        private val blockInterrupt: Boolean = false,
    ) : TerminalSession {
        private val mutableEvents = MutableSharedFlow<TerminalCommandEvent>(extraBufferCapacity = 8)
        val interrupted = CompletableDeferred<Unit>()
        val executed = CompletableDeferred<Unit>()
        val closeStarted = CompletableDeferred<Unit>()
        val allowInterrupt = CompletableDeferred<Unit>()
        var closeFailure = closeFailure
        var suspendClose = suspendClose
        var interruptCount = 0
        var closeCount = 0

        override val id = "terminal-1"
        override val events: Flow<TerminalCommandEvent> = eventFailure?.let { failure ->
            flow { throw failure }
        } ?: mutableEvents

        override suspend fun awaitReady(timeoutMillis: Long) = true

        override suspend fun execute(text: String) {
            mutableEvents.emit(
                TerminalCommandEvent(
                    executionId = EXECUTION_ID,
                    state = TerminalCommandState.OUTPUT_CHANGED,
                    outputText = "__DEV_TASK_BEGIN__:$EXECUTION_ID\n",
                ),
            )
            executed.complete(Unit)
            if (executeFailureAfterInterrupt) {
                interrupted.await()
                throw IOException("interrupt ended command")
            }
        }

        override suspend fun sendInterrupt() {
            interruptFailure?.let { throw it }
            interruptCount++
            interrupted.complete(Unit)
            if (blockInterrupt) allowInterrupt.await()
            if (suspendInterrupt) awaitCancellation()
        }

        override suspend fun activate() = Unit

        override suspend fun close() {
            closeCount++
            closeStarted.complete(Unit)
            if (suspendClose) awaitCancellation()
            closeFailure?.let { throw it }
        }

        suspend fun emit(state: TerminalCommandState, exitCode: Int? = null) {
            mutableEvents.emit(TerminalCommandEvent(EXECUTION_ID, state, exitCode))
        }

        suspend fun emitOutput(text: String) {
            mutableEvents.emit(TerminalCommandEvent(EXECUTION_ID, TerminalCommandState.OUTPUT_CHANGED, outputText = text))
        }
    }

    private companion object {
        const val TASK_ID = "backend"
        const val EXECUTION_ID = "execution-1"
    }

    private fun assertIsForceCloseRequired(result: StopResult) {
        assertTrue(result is StopResult.ForceCloseRequired)
    }
}

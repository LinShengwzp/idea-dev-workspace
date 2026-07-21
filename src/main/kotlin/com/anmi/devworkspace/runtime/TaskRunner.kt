package com.anmi.devworkspace.runtime

import com.anmi.devworkspace.domain.FailureCategory
import com.anmi.devworkspace.domain.ResolvedTask
import com.anmi.devworkspace.domain.RunTrigger
import com.anmi.devworkspace.domain.TaskExecution
import com.anmi.devworkspace.domain.TaskFailure
import com.anmi.devworkspace.domain.TaskStatus
import com.anmi.devworkspace.prepare.PreparationContext
import com.anmi.devworkspace.prepare.PreparationResult
import com.anmi.devworkspace.prepare.PreparedTask
import com.anmi.devworkspace.prepare.TaskPreparationService
import com.anmi.devworkspace.secrets.PasswordSafeSecretStore
import com.anmi.devworkspace.terminal.MarkerEvent
import com.anmi.devworkspace.terminal.TerminalCommandState
import com.anmi.devworkspace.terminal.TerminalGateway
import com.anmi.devworkspace.terminal.TerminalOutputMarkerParser
import com.anmi.devworkspace.terminal.TerminalSession
import com.anmi.devworkspace.terminal.TerminalSessionManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class TaskRunner(
    private val registry: TaskExecutionRegistry,
    private val prepareTask: suspend (ResolvedTask, PreparationContext, String) -> PreparationResult,
    private val acquireSession: suspend (PreparedTask) -> TerminalSession,
    private val executionIdProvider: () -> String = { UUID.randomUUID().toString() },
    private val clock: Clock = Clock.systemUTC(),
) {
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun run(
        task: ResolvedTask,
        trigger: RunTrigger,
        context: PreparationContext,
    ): Result<TaskExecution> {
        val executionId = executionIdProvider()
        val initial = TaskExecution(
            taskId = task.effective.id,
            executionId = executionId,
            trigger = trigger,
            status = TaskStatus.PREPARING,
            startedAt = Instant.now(clock),
            sourceScope = task.effective.scope,
        )
        registry.begin(initial).onFailure { return Result.failure(it) }

        return try {
            runReserved(task, context, initial)
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                transitionAfterCancellation(initial)
            }
            throw cancellation
        } catch (_: Exception) {
            fail(initial, FailureCategory.EXECUTION, "Task execution failed")
        }
    }

    private suspend fun runReserved(
        task: ResolvedTask,
        context: PreparationContext,
        execution: TaskExecution,
    ): Result<TaskExecution> {
        val prepared = when (val result = prepareTask(task, context, execution.executionId)) {
            is PreparationResult.Success -> result.task
            is PreparationResult.Failure -> return registry.transition(
                taskId = execution.taskId,
                expectedExecutionId = execution.executionId,
                status = TaskStatus.FAILED,
                failure = result.failure,
            )
        }

        registry.transition(
            execution.taskId,
            execution.executionId,
            TaskStatus.WAITING_FOR_TERMINAL,
        ).onFailure { return Result.failure(it) }

        val session = try {
            acquireSession(prepared)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return fail(execution, FailureCategory.TERMINAL, "Terminal session could not be created")
        }
        return sessionLocks.computeIfAbsent(session.id) { Mutex() }.withLock {
            runInOwnedSession(execution, prepared, session)
        }
    }

    private suspend fun runInOwnedSession(
        execution: TaskExecution,
        prepared: PreparedTask,
        session: TerminalSession,
    ): Result<TaskExecution> {
        val shellIntegrationReady = session.awaitReady(SHELL_INTEGRATION_TIMEOUT_MILLIS)

        registry.transition(
            execution.taskId,
            execution.executionId,
            TaskStatus.RUNNING,
        ).onFailure { return Result.failure(it) }

        if (!prepared.exitDetection) {
            return try {
                session.execute(prepared.command)
                transitionToUnknown(execution, "Exit detection is disabled")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                fail(execution, FailureCategory.TERMINAL, "Command could not be sent to Terminal")
            }
        }

        val completion = try {
            executeAndAwaitCompletion(session, prepared, shellIntegrationReady)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return fail(execution, FailureCategory.TERMINAL, "Terminal command execution failed")
        }
        return when (completion) {
            is Completion.Exited -> completeWithExit(execution, completion.code)
            Completion.Lost -> transitionToUnknown(execution, "Terminal exit status was lost")
        }
    }

    private suspend fun executeAndAwaitCompletion(
        session: TerminalSession,
        prepared: PreparedTask,
        shellIntegrationReady: Boolean,
    ): Completion = coroutineScope {
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            awaitCompletion(session, prepared.executionId, shellIntegrationReady)
        }
        try {
            session.execute(prepared.command)
        } catch (failure: Exception) {
            completion.cancel()
            throw failure
        }
        completion.await()
    }

    private suspend fun awaitCompletion(
        session: TerminalSession,
        executionId: String,
        shellIntegrationReady: Boolean,
    ): Completion {
        val parser = TerminalOutputMarkerParser(executionId)
        var began = false
        var markerExitCode: Int? = null
        var nativeExitUnavailable = false
        var completion: Completion? = null

        session.events.firstOrNull { event ->
            when (event.state) {
                TerminalCommandState.FINISHED -> {
                    if (!began) {
                        Unit
                    } else if (event.exitCode != null) {
                        completion = Completion.Exited(event.exitCode)
                    } else {
                        nativeExitUnavailable = true
                        markerExitCode?.let { completion = Completion.Exited(it) }
                    }
                }

                TerminalCommandState.OUTPUT_CHANGED -> {
                    event.outputText?.let { chunk ->
                        parser.accept(chunk).forEach { marker ->
                            when (marker) {
                                MarkerEvent.Began -> began = true
                                is MarkerEvent.Exited -> if (began) markerExitCode = marker.code
                            }
                        }
                    }
                    if (!shellIntegrationReady || nativeExitUnavailable) {
                        markerExitCode?.let { completion = Completion.Exited(it) }
                    }
                }

                TerminalCommandState.SESSION_CLOSED -> completion = Completion.Lost
                TerminalCommandState.STARTED -> Unit
            }
            completion != null
        }
        return completion ?: Completion.Lost
    }

    private suspend fun completeWithExit(
        execution: TaskExecution,
        exitCode: Int,
    ): Result<TaskExecution> {
        finishStopIfRequested(execution)?.let { return it }
        val result = if (exitCode == 0) {
            registry.transition(
                execution.taskId,
                execution.executionId,
                TaskStatus.SUCCEEDED,
                exitCode = exitCode,
            )
        } else {
            registry.transition(
                execution.taskId,
                execution.executionId,
                TaskStatus.FAILED,
                exitCode = exitCode,
                failure = TaskFailure(
                    category = FailureCategory.EXECUTION,
                    userMessage = "Task exited with a non-zero status",
                    technicalMessage = "Task exited with code $exitCode",
                ),
            )
        }
        return finishStopIfRequested(execution) ?: result
    }

    private suspend fun fail(
        execution: TaskExecution,
        category: FailureCategory,
        message: String,
    ): Result<TaskExecution> {
        finishStopIfRequested(execution)?.let { return it }
        val result = registry.transition(
            execution.taskId,
            execution.executionId,
            TaskStatus.FAILED,
            failure = TaskFailure(category, message, message),
        )
        return finishStopIfRequested(execution) ?: result
    }

    private suspend fun transitionToUnknown(
        execution: TaskExecution,
        message: String,
    ): Result<TaskExecution> {
        finishStopIfRequested(execution)?.let { return it }
        val result = registry.transition(
            execution.taskId,
            execution.executionId,
            TaskStatus.UNKNOWN,
            failure = TaskFailure(FailureCategory.STATUS_LOST, message, message),
        )
        return finishStopIfRequested(execution) ?: result
    }

    private suspend fun finishStopIfRequested(execution: TaskExecution): Result<TaskExecution>? {
        val current = registry.executions.value[execution.taskId]
        if (current?.executionId != execution.executionId) return null
        if (current.status == TaskStatus.STOPPED) return Result.success(current)
        if (current.status != TaskStatus.STOPPING) return null
        val transitioned = registry.transition(execution.taskId, execution.executionId, TaskStatus.STOPPED)
        if (transitioned.isSuccess) return transitioned
        val latest = registry.executions.value[execution.taskId]
        return latest?.takeIf {
            it.executionId == execution.executionId && it.status == TaskStatus.STOPPED
        }?.let(Result.Companion::success) ?: transitioned
    }

    private suspend fun transitionAfterCancellation(execution: TaskExecution) {
        val active = registry.active(execution.taskId) ?: return
        val status = when (active.status) {
            TaskStatus.PREPARING, TaskStatus.WAITING_FOR_TERMINAL -> TaskStatus.STOPPED
            TaskStatus.RUNNING, TaskStatus.STOPPING -> TaskStatus.UNKNOWN
            else -> return
        }
        registry.transition(
            execution.taskId,
            execution.executionId,
            status,
            failure = TaskFailure(
                FailureCategory.STATUS_LOST,
                "Task execution was interrupted",
                "Task execution was interrupted",
            ),
        )
    }

    private sealed interface Completion {
        data class Exited(val code: Int) : Completion

        data object Lost : Completion
    }

    private companion object {
        const val SHELL_INTEGRATION_TIMEOUT_MILLIS = 10_000L
    }
}

@Service(Service.Level.PROJECT)
class ProjectTaskRunner(
    private val project: Project,
    scope: CoroutineScope,
) : Disposable {
    private val registry: TaskExecutionRegistry = InMemoryTaskExecutionRegistry()
    private val preparationService = TaskPreparationService(PasswordSafeSecretStore())
    private val sessionManager = TerminalSessionManager(
        gateway = project.getService(TerminalGateway::class.java),
        scope = scope,
    )
    private val runner = TaskRunner(
        registry = registry,
        prepareTask = preparationService::prepare,
        acquireSession = { task -> sessionManager.acquire(project.locationHash, task) },
    )
    private val stopService = TaskStopService(registry, sessionManager)

    val executions: StateFlow<Map<String, TaskExecution>> = registry.executions

    suspend fun run(
        task: ResolvedTask,
        trigger: RunTrigger,
        context: PreparationContext,
    ): Result<TaskExecution> = runner.run(task, trigger, context)

    fun active(taskId: String): TaskExecution? = registry.active(taskId)

    suspend fun stop(taskId: String): StopResult = stopService.stop(taskId)

    suspend fun forceClose(taskId: String): Result<Unit> = stopService.forceClose(taskId)

    suspend fun ownsTerminal(taskId: String, executionId: String): Boolean =
        sessionManager.withSession(taskId, executionId) { true } == true

    suspend fun activateTerminal(taskId: String, executionId: String): Boolean =
        sessionManager.withSession(taskId, executionId) { session ->
            session.activate()
            true
        } == true

    override fun dispose() = Unit
}

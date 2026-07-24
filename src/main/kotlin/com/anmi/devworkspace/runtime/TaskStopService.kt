package com.anmi.devworkspace.runtime

import com.anmi.devworkspace.domain.TaskExecution
import com.anmi.devworkspace.domain.TaskStatus
import com.anmi.devworkspace.terminal.MarkerEvent
import com.anmi.devworkspace.terminal.TerminalCommandState
import com.anmi.devworkspace.terminal.TerminalOutputMarkerParser
import com.anmi.devworkspace.terminal.TerminalSession
import com.anmi.devworkspace.terminal.TerminalSessionLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

sealed interface StopResult {
    data object Stopped : StopResult
    data object NotRunning : StopResult
    data class ForceCloseRequired(
        val taskId: String,
        val executionId: String,
        val terminalTitle: String,
    ) : StopResult
}

class TaskStopService(
    private val registry: TaskExecutionRegistry,
    private val sessions: TerminalSessionLookup,
) {
    private val forceCloseAuthorizations = ConcurrentHashMap<ExecutionKey, AuthorizationState>()

    suspend fun stop(taskId: String, timeoutMillis: Long = 5_000): StopResult = try {
        stopInternal(taskId, timeoutMillis)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        StopResult.NotRunning
    }

    private suspend fun stopInternal(taskId: String, timeoutMillis: Long): StopResult {
        val execution = registry.active(taskId) ?: return StopResult.NotRunning
        if (execution.status !in stoppableStatuses) return StopResult.NotRunning
        if (execution.status == TaskStatus.PREPARING) {
            registry.transition(taskId, execution.executionId, TaskStatus.STOPPED)
            return stoppedIfCurrentEnded(execution)
        }

        return try {
            val sendsInterrupt = when (execution.status) {
                TaskStatus.WAITING_FOR_TERMINAL, TaskStatus.RUNNING -> {
                    val transition = registry.transition(taskId, execution.executionId, TaskStatus.STOPPING)
                    if (transition.isSuccess) true else {
                        val current = registry.active(taskId)
                        if (current?.executionId != execution.executionId) return StopResult.NotRunning
                        current.status != TaskStatus.STOPPING
                    }
                }
                TaskStatus.STOPPING -> false
                else -> return StopResult.NotRunning
            }
            coroutineScope {
                val context = try {
                    sessions.withSession(taskId, execution.executionId) { session ->
                        val stopped = async(start = CoroutineStart.UNDISPATCHED) {
                            awaitStopSignal(execution, session)
                        }
                        var interruptFailed = false
                        if (sendsInterrupt) {
                            try {
                                session.sendInterrupt()
                            } catch (cancellation: CancellationException) {
                                stopped.cancel()
                                throw cancellation
                            } catch (_: Exception) {
                                interruptFailed = true
                            }
                        }
                        StopContext(stopped, interruptFailed)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    return@coroutineScope authorizeForceClose(execution)
                }
                if (context == null) {
                    return@coroutineScope authorizeForceClose(execution)
                }
                try {
                    if (context.interruptFailed) {
                        return@coroutineScope authorizeForceClose(execution)
                    }
                    val completed = withTimeoutOrNull(timeoutMillis.coerceAtLeast(0)) { context.stopped.await() }
                    if (completed == null) {
                        context.stopped.cancel()
                        authorizeForceClose(execution)
                    } else {
                        transitionStopped(execution)
                        forceCloseAuthorizations.remove(execution.key())
                        StopResult.Stopped
                    }
                } finally {
                    context.stopped.cancel()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            authorizeForceClose(execution)
        }
    }

    suspend fun forceClose(taskId: String): Result<Unit> = try {
        forceCloseInternal(taskId)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        sanitizedFailure("Terminal session could not be closed")
    }

    private suspend fun forceCloseInternal(taskId: String): Result<Unit> {
        val currentRecord = registry.executions.value[taskId]
        if (currentRecord?.status == TaskStatus.STOPPED) return Result.success(Unit)
        val execution = registry.active(taskId) ?: return sanitizedFailure("Task is not running")
        if (execution.status != TaskStatus.STOPPING) {
            return sanitizedFailure("Task is not awaiting force close confirmation")
        }
        val key = execution.key()
        if (!forceCloseAuthorizations.replace(
                key,
                AuthorizationState.AUTHORIZED,
                AuthorizationState.IN_PROGRESS,
            )
        ) {
            return sanitizedFailure("Task is not awaiting force close confirmation")
        }
        return try {
            val closed = sessions.withSession(taskId, execution.executionId) { session ->
                session.close()
                Unit
            }
            if (closed == null) {
                forceCloseAuthorizations.remove(key, AuthorizationState.IN_PROGRESS)
                transitionStopped(execution)
                return stoppedResult(execution)
            }
            forceCloseAuthorizations.remove(key, AuthorizationState.IN_PROGRESS)
            transitionStopped(execution)
            stoppedResult(execution)
        } catch (cancellation: CancellationException) {
            restoreAuthorization(key)
            throw cancellation
        } catch (_: Exception) {
            restoreAuthorization(key)
            sanitizedFailure("Terminal session could not be closed")
        }
    }

    private fun stoppedResult(execution: TaskExecution): Result<Unit> {
        val current = registry.executions.value[execution.taskId]
        return if (current?.executionId == execution.executionId && current.status == TaskStatus.STOPPED) {
            Result.success(Unit)
        } else {
            sanitizedFailure("Task execution is no longer current")
        }
    }

    private suspend fun awaitStopSignal(execution: TaskExecution, session: TerminalSession) = coroutineScope {
        val parser = TerminalOutputMarkerParser(execution.executionId)
        var began = false
        val registryCompletion = async(start = CoroutineStart.UNDISPATCHED) {
            registry.executions.first { executions ->
                val current = executions[execution.taskId]
                current?.executionId != execution.executionId || current.status.isTerminal()
            }
        }
        val terminalCompletion = async(start = CoroutineStart.UNDISPATCHED) {
            session.events.first { event ->
                when (event.state) {
                    TerminalCommandState.SESSION_CLOSED -> true
                    TerminalCommandState.FINISHED ->
                        began && (event.executionId == null || event.executionId == execution.executionId)
                    TerminalCommandState.OUTPUT_CHANGED -> {
                        event.outputText?.let(parser::accept).orEmpty().any { marker ->
                            when (marker) {
                                MarkerEvent.Began -> {
                                    began = true
                                    false
                                }
                                is MarkerEvent.Exited -> began
                            }
                        }
                    }
                    TerminalCommandState.STARTED -> false
                }
            }
        }
        try {
            select {
                registryCompletion.onAwait { }
                terminalCompletion.onAwait { }
            }
        } finally {
            registryCompletion.cancel()
            terminalCompletion.cancel()
        }
    }

    private suspend fun transitionStopped(execution: TaskExecution) {
        val current = registry.active(execution.taskId) ?: return
        if (current.executionId != execution.executionId) return
        if (current.status == TaskStatus.PREPARING || current.status == TaskStatus.STOPPING) {
            registry.transition(execution.taskId, execution.executionId, TaskStatus.STOPPED)
        }
    }

    private fun stoppedIfCurrentEnded(execution: TaskExecution): StopResult {
        val current = registry.executions.value[execution.taskId]
        return if (current?.executionId == execution.executionId && current.status == TaskStatus.STOPPED) {
            StopResult.Stopped
        } else {
            StopResult.NotRunning
        }
    }

    private fun forceCloseRequired(execution: TaskExecution) =
        StopResult.ForceCloseRequired(execution.taskId, execution.executionId, SAFE_TERMINAL_TITLE)

    private fun authorizeForceClose(execution: TaskExecution): StopResult {
        val current = registry.active(execution.taskId)
        if (current?.executionId != execution.executionId || current.status != TaskStatus.STOPPING) {
            return StopResult.NotRunning
        }
        val key = execution.key()
        forceCloseAuthorizations.putIfAbsent(key, AuthorizationState.AUTHORIZED)
        val confirmed = registry.active(execution.taskId)
        if (confirmed?.executionId != execution.executionId || confirmed.status != TaskStatus.STOPPING) {
            forceCloseAuthorizations.remove(key, AuthorizationState.AUTHORIZED)
            return StopResult.NotRunning
        }
        return forceCloseRequired(execution)
    }

    private fun restoreAuthorization(key: ExecutionKey) {
        try {
            forceCloseAuthorizations.replace(
                key,
                AuthorizationState.IN_PROGRESS,
                AuthorizationState.AUTHORIZED,
            )
        } catch (_: Exception) {
            // Best-effort restoration must not replace the original failure or cancellation.
        }
    }

    private fun sanitizedFailure(message: String): Result<Unit> = Result.failure(IllegalStateException(message))

    private fun TaskStatus.isTerminal() = this in terminalStatuses

    private data class StopContext(
        val stopped: Deferred<Unit>,
        val interruptFailed: Boolean,
    )

    private data class ExecutionKey(val taskId: String, val executionId: String)

    private enum class AuthorizationState { AUTHORIZED, IN_PROGRESS }

    private fun TaskExecution.key() = ExecutionKey(taskId, executionId)

    private companion object {
        const val SAFE_TERMINAL_TITLE = "Task terminal"
        val stoppableStatuses = setOf(
            TaskStatus.PREPARING,
            TaskStatus.WAITING_FOR_TERMINAL,
            TaskStatus.RUNNING,
            TaskStatus.STOPPING,
        )
        val terminalStatuses = setOf(
            TaskStatus.SUCCEEDED,
            TaskStatus.FAILED,
            TaskStatus.STOPPED,
            TaskStatus.UNKNOWN,
            TaskStatus.INTERRUPTED,
        )
    }
}

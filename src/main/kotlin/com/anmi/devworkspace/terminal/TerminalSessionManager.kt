package com.anmi.devworkspace.terminal

import com.anmi.devworkspace.domain.TerminalPolicy
import com.anmi.devworkspace.prepare.PreparedTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class TerminalSessionManager internal constructor(
    private val gateway: TerminalGateway,
    private val scope: CoroutineScope,
) : TerminalSessionLookup {
    constructor(gateway: TerminalGateway) : this(
        gateway,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private val taskSessions = ConcurrentHashMap<TerminalSessionKey, TerminalSession>()
    private val sharedSessions = ConcurrentHashMap<String, TerminalSession>()
    private val alwaysNewSequences = ConcurrentHashMap<TerminalSessionKey, Int>()
    private val executionSessions = ConcurrentHashMap<String, OwnedSession>()
    private val acquisitionMutex = Mutex()

    suspend fun acquire(
        projectKey: String,
        task: PreparedTask,
    ): TerminalSession = acquisitionMutex.withLock {
        val session = when (task.terminalPolicy) {
            TerminalPolicy.REUSE_TASK_TERMINAL -> acquireTaskSession(projectKey, task)
            TerminalPolicy.REUSE_SHARED -> acquireSharedSession(projectKey, task)
            TerminalPolicy.ALWAYS_NEW -> acquireNewSession(projectKey, task)
        }
        executionSessions[task.taskId] = OwnedSession(task.executionId, session)
        session
    }

    override suspend fun <T> withSession(
        taskId: String,
        executionId: String,
        action: suspend (TerminalSession) -> T,
    ): T? = acquisitionMutex.withLock {
        val owned = executionSessions[taskId]?.takeIf { it.executionId == executionId } ?: return@withLock null
        action(owned.session)
    }

    private suspend fun acquireTaskSession(
        projectKey: String,
        task: PreparedTask,
    ): TerminalSession {
        val key = TerminalSessionKey(projectKey, task.taskId)
        return taskSessions[key] ?: gateway.acquire(key, task, task.terminalPolicy).also { session ->
            taskSessions[key] = session
            removeWhenClosed(session) {
                taskSessions.remove(key, session)
                removeExecutionSessions(session)
            }
        }
    }

    private suspend fun acquireSharedSession(
        projectKey: String,
        task: PreparedTask,
    ): TerminalSession = sharedSessions[projectKey]
        ?: gateway.acquire(
            TerminalSessionKey(projectKey, SHARED_TASK_ID),
            task.copy(displayName = SHARED_TERMINAL_TITLE),
            task.terminalPolicy,
        ).also { session ->
            sharedSessions[projectKey] = session
            removeWhenClosed(session) {
                sharedSessions.remove(projectKey, session)
                removeExecutionSessions(session)
            }
        }

    private suspend fun acquireNewSession(
        projectKey: String,
        task: PreparedTask,
    ): TerminalSession {
        val key = TerminalSessionKey(projectKey, task.taskId)
        val sequence = (alwaysNewSequences[key] ?: 0) + 1
        alwaysNewSequences[key] = sequence
        val titledTask = if (sequence == 1) task else task.copy(displayName = "${task.displayName} ($sequence)")
        return gateway.acquire(key, titledTask, task.terminalPolicy).also { session ->
            removeWhenClosed(session) { removeExecutionSessions(session) }
        }
    }

    private fun removeWhenClosed(session: TerminalSession, remove: () -> Unit) {
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            session.events.first { it.state == TerminalCommandState.SESSION_CLOSED }
            remove()
        }
    }

    private fun removeExecutionSessions(session: TerminalSession) {
        executionSessions.entries
            .filter { it.value.session === session }
            .forEach { (taskId, ownership) -> executionSessions.remove(taskId, ownership) }
    }

    private data class OwnedSession(val executionId: String, val session: TerminalSession)

    private companion object {
        const val SHARED_TASK_ID = "__shared__"
        const val SHARED_TERMINAL_TITLE = "Dev Tasks"
    }
}

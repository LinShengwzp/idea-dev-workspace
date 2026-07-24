package com.anmi.devworkspace.terminal

import com.anmi.devworkspace.domain.TerminalPolicy
import com.anmi.devworkspace.prepare.PreparedTask
import com.anmi.devworkspace.prepare.ShellType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

class TerminalLaunchSignature private constructor(
    val shellType: ShellType,
    val normalizedWorkingDirectory: Path,
    private val environmentDigest: String,
) {
    override fun equals(other: Any?): Boolean =
        other is TerminalLaunchSignature &&
            shellType == other.shellType &&
            normalizedWorkingDirectory == other.normalizedWorkingDirectory &&
            environmentDigest == other.environmentDigest

    override fun hashCode(): Int {
        var result = shellType.hashCode()
        result = 31 * result + normalizedWorkingDirectory.hashCode()
        result = 31 * result + environmentDigest.hashCode()
        return result
    }

    override fun toString(): String =
        "TerminalLaunchSignature(shellType=$shellType, normalizedWorkingDirectory=$normalizedWorkingDirectory, environmentDigest=<redacted>)"

    companion object {
        fun from(task: PreparedTask): TerminalLaunchSignature = TerminalLaunchSignature(
            shellType = task.shellType,
            normalizedWorkingDirectory = task.workingDirectory.toAbsolutePath().normalize(),
            environmentDigest = digest(task.environment),
        )

        private fun digest(environment: Map<String, String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            environment.entries.sortedBy { it.key }.forEach { (key, value) ->
                digest.updateLengthPrefixed(key)
                digest.updateLengthPrefixed(value)
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        private fun MessageDigest.updateLengthPrefixed(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            update(bytes)
        }
    }
}

class TerminalSessionManager internal constructor(
    private val gateway: TerminalGateway,
    private val scope: CoroutineScope,
) : TerminalSessionLookup {
    constructor(gateway: TerminalGateway) : this(
        gateway,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private val taskSessions = ConcurrentHashMap<TerminalSessionKey, CachedSession>()
    private val sharedSessions = ConcurrentHashMap<String, CachedSession>()
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
        if (owned.session.isClosed) {
            removeExecutionSessions(owned.session)
            removeReusableSession(owned.session)
            return@withLock null
        }
        action(owned.session)
    }

    private suspend fun acquireTaskSession(
        projectKey: String,
        task: PreparedTask,
    ): TerminalSession {
        val key = TerminalSessionKey(projectKey, task.taskId)
        val signature = TerminalLaunchSignature.from(task)
        taskSessions[key]
            ?.takeIf { cached -> !cached.session.isClosed && cached.signature == signature }
            ?.let { return it.session }
        taskSessions[key]?.let { stale ->
            taskSessions.remove(key, stale)
            removeExecutionSessions(stale.session)
        }
        return gateway.acquire(key, task, task.terminalPolicy).also { session ->
            val cached = CachedSession(signature, session)
            taskSessions[key] = cached
            removeWhenClosed(session) {
                taskSessions.remove(key, cached)
                removeExecutionSessions(session)
            }
        }
    }

    private suspend fun acquireSharedSession(
        projectKey: String,
        task: PreparedTask,
    ): TerminalSession {
        val signature = TerminalLaunchSignature.from(task)
        sharedSessions[projectKey]
            ?.takeIf { cached -> !cached.session.isClosed && cached.signature == signature }
            ?.let { return it.session }
        sharedSessions[projectKey]?.let { stale ->
            sharedSessions.remove(projectKey, stale)
            removeExecutionSessions(stale.session)
        }
        return gateway.acquire(
            TerminalSessionKey(projectKey, SHARED_TASK_ID),
            task.copy(displayName = SHARED_TERMINAL_TITLE),
            task.terminalPolicy,
        ).also { session ->
            val cached = CachedSession(signature, session)
            sharedSessions[projectKey] = cached
            removeWhenClosed(session) {
                sharedSessions.remove(projectKey, cached)
                removeExecutionSessions(session)
            }
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

    private fun removeReusableSession(session: TerminalSession) {
        taskSessions.entries
            .filter { it.value.session === session }
            .forEach { (key, value) -> taskSessions.remove(key, value) }
        sharedSessions.entries
            .filter { it.value.session === session }
            .forEach { (key, value) -> sharedSessions.remove(key, value) }
    }

    private data class CachedSession(
        val signature: TerminalLaunchSignature,
        val session: TerminalSession,
    )

    private data class OwnedSession(val executionId: String, val session: TerminalSession)

    private companion object {
        const val SHARED_TASK_ID = "__shared__"
        const val SHARED_TERMINAL_TITLE = "Dev Tasks"
    }
}

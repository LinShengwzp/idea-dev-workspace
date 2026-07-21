package com.anmi.devworkspace.runtime

import com.anmi.devworkspace.domain.TaskExecution
import com.anmi.devworkspace.domain.TaskFailure
import com.anmi.devworkspace.domain.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant

interface TaskExecutionRegistry {
    val executions: StateFlow<Map<String, TaskExecution>>

    suspend fun begin(execution: TaskExecution): Result<Unit>

    suspend fun transition(
        taskId: String,
        expectedExecutionId: String,
        status: TaskStatus,
        exitCode: Int? = null,
        failure: TaskFailure? = null,
    ): Result<TaskExecution>

    fun active(taskId: String): TaskExecution?
}

class InMemoryTaskExecutionRegistry(
    private val clock: Clock = Clock.systemUTC(),
) : TaskExecutionRegistry {
    private val mutex = Mutex()
    private val mutableExecutions = MutableStateFlow<Map<String, TaskExecution>>(emptyMap())

    override val executions: StateFlow<Map<String, TaskExecution>> = mutableExecutions.asStateFlow()

    override suspend fun begin(execution: TaskExecution): Result<Unit> = mutex.withLock {
        if (execution.status != TaskStatus.PREPARING) {
            return@withLock Result.failure(
                IllegalArgumentException("A new task execution must start in PREPARING"),
            )
        }
        if (active(execution.taskId) != null) {
            return@withLock Result.failure(
                IllegalStateException("Task '${execution.taskId}' already has an active execution"),
            )
        }

        mutableExecutions.value = mutableExecutions.value + (execution.taskId to execution)
        Result.success(Unit)
    }

    override suspend fun transition(
        taskId: String,
        expectedExecutionId: String,
        status: TaskStatus,
        exitCode: Int?,
        failure: TaskFailure?,
    ): Result<TaskExecution> = mutex.withLock {
        val current = mutableExecutions.value[taskId]
            ?: return@withLock Result.failure(IllegalStateException("Task '$taskId' has no execution"))
        if (current.executionId != expectedExecutionId) {
            return@withLock Result.failure(IllegalStateException("Task '$taskId' execution is no longer current"))
        }
        if (!current.status.canTransitionTo(status)) {
            return@withLock Result.failure(
                IllegalStateException("Task '$taskId' cannot transition from ${current.status} to $status"),
            )
        }

        val updated = current.copy(
            status = status,
            endedAt = if (status.isTerminal()) Instant.now(clock) else null,
            exitCode = exitCode,
            failure = failure,
        )
        mutableExecutions.value = mutableExecutions.value + (taskId to updated)
        Result.success(updated)
    }

    override fun active(taskId: String): TaskExecution? =
        mutableExecutions.value[taskId]?.takeUnless { it.status.isTerminal() }
}

private fun TaskStatus.isTerminal(): Boolean = this in setOf(
    TaskStatus.SUCCEEDED,
    TaskStatus.FAILED,
    TaskStatus.STOPPED,
    TaskStatus.UNKNOWN,
    TaskStatus.INTERRUPTED,
)

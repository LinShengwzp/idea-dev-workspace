package com.anmi.devworkspace.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.config.TaskConfigError
import com.anmi.devworkspace.config.TaskConfigurationState
import com.anmi.devworkspace.domain.ResolvedTask
import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.TaskExecution
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskStatus
import com.anmi.devworkspace.domain.TaskSource
import kotlinx.coroutines.CancellationException

data class TaskListItem(
    val task: ResolvedTask,
    val status: TaskStatus,
    val statusText: String,
    val failureText: String?,
) {
    val displayName: String get() = task.effective.name?.takeIf(String::isNotBlank) ?: task.effective.id
    val autoText: String get() = task.effective.autoStart?.let {
        DevWorkspaceBundle.message("task.list.auto", it.order)
    } ?: DevWorkspaceBundle.message("task.list.manual")
    val scopeText: String get() = when (task.effective.scope) {
        TaskScope.GLOBAL -> DevWorkspaceBundle.message("task.list.scope.global")
        TaskScope.PROJECT_SHARED -> DevWorkspaceBundle.message("task.list.scope.project.shared")
        TaskScope.PROJECT_PRIVATE -> DevWorkspaceBundle.message("task.list.scope.project.private")
    }
    val favoriteText: String? get() = task.effective.favoriteSlot?.let {
        DevWorkspaceBundle.message("task.list.favorite", it)
    }
    val hasOverrides: Boolean get() = task.shadowed.isNotEmpty()

    fun actionState(ownsActiveTerminal: Boolean): TaskActionState = TaskActionState(
        run = status !in ACTIVE_STATUSES,
        openTerminal = ownsActiveTerminal,
    )

    private companion object {
        val ACTIVE_STATUSES = setOf(
            TaskStatus.PREPARING,
            TaskStatus.WAITING_FOR_TERMINAL,
            TaskStatus.RUNNING,
            TaskStatus.STOPPING,
        )
    }
}

data class TaskActionState(
    val run: Boolean,
    val openTerminal: Boolean,
)

object TaskListModel {
    fun items(
        tasks: List<ResolvedTask>,
        executions: Map<String, TaskExecution>,
        statusText: (TaskStatus) -> String,
    ): List<TaskListItem> = tasks
        .map { resolved ->
            val execution = executions[resolved.effective.id]
            val status = execution?.status ?: TaskStatus.IDLE
            TaskListItem(
                task = resolved,
                status = status,
                statusText = statusText(status),
                failureText = execution?.failure?.userMessage,
            )
        }
        .sortedWith(
            compareBy<TaskListItem> { it.task.effective.autoStart == null }
                .thenBy { it.task.effective.autoStart?.order ?: Int.MAX_VALUE }
                .thenBy { it.task.effective.id },
        )

    fun filter(items: List<TaskListItem>, query: String): List<TaskListItem> {
        val needle = query.trim()
        if (needle.isEmpty()) return items.toList()
        return items.filter { item ->
            item.displayName.contains(needle, ignoreCase = true) ||
                item.task.effective.id.contains(needle, ignoreCase = true)
        }
    }

}

val TaskStatus.resourceKey: String
    get() = when (this) {
        TaskStatus.IDLE -> "task.status.idle"
        TaskStatus.PREPARING -> "task.status.preparing"
        TaskStatus.WAITING_FOR_TERMINAL -> "task.status.waiting"
        TaskStatus.RUNNING -> "task.status.running"
        TaskStatus.SUCCEEDED -> "task.status.succeeded"
        TaskStatus.FAILED -> "task.status.failed"
        TaskStatus.STOPPING -> "task.status.stopping"
        TaskStatus.STOPPED -> "task.status.stopped"
        TaskStatus.UNKNOWN -> "task.status.unknown"
        TaskStatus.INTERRUPTED -> "task.status.interrupted"
    }

data class TaskListPresentation(
    val items: List<TaskListItem>,
    val errors: List<TaskConfigError>,
)

class TaskListPresentationState(
    private val statusText: (TaskStatus) -> String,
) {
    private var lastValidTasks: List<ResolvedTask> = emptyList()

    fun update(
        configuration: TaskConfigurationState,
        executions: Map<String, TaskExecution>,
    ): TaskListPresentation {
        if (configuration.errors.isEmpty() || configuration.tasks.isNotEmpty()) {
            lastValidTasks = configuration.tasks.toList()
        }
        val tasks = if (configuration.errors.isNotEmpty() && configuration.tasks.isEmpty()) {
            lastValidTasks
        } else {
            configuration.tasks
        }
        return TaskListPresentation(
            items = TaskListModel.items(tasks, executions, statusText),
            errors = TaskScope.entries.flatMap { configuration.errors[it].orEmpty() },
        )
    }
}

object TaskExample {
    fun create(scope: TaskScope): DevTask = DevTask(
        id = "example",
        name = DevWorkspaceBundle.message("task.example.name"),
        description = DevWorkspaceBundle.message("task.example.description"),
        scope = scope,
        source = TaskSource.InlineCommand("echo Dev Tasks example"),
    )

    fun expectedHash(state: TaskConfigurationState, scope: TaskScope): String? =
        state.snapshots[scope]?.contentHash?.takeIf { state.errors[scope].isNullOrEmpty() }
}

class TaskInteractionCoordinator(
    private val inFlightChanged: (String) -> Unit = {},
) {
    private val lock = Any()
    private val inFlightTaskIds = mutableSetOf<String>()

    fun isInFlight(taskId: String): Boolean = synchronized(lock) { taskId in inFlightTaskIds }

    fun canRun(item: TaskListItem, currentExecutionId: String?): Boolean =
        currentExecutionId == null && !isInFlight(item.task.effective.id)

    fun launchRun(
        item: TaskListItem,
        currentExecutionId: String?,
        launcher: ((suspend () -> Unit) -> Unit),
        run: suspend () -> Unit,
    ): Boolean {
        if (currentExecutionId != null) return false
        val taskId = item.task.effective.id
        synchronized(lock) {
            if (!inFlightTaskIds.add(taskId)) return false
        }
        inFlightChanged(taskId)
        launcher {
            try {
                run()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Operational callers surface only their fixed, sanitized messages.
            } finally {
                synchronized(lock) { inFlightTaskIds.remove(taskId) }
                inFlightChanged(taskId)
            }
        }
        return true
    }

    suspend fun handleDoubleClick(
        item: TaskListItem,
        exactExecutionId: String?,
        canRunInactive: Boolean = false,
        ownsTerminal: suspend (String, String) -> Boolean,
        activateTerminal: suspend (String, String) -> Unit,
        runInactive: () -> Unit,
    ) {
        val taskId = item.task.effective.id
        if (exactExecutionId != null) {
            val executionId = exactExecutionId
            if (activateExactIfOwned(taskId, executionId, ownsTerminal, activateTerminal)) return
        }
        if (canRunInactive) runInactive()
    }

    suspend fun activateExactIfOwned(
        taskId: String,
        executionId: String,
        ownsTerminal: suspend (String, String) -> Boolean,
        activateTerminal: suspend (String, String) -> Unit,
    ): Boolean {
        if (!ownsTerminal(taskId, executionId)) return false
        activateTerminal(taskId, executionId)
        return true
    }

}

data class TaskBannerMessages(
    val configError: TaskConfigError?,
    val operationalMessage: String?,
)

class TaskBannerState {
    private var configError: TaskConfigError? = null
    private var operationalMessage: String? = null

    fun updateConfig(errors: List<TaskConfigError>) {
        configError = errors.firstOrNull()
    }

    fun showOperational(message: String) {
        operationalMessage = message
    }

    fun clearOperational() {
        operationalMessage = null
    }

    fun messages(): TaskBannerMessages = TaskBannerMessages(configError, operationalMessage)
}

package com.anmi.devworkspace.ui

import com.anmi.devworkspace.config.TaskConfigError
import com.anmi.devworkspace.config.TaskConfigurationState
import com.anmi.devworkspace.config.TaskTomlParser
import com.anmi.devworkspace.config.TaskTomlWriter
import com.anmi.devworkspace.config.TaskConfigLoadResult
import com.anmi.devworkspace.config.TaskConfigSnapshot
import com.anmi.devworkspace.domain.AutoStartConfig
import com.anmi.devworkspace.domain.AutoStartTrigger
import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.FailureCategory
import com.anmi.devworkspace.domain.ResolvedTask
import com.anmi.devworkspace.domain.RunTrigger
import com.anmi.devworkspace.domain.TaskExecution
import com.anmi.devworkspace.domain.TaskFailure
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskSource
import com.anmi.devworkspace.domain.TaskStatus
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class TaskListModelTest {
    @Test
    fun `merges current execution status and only sanitized user failure`() {
        val task = task("api")
        val execution = execution(
            "api",
            TaskStatus.FAILED,
            TaskFailure(FailureCategory.EXECUTION, "Safe failure", "token=secret"),
        )

        val item = items(listOf(task), mapOf("api" to execution)).single()

        assertEquals(TaskStatus.FAILED, item.status)
        assertEquals("Failed", item.statusText)
        assertEquals("Safe failure", item.failureText)
        assertFalse(item.toString().contains("token=secret"))
    }

    @Test
    fun `defaults tasks without executions to idle`() {
        val item = items(listOf(task("api")), emptyMap()).single()

        assertEquals(TaskStatus.IDLE, item.status)
        assertEquals("Idle", item.statusText)
        assertNull(item.failureText)
    }

    @Test
    fun `sorts ordered auto starts first then manual tasks by stable id`() {
        val tasks = listOf(
            task("z-manual"),
            task("b-auto", order = 20),
            task("a-manual"),
            task("c-auto", order = 10),
            task("a-auto", order = 20),
        )

        assertEquals(
            listOf("c-auto", "a-auto", "b-auto", "a-manual", "z-manual"),
            items(tasks, emptyMap()).map { it.task.effective.id },
        )
    }

    @Test
    fun `search is case insensitive presentation only`() {
        val source = listOf(task("api", name = "Backend API"), task("web", name = "Frontend"))
        val items = items(source, emptyMap())

        assertEquals(listOf("api"), TaskListModel.filter(items, "bAcKeNd").map { it.task.effective.id })
        assertEquals(listOf("api", "web"), source.map { it.effective.id })
        assertEquals(2, items.size)
    }

    @Test
    fun `action state follows runner stop and exact terminal ownership rules`() {
        val idle = items(listOf(task("api")), emptyMap()).single()
        val preparing = idle.copy(status = TaskStatus.PREPARING, statusText = "Preparing")
        val stopping = idle.copy(status = TaskStatus.STOPPING, statusText = "Stopping")

        assertEquals(TaskActionState(run = true, stop = false, openTerminal = false), idle.actionState(false))
        assertEquals(TaskActionState(run = false, stop = true, openTerminal = true), preparing.actionState(true))
        assertEquals(TaskActionState(run = false, stop = true, openTerminal = false), stopping.actionState(false))
    }

    @Test
    fun `active task cannot be started twice`() {
        TaskStatus.entries.forEach { status ->
            val item = items(listOf(task("api")), mapOf("api" to execution("api", status))).single()
            assertEquals(status !in ACTIVE_STATUSES, item.actionState(false).run, status.name)
        }
    }

    @Test
    fun `exposes override favorite scope and auto metadata`() {
        val effective = task("api", order = 4, scope = TaskScope.PROJECT_PRIVATE, favorite = 2)
        val item = items(
            listOf(effective.copy(shadowed = listOf(effective.effective.copy(scope = TaskScope.GLOBAL)))),
            emptyMap(),
        ).single()

        assertEquals("Auto #4", item.autoText)
        assertEquals("Project private", item.scopeText)
        assertEquals("Favorite 2", item.favoriteText)
        assertTrue(item.hasOverrides)
    }

    @Test
    fun `configuration errors keep last valid items visible`() {
        val state = TaskListPresentationState(::statusText)
        state.update(TaskConfigurationState(tasks = listOf(task("api"))), emptyMap())
        val error = TaskConfigError(Path.of("tasks.toml"), 3, 7, "Invalid TOML syntax")

        val updated = state.update(TaskConfigurationState(errors = mapOf(TaskScope.GLOBAL to listOf(error))), emptyMap())

        assertEquals(listOf("api"), updated.items.map { it.task.effective.id })
        assertEquals(listOf(error), updated.errors)
    }

    @Test
    fun `example task serializes as valid non secret version 1 configuration`() {
        val example = TaskExample.create(TaskScope.PROJECT_SHARED)
        val content = TaskTomlWriter().write(listOf(example))

        val parsed = assertIs<TaskConfigLoadResult.Success>(
            TaskTomlParser().parse(content, TaskScope.PROJECT_SHARED, Path.of("tasks.toml")),
        )
        assertEquals("example", parsed.snapshot.tasks.single().id)
        assertFalse(content.contains("secret", ignoreCase = true))
        assertFalse(content.contains("password", ignoreCase = true))
    }

    @Test
    fun `example save requires a current error free expected hash`() {
        val scope = TaskScope.PROJECT_SHARED
        val snapshot = TaskConfigSnapshot(scope, emptyList(), Path.of("tasks.toml"), "current-hash", Instant.EPOCH)
        val error = TaskConfigError(snapshot.sourceFile, 1, 1, "Invalid TOML syntax")

        assertEquals("current-hash", TaskExample.expectedHash(TaskConfigurationState(snapshots = mapOf(scope to snapshot)), scope))
        assertNull(TaskExample.expectedHash(TaskConfigurationState(), scope))
        assertNull(
            TaskExample.expectedHash(
                TaskConfigurationState(snapshots = mapOf(scope to snapshot), errors = mapOf(scope to listOf(error))),
                scope,
            ),
        )
    }

    @Test
    fun `immediate double click resolves exact owned terminal despite stale cached ownership`() = runBlocking {
        val coordinator = TaskInteractionCoordinator()
        val item = items(
            listOf(task("api")),
            mapOf("api" to execution("api", TaskStatus.RUNNING)),
        ).single()
        var ownershipChecks = 0
        var activations = 0
        var runs = 0

        coordinator.handleDoubleClick(
            item = item,
            exactExecutionId = "execution-api",
            ownsTerminal = { taskId, executionId ->
                ownershipChecks++
                taskId == "api" && executionId == "execution-api"
            },
            activateTerminal = { _, _ -> activations++ },
            runInactive = { runs++ },
        )

        assertEquals(1, ownershipChecks)
        assertEquals(1, activations)
        assertEquals(0, runs)
    }

    @Test
    fun `active double click without owned terminal never restarts task`() = runBlocking {
        val coordinator = TaskInteractionCoordinator()
        val item = items(
            listOf(task("api")),
            mapOf("api" to execution("api", TaskStatus.PREPARING)),
        ).single()
        var runs = 0

        coordinator.handleDoubleClick(
            item,
            exactExecutionId = "execution-api",
            ownsTerminal = { _, _ -> false },
            activateTerminal = { _, _ -> error("must not activate") },
            runInactive = { runs++ },
        )

        assertEquals(0, runs)
    }

    @Test
    fun `exact active execution prevents restart even before presentation updates`() = runBlocking {
        val coordinator = TaskInteractionCoordinator()
        val staleIdleItem = items(listOf(task("api")), emptyMap()).single()
        var runs = 0

        coordinator.handleDoubleClick(
            staleIdleItem,
            exactExecutionId = "execution-api",
            ownsTerminal = { _, _ -> false },
            activateTerminal = { _, _ -> error("must not activate") },
            runInactive = { runs++ },
        )

        assertEquals(0, runs)
    }

    @Test
    fun `stale active presentation runs once when current active lookup is empty`() = runBlocking {
        val coordinator = TaskInteractionCoordinator()
        val staleActiveItem = items(
            listOf(task("api")),
            mapOf("api" to execution("api", TaskStatus.STOPPING)),
        ).single()
        var runs = 0

        coordinator.handleDoubleClick(
            staleActiveItem,
            exactExecutionId = null,
            ownsTerminal = { _, _ -> error("must not check ownership") },
            activateTerminal = { _, _ -> error("must not activate") },
            runInactive = { runs++ },
        )

        assertEquals(1, runs)
    }

    @Test
    fun `current external execution disables run and callback invokes runner zero times`() {
        val coordinator = TaskInteractionCoordinator()
        val staleIdleItem = items(listOf(task("api")), emptyMap()).single()
        var runs = 0

        assertFalse(coordinator.canRun(staleIdleItem, currentExecutionId = "external-execution"))
        val launched = coordinator.launchRun(
            item = staleIdleItem,
            currentExecutionId = "external-execution",
            launcher = { action -> runBlocking { action() } },
            run = { runs++ },
        )

        assertFalse(launched)
        assertEquals(0, runs)
    }

    @Test
    fun `two immediate run callbacks invoke runner once`() = runBlocking {
        val coordinator = TaskInteractionCoordinator()
        val item = items(listOf(task("api")), emptyMap()).single()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        var runs = 0
        try {
            val launch: ((suspend () -> Unit) -> Unit) = { action -> scope.launch { action() } }
            val first = coordinator.launchRun(item, null, launch) { runs++; release.await() }
            val second = coordinator.launchRun(item, null, launch) { runs++; release.await() }

            assertTrue(first)
            assertFalse(second)
            assertFalse(coordinator.canRun(item, null))
            assertEquals(1, runs)
            release.complete(Unit)
            yield()
            assertTrue(coordinator.canRun(item, null))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failed run clears in flight guard for retry`() = runBlocking {
        val coordinator = TaskInteractionCoordinator()
        val item = items(listOf(task("api")), emptyMap()).single()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var attempts = 0
        try {
            val launch: ((suspend () -> Unit) -> Unit) = { action -> scope.launch { action() } }
            assertTrue(coordinator.launchRun(item, null, launch) { attempts++; error("sanitized-test-failure") })
            yield()
            assertFalse(coordinator.isInFlight("api"))
            assertTrue(coordinator.launchRun(item, null, launch) { attempts++ })
            yield()
            assertEquals(2, attempts)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `banner retains exact config location alongside sanitized operation`() {
        val error = TaskConfigError(Path.of("config", "tasks.toml"), 8, 3, "Invalid TOML syntax")
        val banners = TaskBannerState().apply {
            updateConfig(listOf(error))
            showOperational("The example configuration could not be created.")
        }.messages()

        assertEquals(error, banners.configError)
        assertEquals("The example configuration could not be created.", banners.operationalMessage)
        assertEquals(8, banners.configError?.line)
        assertEquals(3, banners.configError?.column)
    }

    private fun task(
        id: String,
        name: String = id,
        order: Int? = null,
        scope: TaskScope = TaskScope.PROJECT_SHARED,
        favorite: Int? = null,
    ) = ResolvedTask(
        DevTask(
            id = id,
            name = name,
            scope = scope,
            source = TaskSource.InlineCommand("never-presented"),
            autoStart = order?.let { AutoStartConfig(AutoStartTrigger.PROJECT_OPEN, order = it) },
            favoriteSlot = favorite,
        ),
    )

    private fun items(
        tasks: List<ResolvedTask>,
        executions: Map<String, TaskExecution>,
    ) = TaskListModel.items(tasks, executions, ::statusText)

    private fun statusText(status: TaskStatus): String = when (status) {
        TaskStatus.IDLE -> "Idle"
        TaskStatus.PREPARING -> "Preparing"
        TaskStatus.WAITING_FOR_TERMINAL -> "Waiting for terminal"
        TaskStatus.RUNNING -> "Running"
        TaskStatus.SUCCEEDED -> "Succeeded"
        TaskStatus.FAILED -> "Failed"
        TaskStatus.STOPPING -> "Stopping"
        TaskStatus.STOPPED -> "Stopped"
        TaskStatus.UNKNOWN -> "Unknown"
        TaskStatus.INTERRUPTED -> "Interrupted"
    }

    private fun execution(taskId: String, status: TaskStatus, failure: TaskFailure? = null) = TaskExecution(
        taskId = taskId,
        executionId = "execution-$taskId",
        trigger = RunTrigger.MANUAL,
        status = status,
        startedAt = Instant.EPOCH,
        failure = failure,
        sourceScope = TaskScope.PROJECT_SHARED,
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

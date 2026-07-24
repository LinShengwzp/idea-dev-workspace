package com.anmi.devworkspace.ui

import com.anmi.devworkspace.config.TaskConfigResolver
import com.anmi.devworkspace.config.TaskConfigConflictException
import com.anmi.devworkspace.config.TaskTomlWriter
import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.EnvironmentValue
import com.anmi.devworkspace.domain.ResolvedTask
import com.anmi.devworkspace.domain.TaskExecution
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskSource
import com.anmi.devworkspace.domain.TaskStatus
import com.anmi.devworkspace.domain.RunTrigger
import com.anmi.devworkspace.prepare.OperatingSystem
import java.nio.file.Path
import java.time.Instant
import com.intellij.ui.table.JBTable
import javax.swing.JTextField
import javax.swing.table.DefaultTableModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TaskEditorModelTest {
    @Test
    fun `correcting an invalid script path clears validation`() {
        val invalid = validModel().withState(validState().copy(scriptPath = ""))

        assertTrue(invalid.validate().any { it.field == "scriptPath" })

        val corrected = invalid.withState(invalid.state.copy(scriptPath = "scripts/run.ps1"))

        assertFalse(corrected.validate().any { it.field == "scriptPath" })
    }

    @Test
    fun `edited environment value is committed before validation`() {
        val tableModel = DefaultTableModel(arrayOf("Name"), 1)
        val table = JBTable(tableModel)
        assertTrue(table.editCellAt(0, 0))
        (table.editorComponent as JTextField).text = "VALID_NAME"

        assertTrue(commitEnvironmentTableEdit(table))
        assertEquals("VALID_NAME", tableModel.getValueAt(0, 0))
        assertFalse(table.isEditing)
    }

    @Test
    fun `script chooser stores project files portably and external files absolutely`() {
        val project = Path.of("C:/work/project")

        assertEquals(
            "\${PROJECT_DIR}/scripts/run.ps1",
            TaskScriptPath.store(Path.of("C:/work/project/scripts/run.ps1"), project),
        )
        assertEquals(
            "C:/tools/run.ps1",
            TaskScriptPath.store(Path.of("C:/tools/run.ps1"), project),
        )
        assertEquals(
            Path.of("C:/work/project/scripts/run.ps1"),
            TaskScriptPath.resolve("\${PROJECT_DIR}/scripts/run.ps1", project),
        )
    }

    @Test
    fun `validates identifiers sources numeric fields and environment rows`() {
        val invalid = TaskEditorModel(
            TaskEditorState(
                id = "bad id",
                sourceKind = SourceKind.SCRIPT,
                scriptPath = "",
                favoriteSlot = 0,
                startupOrder = -1,
                startupDelaySeconds = -2,
                environmentRows = listOf(
                    EnvironmentRow("BAD-NAME", EnvironmentRowKind.PLAIN, "x"),
                    EnvironmentRow("DUP", EnvironmentRowKind.PLAIN, "x"),
                    EnvironmentRow("DUP", EnvironmentRowKind.SYSTEM, "also-bad-name"),
                    EnvironmentRow("SECRET", EnvironmentRowKind.SECRET, ""),
                ),
            ),
            snapshots(),
        )

        val fields = invalid.validate().map { it.field }.toSet()
        assertTrue(fields.containsAll(setOf("id", "scriptPath", "favoriteSlot", "startupOrder", "startupDelaySeconds")))
        assertTrue(fields.containsAll(setOf("environment.0.name", "environment.2.name", "environment.2.value", "environment.3.value")))

        val blankId = invalid.withState(invalid.state.copy(id = ""))
        assertTrue(blankId.validate().any { it.field == "id" })
        assertTrue(validModel().validate().isEmpty())
        assertTrue(
            invalid.withState(invalid.state.copy(id = "ok", sourceKind = SourceKind.INLINE, inlineCommand = ""))
                .validate().any { it.field == "inlineCommand" },
        )
    }

    @Test
    fun `maps all environment kinds without retaining an actual secret`() {
        val marker = "actual-secret-must-not-appear"
        val model = validModel(
            environmentRows = listOf(
                EnvironmentRow("PLAIN", EnvironmentRowKind.PLAIN, "hello"),
                EnvironmentRow("SYSTEM", EnvironmentRowKind.SYSTEM, "PATH"),
                EnvironmentRow("SECRET", EnvironmentRowKind.SECRET, "api-token-key"),
            ),
        )

        val task = assertIs<TaskConversion.Valid>(model.toDevTask()).task
        assertEquals(EnvironmentValue.Plain("hello"), task.environment["PLAIN"])
        assertEquals(EnvironmentValue.SystemReference("PATH"), task.environment["SYSTEM"])
        assertEquals(EnvironmentValue.SecretReference("api-token-key"), task.environment["SECRET"])
        val serialized = TaskTomlWriter().write(listOf(task))
        val preview = model.preview(previewContext()).toString()
        assertTrue(serialized.contains("\${SECRET:api-token-key}"))
        assertTrue(preview.contains("\${SECRET:api-token-key}"))
        assertFalse(model.toString().contains(marker))
        assertFalse(task.toString().contains(marker))
        assertFalse(serialized.contains(marker))
        assertFalse(preview.contains(marker))
    }

    @Test
    fun `scope switching changes duplicate validation target`() {
        val sharedExisting = task("candidate", TaskScope.PROJECT_SHARED)
        val model = TaskEditorModel(
            validState().copy(id = "candidate", scope = TaskScope.PROJECT_PRIVATE),
            snapshots(shared = listOf(sharedExisting)),
            EditorMode.NEW,
        )

        assertFalse(model.validate().any { it.field == "id" })
        assertTrue(model.withState(model.state.copy(scope = TaskScope.PROJECT_SHARED)).validate().any { it.field == "id" })
    }

    @Test
    fun `copy requires a genuinely new id and never overwrites target`() {
        val source = task("source", TaskScope.PROJECT_SHARED)
        val model = TaskEditorModel(TaskEditorState.from(source), snapshots(shared = listOf(source)), EditorMode.COPY)

        assertTrue(model.validate().any { it.field == "id" })
        val plan = assertIs<PlanResult.Valid>(model.withState(model.state.copy(id = "source-copy")).planSave()).plan
        assertEquals(listOf("source", "source-copy"), plan.mutations.single().tasks.map { it.id })
        assertEquals("shared-hash", plan.mutations.single().expectedHash)
    }

    @Test
    fun `editing an id plans delete old and create new across captured files`() {
        val source = task("old", TaskScope.PROJECT_SHARED)
        val model = TaskEditorModel(TaskEditorState.from(source), snapshots(shared = listOf(source)), EditorMode.EDIT)
            .withState(TaskEditorState.from(source).copy(id = "new", scope = TaskScope.PROJECT_PRIVATE))

        val plan = assertIs<PlanResult.Valid>(model.planSave()).plan
        assertEquals(listOf(TaskScope.PROJECT_SHARED, TaskScope.PROJECT_PRIVATE), plan.mutations.map { it.scope })
        assertTrue(plan.mutations.first().tasks.none { it.id == "old" })
        assertTrue(plan.mutations.last().tasks.any { it.id == "new" })
        assertEquals(listOf("shared-hash", "private-hash"), plan.mutations.map { it.expectedHash })
    }

    @Test
    fun `delete removes only selected scope and reveals lower definition`() {
        val global = task("api", TaskScope.GLOBAL)
        val shared = task("api", TaskScope.PROJECT_SHARED)
        val private = task("api", TaskScope.PROJECT_PRIVATE)
        val model = TaskEditorModel(TaskEditorState.from(private), snapshots(global = listOf(global), shared = listOf(shared), privateTasks = listOf(private)), EditorMode.EDIT)

        val deletion = assertIs<PlanResult.Valid>(model.planDelete()).plan.mutations.single()
        assertEquals(TaskScope.PROJECT_PRIVATE, deletion.scope)
        assertEquals("private-hash", deletion.expectedHash)
        val resolved = TaskConfigResolver.resolve(listOf(global), listOf(shared), deletion.tasks).single()
        assertEquals(TaskScope.PROJECT_SHARED, resolved.effective.scope)
    }

    @Test
    fun `conflict choices are exact and overwrite clears expected hash only explicitly`() {
        assertEquals(
            listOf(ConflictChoice.RELOAD_AND_EDIT, ConflictChoice.VIEW_DIFF, ConflictChoice.OVERWRITE),
            ConflictChoice.entries,
        )
        val mutation = ScopeMutation(TaskScope.PROJECT_SHARED, listOf(task("api", TaskScope.PROJECT_SHARED)), "captured")
        assertEquals("captured", mutation.expectedHash)
        assertEquals("captured", mutation.forChoice(ConflictChoice.RELOAD_AND_EDIT).expectedHash)
        assertEquals("captured", mutation.forChoice(ConflictChoice.VIEW_DIFF).expectedHash)
        assertEquals(null, mutation.forChoice(ConflictChoice.OVERWRITE).expectedHash)
    }

    @Test
    fun `conflict coordinator reloads without overwrite`() = runBlocking {
        val calls = mutableListOf<String>()
        val coordinator = TaskSaveCoordinator(
            save = { throw conflict(it) },
            reload = { calls += "reload:${it.name}" },
            choose = { ConflictChoice.RELOAD_AND_EDIT },
            viewDiff = { calls += "diff" },
        )

        assertEquals(SaveExecution.Reloaded(partial = false), coordinator.execute(singleMutationPlan()))
        assertEquals(listOf("reload:PROJECT_SHARED"), calls)
    }

    @Test
    fun `conflict coordinator shows diff then overwrites through null hash boundary`() = runBlocking {
        val hashes = mutableListOf<String?>()
        val choices = ArrayDeque(listOf(ConflictChoice.VIEW_DIFF, ConflictChoice.OVERWRITE))
        var diffs = 0
        val coordinator = TaskSaveCoordinator(
            save = {
                hashes += it.expectedHash
                if (it.expectedHash != null) throw conflict(it)
            },
            reload = {},
            choose = { choices.removeFirst() },
            viewDiff = { diffs++ },
        )

        assertEquals(SaveExecution.Saved, coordinator.execute(singleMutationPlan()))
        assertEquals(listOf("captured", "captured", null), hashes)
        assertEquals(1, diffs)
    }

    @Test
    fun `preview resolves safe directories detects shell sanitizes controls and redacts tokens`() {
        val model = validModel().withState(
            validState().copy(
                sourceKind = SourceKind.INLINE,
                inlineCommand = "echo \${ENV:TOKEN} \${SECRET:key}\nnext",
                inlineShell = "pwsh",
                workingDirectory = "\${PROJECT_DIR}/app",
            ),
        )

        val preview = model.preview(previewContext())

        assertEquals("C:/work/project/app", preview.workingDirectory)
        assertEquals("POWERSHELL", preview.shell)
        assertEquals("echo \${ENV:TOKEN} \${SECRET:key} next", preview.command)
        assertFalse(preview.toString().contains("live-environment-secret"))
        assertTrue(preview.warnings.isEmpty())
    }

    @Test
    fun `invalid conversion cannot construct partial task`() {
        assertIs<TaskConversion.Invalid>(TaskEditorModel(TaskEditorState(), snapshots()).toDevTask())
    }

    @Test
    fun `navigation converts exact one based location to platform coordinates`() {
        assertEquals(EditorPosition(0, 0), TaskConfigNavigation.editorPosition(1, 1))
        assertEquals(EditorPosition(7, 2), TaskConfigNavigation.editorPosition(8, 3))
        assertEquals(EditorPosition(0, 0), TaskConfigNavigation.editorPosition(0, -4))
    }

    @Test
    fun `configuration edit does not mutate running execution input snapshot`() {
        val original = task("api", TaskScope.PROJECT_SHARED)
        val runningInput = ResolvedTask(original)
        val execution = TaskExecution("api", "run-1", RunTrigger.MANUAL, TaskStatus.RUNNING, Instant.EPOCH, sourceScope = original.scope)
        val edited = TaskEditorModel(TaskEditorState.from(original), snapshots(shared = listOf(original)), EditorMode.EDIT)
            .withState(TaskEditorState.from(original).copy(inlineCommand = "new-command"))

        assertIs<PlanResult.Valid>(edited.planSave())
        assertEquals("echo safe", (runningInput.effective.source as TaskSource.InlineCommand).command)
        assertEquals(TaskStatus.RUNNING, execution.status)
    }

    private fun validModel(environmentRows: List<EnvironmentRow> = emptyList()) = TaskEditorModel(
        validState().copy(environmentRows = environmentRows),
        snapshots(),
        EditorMode.NEW,
    )

    private fun validState() = TaskEditorState(
        id = "valid.task",
        name = "Valid task",
        sourceKind = SourceKind.SCRIPT,
        scriptPath = "scripts/run.sh",
    )

    private fun task(id: String, scope: TaskScope) = DevTask(
        id = id,
        name = id,
        scope = scope,
        source = TaskSource.InlineCommand("echo safe"),
    )

    private fun snapshots(
        global: List<DevTask> = emptyList(),
        shared: List<DevTask> = emptyList(),
        privateTasks: List<DevTask> = emptyList(),
    ) = EditorSnapshots(
        mapOf(
            TaskScope.GLOBAL to ScopeSnapshot(global, "global-hash"),
            TaskScope.PROJECT_SHARED to ScopeSnapshot(shared, "shared-hash"),
            TaskScope.PROJECT_PRIVATE to ScopeSnapshot(privateTasks, "private-hash"),
        ),
    )

    private fun previewContext() = TaskPreviewContext(
        projectDir = Path.of("C:/work/project"),
        userHome = Path.of("C:/Users/test"),
        moduleDir = Path.of("C:/work/project/module"),
        operatingSystem = OperatingSystem.WINDOWS,
        shellEnvironment = null,
    )

    private fun singleMutationPlan() = TaskSavePlan(
        listOf(ScopeMutation(TaskScope.PROJECT_SHARED, listOf(task("api", TaskScope.PROJECT_SHARED)), "captured")),
    )

    private fun conflict(mutation: ScopeMutation) = TaskConfigConflictException(
        mutation.scope,
        Path.of("tasks.toml"),
        mutation.expectedHash,
        "changed",
    )
}

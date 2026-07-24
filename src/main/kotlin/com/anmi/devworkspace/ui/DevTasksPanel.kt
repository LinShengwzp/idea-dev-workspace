package com.anmi.devworkspace.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.config.TaskConfigConflictException
import com.anmi.devworkspace.config.TaskConfigError
import com.anmi.devworkspace.config.TaskConfigurationService
import com.anmi.devworkspace.config.TaskConfigurationState
import com.anmi.devworkspace.config.TaskTomlWriter
import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.RunTrigger
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.prepare.OperatingSystem
import com.anmi.devworkspace.prepare.PreparationContext
import com.anmi.devworkspace.runtime.ProjectTaskRunner
import com.anmi.devworkspace.terminal.idea262.Idea262TerminalUi
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

class DevTasksPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val configuration = project.service<TaskConfigurationService>()
    private val runner = project.service<ProjectTaskRunner>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val presentationState = TaskListPresentationState { status ->
        DevWorkspaceBundle.message(status.resourceKey)
    }
    private val bannerState = TaskBannerState()
    private val interactionCoordinator = TaskInteractionCoordinator(::inFlightChanged)
    private val navigation = TaskConfigNavigation(project, ::showOperational)
    private val listModel = DefaultListModel<TaskListItem>()
    private val list = JBList(listModel).apply {
        cellRenderer = TaskListCellRenderer()
        emptyText.text = message("task.empty.short")
    }
    private val search = SearchTextField()
    private val cards = CardLayout()
    private val cardPanel = JBPanel<JBPanel<*>>(cards)
    private val errorPanel = TaskConfigErrorPanel(::openErrorLocation) { scope.launch { configuration.reload() } }
    private var allItems: List<TaskListItem> = emptyList()
    private var selectedOwnsTerminal = false

    private val newAction = UiAction(message("task.action.new"), AllIcons.General.Add) { openNewEditor() }
    private val editAction = UiAction(message("task.action.edit"), AllIcons.Actions.Edit) {
        selectedItem()?.let { openEditor(it.task.effective) }
    }
    private val copyAction = UiAction(message("task.action.copy"), AllIcons.Actions.Copy) {
        selectedItem()?.let { openCopyEditor(it.task.effective) }
    }
    private val deleteAction = UiAction(message("task.action.delete"), AllIcons.General.Remove) {
        selectedItem()?.let { deleteTask(it.task.effective) }
    }
    private val runAction = UiAction(message("task.action.run"), AllIcons.Actions.Execute) { selectedItem()?.let(::runTask) }
    private val terminalAction = UiAction(
        message("task.action.open.terminal"),
        Idea262TerminalUi.toolWindowIcon(project) ?: AllIcons.Actions.MenuOpen,
    ) { selectedItem()?.let(::openExactTerminal) }
    private val configAction = UiAction(message("task.action.open.config"), AllIcons.Actions.MenuOpen) {
        openConfig(selectedItem()?.task?.effective?.scope)
    }

    init {
        val toolbarGroup = DefaultActionGroup(
            newAction,
            editAction,
            copyAction,
            deleteAction,
            runAction,
            terminalAction,
            configAction,
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("DevTasks", toolbarGroup, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val listContent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(search, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }
        cardPanel.add(listContent, LIST_CARD)
        cardPanel.add(createEmptyPanel(), EMPTY_CARD)
        setContent(JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(errorPanel, BorderLayout.NORTH)
            add(cardPanel, BorderLayout.CENTER)
        })

        search.textEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = renderFiltered()
        })
        list.addListSelectionListener {
            selectedOwnsTerminal = false
            updateActions()
            refreshTerminalOwnership()
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1) handleDoubleClick()
            }
        })
        collectState()
        updateActions()
    }

    override fun dispose() {
        scope.cancel()
    }

    private fun collectState() {
        scope.launch {
            configuration.state.combine(runner.executions) { config, executions -> config to executions }
                .collect { (config, executions) ->
                    val presentation = presentationState.update(config, executions)
                    withContext(Dispatchers.EDT) {
                        allItems = presentation.items
                        bannerState.updateConfig(presentation.errors)
                        renderBanner()
                        renderFiltered()
                        refreshTerminalOwnership()
                    }
                }
        }
    }

    private fun renderFiltered() {
        val selectedId = selectedItem()?.task?.effective?.id
        val filtered = TaskListModel.filter(allItems, search.text)
        listModel.clear()
        filtered.forEach(listModel::addElement)
        selectedId?.let { id -> list.selectedIndex = filtered.indexOfFirst { it.task.effective.id == id } }
        cards.show(cardPanel, if (allItems.isEmpty()) EMPTY_CARD else LIST_CARD)
        updateActions()
    }

    private fun refreshTerminalOwnership() {
        val selected = selectedItem() ?: return
        val execution = runner.executions.value[selected.task.effective.id] ?: return
        val taskId = selected.task.effective.id
        scope.launch {
            val owns = runner.ownsTerminal(taskId, execution.executionId)
            withContext(Dispatchers.EDT) {
                if (selectedItem()?.task?.effective?.id == taskId &&
                    runner.executions.value[taskId]?.executionId == execution.executionId
                ) {
                    selectedOwnsTerminal = owns
                    updateActions()
                }
            }
        }
    }

    private fun updateActions() {
        val selected = selectedItem()
        val selectedState = selected?.actionState(selectedOwnsTerminal)
        editAction.enabled = selected != null
        copyAction.enabled = selected != null
        deleteAction.enabled = selected != null
        runAction.enabled = selected?.let { item ->
            val currentExecutionId = runner.active(item.task.effective.id)?.executionId
            interactionCoordinator.canRun(item, currentExecutionId)
        } == true
        terminalAction.enabled = selectedState?.openTerminal == true
        newAction.enabled = true
        configAction.enabled = true
    }

    private fun handleDoubleClick() {
        val selected = selectedItem() ?: return
        val taskId = selected.task.effective.id
        val exactExecutionId = runner.executions.value[taskId]?.executionId
        val canRunInactive = runner.active(taskId) == null
        scope.launch {
            interactionCoordinator.handleDoubleClick(
                item = selected,
                exactExecutionId = exactExecutionId,
                canRunInactive = canRunInactive,
                ownsTerminal = runner::ownsTerminal,
                activateTerminal = { id, executionId -> runner.activateTerminal(id, executionId) },
                runInactive = { runTask(selected) },
            )
        }
    }

    private fun runTask(item: TaskListItem) {
        val taskId = item.task.effective.id
        val currentExecutionId = runner.active(taskId)?.executionId
        interactionCoordinator.launchRun(
            item = item,
            currentExecutionId = currentExecutionId,
            launcher = { action -> scope.launch { action() } },
            run = { runner.run(item.task, RunTrigger.MANUAL, preparationContext()) },
        )
    }

    private fun openExactTerminal(item: TaskListItem) {
        val taskId = item.task.effective.id
        val exactExecutionId = runner.executions.value[taskId]?.executionId ?: return
        scope.launch {
            interactionCoordinator.activateExactIfOwned(
                taskId,
                exactExecutionId,
                runner::ownsTerminal,
                { id, executionId -> runner.activateTerminal(id, executionId) },
            )
        }
    }

    private fun createEmptyPanel(): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        val message = JBTextArea(message("task.empty.description")).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
        }
        val buttons = JBPanel<JBPanel<*>>().apply {
            add(ActionLink(message("task.empty.new")).also { it.addActionListener { openNewEditor() } })
            add(ActionLink(message("task.empty.create.example")).also { it.addActionListener { createExampleConfig() } })
            add(ActionLink(message("task.empty.open.config")).also { it.addActionListener { openConfig(null) } })
        }
        add(message, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
    }

    private fun createExampleConfig() {
        scope.launch {
            val targetScope = TaskScope.PROJECT_SHARED
            try {
                configuration.reload(targetScope)
                val expectedHash = TaskExample.expectedHash(configuration.state.value, targetScope)
                if (expectedHash == null) {
                    showOperational(message("task.operation.reload.repair.example"))
                    return@launch
                }
                configuration.save(targetScope, listOf(TaskExample.create(targetScope)), expectedHash)
                clearOperational()
            } catch (conflict: TaskConfigConflictException) {
                showOperational(message("task.operation.example.conflict"))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                showOperational(message("task.operation.example.failed"))
            }
        }
    }

    private fun openConfig(scopeHint: TaskScope?) {
        val targetScope = scopeHint ?: TaskScope.PROJECT_SHARED
        scope.launch { navigation.open(configuration.path(targetScope)) }
    }

    private fun openErrorLocation(error: TaskConfigError) {
        scope.launch { navigation.open(error.sourceFile, error.line, error.column) }
    }

    private fun openNewEditor() {
        launchEditor(EditorMode.NEW, null, TaskEditorState())
    }

    private fun openEditor(task: DevTask) {
        launchEditor(EditorMode.EDIT, task, TaskEditorState.from(task))
    }

    private fun openCopyEditor(task: DevTask) {
        scope.launch {
            configuration.reload()
            val snapshotState = configuration.state.value
            val copyId = uniqueCopyId(task.id, task.scope, snapshotState)
            showEditorLoop(EditorMode.COPY, task, TaskEditorState.from(task).copy(id = copyId), snapshotState)
        }
    }

    private fun launchEditor(mode: EditorMode, original: DevTask?, initial: TaskEditorState) {
        scope.launch {
            try {
                configuration.reload()
                showEditorLoop(mode, original, initial, configuration.state.value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                showOperational(message("task.operation.editor.failed"))
            }
        }
    }

    private suspend fun showEditorLoop(
        mode: EditorMode,
        original: DevTask?,
        initial: TaskEditorState,
        initialConfiguration: TaskConfigurationState,
    ) {
        var configurationState = initialConfiguration
        var editorState = initial
        var editorOriginal = original
        while (true) {
            if (configurationState.errors.isNotEmpty()) {
                showOperational(message("task.operation.reload.repair.edit"))
                return
            }
            val model = TaskEditorModel(editorState, editorSnapshots(configurationState), mode, editorOriginal)
            val accepted = withContext(Dispatchers.EDT) {
                val dialog = TaskEditorDialog(
                    project = project,
                    initialModel = model,
                    previewContext = taskPreviewContext(),
                    showInExplorer = { target -> scope.launch { navigation.showInFileManager(configuration.path(target)) } },
                    dialogTitle = when (mode) {
                        EditorMode.NEW -> message("task.editor.title.new")
                        EditorMode.EDIT -> message("task.editor.title.edit")
                        EditorMode.COPY -> message("task.editor.title.copy")
                    },
                )
                if (dialog.showAndGet()) dialog.resultModel() else null
            } ?: return
            editorState = accepted.state
            val plan = when (val result = accepted.planSave()) {
                is PlanResult.Invalid -> {
                    showOperational(result.issues.firstOrNull()?.message ?: message("task.operation.invalid"))
                    continue
                }
                is PlanResult.Valid -> result.plan
            }
            when (savePlan(plan)) {
                SavePlanResult.SAVED -> return
                SavePlanResult.RELOAD -> {
                    configuration.reload()
                    configurationState = configuration.state.value
                    if (mode == EditorMode.EDIT && editorOriginal != null) {
                        val reloaded = configurationState.snapshots[editorOriginal.scope]?.tasks
                            ?.firstOrNull { it.id == editorOriginal.id }
                        if (reloaded != null) {
                            editorOriginal = reloaded
                            editorState = TaskEditorState.from(reloaded)
                        }
                    }
                }
                SavePlanResult.FAILED -> return
            }
        }
    }

    private fun deleteTask(task: DevTask) {
        val confirmed = Messages.showYesNoDialog(
            project,
            message("task.delete.message", task.id, scopeText(task.scope)),
            message("task.delete.title"),
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) return
        scope.launch {
            try {
                configuration.reload()
                val state = configuration.state.value
                val current = state.snapshots[task.scope]?.tasks?.firstOrNull { it.id == task.id }
                if (current == null) {
                    showOperational(message("task.operation.definition.missing"))
                    return@launch
                }
                val model = TaskEditorModel(TaskEditorState.from(current), editorSnapshots(state), EditorMode.EDIT, current)
                val plan = (model.planDelete() as? PlanResult.Valid)?.plan ?: return@launch
                savePlan(plan)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                showOperational(message("task.operation.delete.failed"))
            }
        }
    }

    private suspend fun savePlan(plan: TaskSavePlan): SavePlanResult {
        val coordinator = TaskSaveCoordinator(
            save = { mutation -> configuration.save(mutation.scope, mutation.tasks, mutation.expectedHash) },
            reload = { target -> configuration.reload(target) },
            choose = {
                val selected = withContext(Dispatchers.EDT) {
                    Messages.showDialog(
                        project,
                        message("task.conflict.message"),
                        message("task.conflict.title"),
                        ConflictChoice.entries.map(::conflictChoiceText).toTypedArray(),
                        0,
                        Messages.getWarningIcon(),
                    )
                }
                ConflictChoice.entries.getOrNull(selected) ?: ConflictChoice.RELOAD_AND_EDIT
            },
            viewDiff = { mutation ->
                try {
                    navigation.showDiff(configuration.path(mutation.scope), TaskTomlWriter().write(mutation.tasks))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    showOperational(message("task.operation.diff.failed"))
                }
            },
        )
        val result = try {
            coordinator.execute(plan)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            configuration.reload()
            showOperational(message("task.operation.save.failed"))
            return SavePlanResult.FAILED
        }
        when (result) {
            is SaveExecution.Reloaded -> {
                if (result.partial) {
                    showOperational(message("task.operation.partial.conflict"))
                }
                return SavePlanResult.RELOAD
            }
            SaveExecution.Saved -> Unit
        }
        configuration.reload()
        clearOperational()
        return SavePlanResult.SAVED
    }

    private fun editorSnapshots(state: TaskConfigurationState): EditorSnapshots = EditorSnapshots(
        TaskScope.entries.associateWith { target ->
            val snapshot = state.snapshots[target]
            ScopeSnapshot(snapshot?.tasks.orEmpty(), snapshot?.contentHash)
        },
    )

    private fun uniqueCopyId(baseId: String, target: TaskScope, state: TaskConfigurationState): String {
        val existing = state.snapshots[target]?.tasks.orEmpty().mapTo(mutableSetOf()) { it.id }
        var suffix = 1
        var candidate = "$baseId-copy"
        while (candidate in existing) {
            suffix++
            candidate = "$baseId-copy-$suffix"
        }
        return candidate
    }

    private fun taskPreviewContext(): TaskPreviewContext = TaskPreviewContext(
        projectDir = Path.of(requireNotNull(project.basePath)),
        userHome = Path.of(System.getProperty("user.home")),
        moduleDir = null,
        operatingSystem = when {
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> OperatingSystem.WINDOWS
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> OperatingSystem.MAC
            else -> OperatingSystem.LINUX
        },
        shellEnvironment = System.getenv("SHELL"),
    )

    private fun showOperational(message: String) {
        if (SwingUtilities.isEventDispatchThread()) {
            bannerState.showOperational(message)
            renderBanner()
        } else {
            scope.launch(Dispatchers.EDT) {
                bannerState.showOperational(message)
                renderBanner()
            }
        }
    }

    private suspend fun clearOperational() = withContext(Dispatchers.EDT) {
        bannerState.clearOperational()
        renderBanner()
    }

    private fun renderBanner() {
        errorPanel.showMessages(bannerState.messages())
    }

    private fun inFlightChanged(@Suppress("UNUSED_PARAMETER") taskId: String) {
        if (SwingUtilities.isEventDispatchThread()) {
            updateActions()
        } else {
            scope.launch(Dispatchers.EDT) { updateActions() }
        }
    }

    private fun selectedItem(): TaskListItem? = list.selectedValue

    private fun preparationContext(): PreparationContext = PreparationContext(
        projectDir = Path.of(requireNotNull(project.basePath)),
        userHome = Path.of(System.getProperty("user.home")),
        moduleDir = null,
        environment = System.getenv().toMap(),
        operatingSystem = when {
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> OperatingSystem.WINDOWS
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> OperatingSystem.MAC
            else -> OperatingSystem.LINUX
        },
        shellEnvironment = System.getenv("SHELL"),
    )

    private fun scopeText(value: TaskScope): String = message(
        when (value) {
            TaskScope.GLOBAL -> "task.editor.scope.global"
            TaskScope.PROJECT_SHARED -> "task.editor.scope.project.shared"
            TaskScope.PROJECT_PRIVATE -> "task.editor.scope.project.private"
        },
    )

    private fun conflictChoiceText(value: ConflictChoice): String = message(
        when (value) {
            ConflictChoice.RELOAD_AND_EDIT -> "task.conflict.reload"
            ConflictChoice.VIEW_DIFF -> "task.conflict.diff"
            ConflictChoice.OVERWRITE -> "task.conflict.overwrite"
        },
    )

    private fun message(key: String, vararg values: Any): String =
        DevWorkspaceBundle.message(key, *values)

    private inner class UiAction(text: String, icon: javax.swing.Icon, private val invoke: () -> Unit) :
        AnAction(text, null, icon) {
        var enabled: Boolean = true

        override fun actionPerformed(e: AnActionEvent) = invoke()

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled
        }
    }

    private companion object {
        const val LIST_CARD = "list"
        const val EMPTY_CARD = "empty"
    }

    private enum class SavePlanResult { SAVED, RELOAD, FAILED }
}

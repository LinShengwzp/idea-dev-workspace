package com.anmi.devworkspace.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.config.TaskConfigConflictException
import com.anmi.devworkspace.config.TaskConfigError
import com.anmi.devworkspace.config.TaskConfigurationService
import com.anmi.devworkspace.domain.RunTrigger
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.prepare.OperatingSystem
import com.anmi.devworkspace.prepare.PreparationContext
import com.anmi.devworkspace.runtime.ProjectTaskRunner
import com.anmi.devworkspace.runtime.StopResult
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
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
    private val listModel = DefaultListModel<TaskListItem>()
    private val list = JBList(listModel).apply {
        cellRenderer = TaskListCellRenderer()
        emptyText.text = "No Dev Tasks configured"
    }
    private val search = SearchTextField()
    private val cards = CardLayout()
    private val cardPanel = JBPanel<JBPanel<*>>(cards)
    private val errorPanel = TaskConfigErrorPanel(::openErrorLocation) { scope.launch { configuration.reload() } }
    private var allItems: List<TaskListItem> = emptyList()
    private var selectedOwnsTerminal = false

    private val newAction = UiAction("New", AllIcons.General.Add) { editEntryPoint("New Task") }
    private val editAction = UiAction("Edit", AllIcons.Actions.Edit) { editEntryPoint("Edit Task") }
    private val copyAction = UiAction("Copy", AllIcons.Actions.Copy) { editEntryPoint("Copy Task") }
    private val deleteAction = UiAction("Delete", AllIcons.General.Remove) { editEntryPoint("Delete Task") }
    private val runAction = UiAction("Run", AllIcons.Actions.Execute) { selectedItem()?.let(::runTask) }
    private val stopAction = UiAction("Stop", AllIcons.Actions.Suspend) { selectedItem()?.let(::stopTask) }
    private val terminalAction = UiAction("Open Terminal", AllIcons.Actions.MenuOpen) { selectedItem()?.let(::openExactTerminal) }
    private val configAction = UiAction("Open Config", AllIcons.Actions.MenuOpen) { openConfig(selectedItem()?.task?.effective?.scope) }

    init {
        val toolbarGroup = DefaultActionGroup(
            newAction,
            editAction,
            copyAction,
            deleteAction,
            runAction,
            stopAction,
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
        stopAction.enabled = selectedState?.stop == true
        terminalAction.enabled = selectedState?.openTerminal == true
        newAction.enabled = true
        configAction.enabled = true
    }

    private fun handleDoubleClick() {
        val selected = selectedItem() ?: return
        val taskId = selected.task.effective.id
        val exactExecutionId = runner.active(taskId)?.executionId
        scope.launch {
            interactionCoordinator.handleDoubleClick(
                item = selected,
                exactExecutionId = exactExecutionId,
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

    private fun stopTask(item: TaskListItem) {
        if (!item.actionState(selectedOwnsTerminal).stop) return
        scope.launch {
            when (runner.stop(item.task.effective.id)) {
                is StopResult.ForceCloseRequired -> {
                    val confirmed = withContext(Dispatchers.EDT) {
                        Messages.showYesNoDialog(
                            project,
                            "The task did not stop. Close its task terminal?",
                            "Stop Dev Task",
                            Messages.getWarningIcon(),
                        ) == Messages.YES
                    }
                    if (confirmed) runner.forceClose(item.task.effective.id)
                }
                StopResult.NotRunning, StopResult.Stopped -> Unit
            }
        }
    }

    private fun openExactTerminal(item: TaskListItem) {
        val taskId = item.task.effective.id
        val exactExecutionId = runner.active(taskId)?.executionId ?: return
        scope.launch {
            interactionCoordinator.activateExactIfOwned(
                taskId,
                exactExecutionId,
                runner::ownsTerminal,
                { id, executionId -> runner.activateTerminal(id, executionId) },
            )
        }
    }

    private fun editEntryPoint(title: String) {
        val selectedScope = selectedItem()?.task?.effective?.scope
        openConfig(selectedScope)
        showOperational("$title is not available yet. Use the configuration file directly.")
    }

    private fun createEmptyPanel(): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        val message = JBTextArea("No Dev Tasks are configured. Create one or open a configuration file.").apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
        }
        val buttons = JBPanel<JBPanel<*>>().apply {
            add(ActionLink("New Task").also { it.addActionListener { editEntryPoint("New Task") } })
            add(ActionLink("Create Example Config").also { it.addActionListener { createExampleConfig() } })
            add(ActionLink("Open Config").also { it.addActionListener { openConfig(null) } })
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
                    showOperational("Reload and repair the configuration before creating the example.")
                    return@launch
                }
                configuration.save(targetScope, listOf(TaskExample.create(targetScope)), expectedHash)
                clearOperational()
            } catch (conflict: TaskConfigConflictException) {
                showOperational("The configuration changed externally. Reload it before creating the example.")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                showOperational("The example configuration could not be created.")
            }
        }
    }

    private fun openConfig(scopeHint: TaskScope?) {
        val targetScope = scopeHint ?: TaskScope.PROJECT_SHARED
        openPath(configuration.path(targetScope), 1, 1)
    }

    private fun openErrorLocation(error: TaskConfigError) {
        openPath(error.sourceFile, error.line, error.column)
    }

    private fun openPath(path: Path, line: Int, column: Int) {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            }
            withContext(Dispatchers.EDT) {
                if (file != null) {
                    clearOperational()
                    OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), (column - 1).coerceAtLeast(0)).navigate(true)
                } else {
                    showOperational("The configuration file does not exist yet.")
                }
            }
        }
    }

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
}

package com.anmi.devworkspace.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.domain.AutoStartTrigger
import com.anmi.devworkspace.domain.GlobalAutoStartMode
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TerminalPolicy
import com.anmi.devworkspace.secrets.PasswordSafeSecretStore
import com.anmi.devworkspace.secrets.SecretStore
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.application.EDT
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JList
import javax.swing.table.DefaultTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.event.DocumentEvent
import javax.swing.event.TableModelEvent

class TaskEditorDialog(
    private val project: Project,
    initialModel: TaskEditorModel,
    private val previewContext: TaskPreviewContext,
    private val showInExplorer: (TaskScope) -> Unit,
    dialogTitle: String,
    private val secretStore: SecretStore = PasswordSafeSecretStore(),
) : DialogWrapper(project, true) {
    private var model = initialModel
    private val secretScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val id = JBTextField(model.state.id)
    private val name = JBTextField(model.state.name)
    private val terminalAlias = JBTextField(model.state.terminalAlias)
    private val description = JBTextArea(model.state.description, 2, 40)
    private val scope = enumCombo(TaskScope.entries.toTypedArray(), initialModel.state.scope, ::scopeText)
    private val enabled = JBCheckBox(message("task.editor.enabled"), model.state.enabled)
    private val sourceKind = enumCombo(SourceKind.entries.toTypedArray(), initialModel.state.sourceKind, ::sourceKindText)
    private val scriptPath = TextFieldWithBrowseButton().apply { text = model.state.scriptPath }
    private val arguments = JBTextArea(model.state.arguments.joinToString("\n"), 3, 40)
    private val interpreter = JBTextField(model.state.interpreter)
    private val inlineCommand = JBTextArea(model.state.inlineCommand, 3, 40)
    private val inlineShell = JBTextField(model.state.inlineShell)
    private val workingDirectory = JBTextField(model.state.workingDirectory)
    private val terminalPolicy = enumCombo(
        TerminalPolicy.entries.toTypedArray(),
        initialModel.state.terminalPolicy,
        ::terminalPolicyText,
    )
    private val autoStart = JComboBox(
        arrayOf(
            message("task.editor.autostart.none"),
            message("task.editor.autostart.ide.start"),
            message("task.editor.autostart.project.open"),
        ),
    ).apply {
        selectedIndex = when (initialModel.state.autoStartTrigger) {
            null -> 0
            AutoStartTrigger.IDE_START -> 1
            AutoStartTrigger.PROJECT_OPEN -> 2
        }
    }
    private val startupOrder = JBTextField(model.state.startupOrder.toString())
    private val startupDelay = JBTextField(model.state.startupDelaySeconds.toString())
    private val globalMode = enumCombo(
        GlobalAutoStartMode.entries.toTypedArray(),
        initialModel.state.globalMode,
        ::globalModeText,
    )
    private val favoriteSlot = JBTextField(model.state.favoriteSlot?.toString().orEmpty())
    private val environmentModel = object : DefaultTableModel(
        arrayOf(
            message("task.editor.environment.name"),
            message("task.editor.environment.kind"),
            message("task.editor.environment.value"),
            message("task.editor.environment.status"),
        ),
        0,
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = column != SECRET_STATUS_COLUMN
    }.apply {
        initialModel.state.environmentRows.forEach {
            addRow(arrayOf<Any?>(it.name, it.kind, it.value, secretStatusText(it.kind, null)))
        }
    }
    private val environmentNameEditor = JBTextField()
    private val environmentValueEditor = JBTextField()
    private val environment = JBTable(environmentModel).apply {
        putClientProperty("terminateEditOnFocusLost", true)
        rowHeight = JBUI.scale(24)
        val kinds = enumCombo(EnvironmentRowKind.entries.toTypedArray(), EnvironmentRowKind.PLAIN, ::environmentKindText)
        kinds.addActionListener {
            updatePreview()
        }
        columnModel.getColumn(0).cellEditor = javax.swing.DefaultCellEditor(environmentNameEditor)
        columnModel.getColumn(1).cellEditor = javax.swing.DefaultCellEditor(kinds)
        columnModel.getColumn(1).cellRenderer = LocalizedEnumTableCellRenderer(::environmentKindText)
        columnModel.getColumn(2).cellEditor = javax.swing.DefaultCellEditor(environmentValueEditor)
    }
    private val setSecret = JButton(message("task.editor.button.set.secret")).apply { isEnabled = false }
    private val clearSecret = JButton(message("task.editor.button.clear.secret")).apply { isEnabled = false }
    private val preview = JBTextArea(6, 40).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = dialogTitle
        init()
        setOKButtonText(message("task.editor.button.ok"))
        setCancelButtonText(message("task.editor.button.cancel"))
        initValidation()
        installRefreshListeners()
        installScriptChooser()
        installSecretActions()
        updatePreview()
        refreshSecretStatuses()
    }

    fun resultModel(): TaskEditorModel = model.withState(readState())

    override fun createCenterPanel(): JComponent {
        val content = panel {
        group(message("task.editor.group.basic")) {
            row(message("task.editor.label.id")) { cell(id).align(Align.FILL) }
            row(message("task.editor.label.name")) { cell(name).align(Align.FILL) }
            row(message("task.editor.label.terminal.alias")) { cell(terminalAlias).align(Align.FILL) }
            row(message("task.editor.label.description")) { cell(JBScrollPane(description)).align(Align.FILL) }
            row(message("task.editor.label.scope")) { cell(scope) }
            row { cell(enabled) }
        }
        group(message("task.editor.group.execution")) {
            row(message("task.editor.label.source")) { cell(sourceKind) }
            row(message("task.editor.label.script.path")) { cell(scriptPath).align(Align.FILL) }
            row(message("task.editor.label.arguments")) { cell(JBScrollPane(arguments)).align(Align.FILL) }
            row(message("task.editor.label.interpreter")) { cell(interpreter).align(Align.FILL) }
            row(message("task.editor.label.inline.command")) { cell(JBScrollPane(inlineCommand)).align(Align.FILL) }
            row(message("task.editor.label.inline.shell")) { cell(inlineShell).align(Align.FILL) }
            row(message("task.editor.label.environment")) {
                cell(JBScrollPane(environment).apply { preferredSize = Dimension(JBUI.scale(560), JBUI.scale(120)) })
                    .align(Align.FILL)
            }
            row {
                button(message("task.editor.button.add")) {
                    environmentModel.addRow(
                        arrayOf<Any?>("", EnvironmentRowKind.PLAIN, "", secretStatusText(EnvironmentRowKind.PLAIN, null)),
                    )
                    val row = environmentModel.rowCount - 1
                    environment.setRowSelectionInterval(row, row)
                    environment.editCellAt(row, 0)
                    environment.editorComponent?.requestFocusInWindow()
                }
                button(message("task.editor.button.remove")) {
                    val selected = environment.selectedRow
                    if (selected >= 0) environmentModel.removeRow(selected)
                }
                cell(setSecret)
                cell(clearSecret)
            }
            row { cell(JBLabel(message("task.editor.environment.value.help"))) }
            row { cell(JBLabel(message("task.editor.environment.secret.help"))) }
            row { cell(JBLabel(message("task.editor.environment.powershell.help"))) }
        }
        group(message("task.editor.group.runtime")) {
            row(message("task.editor.label.working.directory")) { cell(workingDirectory).align(Align.FILL) }
            row(message("task.editor.label.terminal.policy")) { cell(terminalPolicy) }
        }
        group(message("task.editor.group.autostart")) {
            row(message("task.editor.label.trigger")) { cell(autoStart) }
            row(message("task.editor.label.order")) { cell(startupOrder) }
            row(message("task.editor.label.delay")) { cell(startupDelay) }
            row(message("task.editor.label.global.mode")) { cell(globalMode) }
        }
        group(message("task.editor.group.shortcut")) {
            row(message("task.editor.label.favorite.slot")) { cell(favoriteSlot) }
        }
        group(message("task.editor.group.preview")) {
            row {
                cell(JBScrollPane(preview).apply {
                    preferredSize = Dimension(JBUI.scale(560), JBUI.scale(130))
                }).align(Align.FILL)
            }
            row {
                button(message("task.editor.button.show.in.explorer")) {
                    showInExplorer(scope.selectedItem as TaskScope)
                }
            }
        }
        }
        return JBScrollPane(
            content,
            JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
        ).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
            preferredSize = Dimension(JBUI.scale(720), JBUI.scale(620))
        }
    }

    override fun doValidate(): ValidationInfo? {
        model = model.withState(readState())
        updatePreview()
        val issue = model.validate().firstOrNull { !it.warning } ?: return null
        return ValidationInfo(issue.message, componentFor(issue.field))
    }

    override fun doOKAction() {
        if (!commitEnvironmentTableEdit(environment)) return
        if (doValidate() != null) return
        super.doOKAction()
    }

    private fun updatePreview() {
        model = model.withState(readState())
        val value = model.preview(previewContext)
        preview.text = buildString {
            appendLine(message("task.editor.preview.working.directory", value.workingDirectory))
            appendLine(message("task.editor.preview.shell", value.shell))
            appendLine(message("task.editor.preview.command", value.command))
            if (value.environment.isNotEmpty()) {
                appendLine(message("task.editor.preview.environment", value.environment.joinToString()))
            }
            if (value.warnings.isNotEmpty()) {
                append(message("task.editor.preview.warnings", value.warnings.joinToString()))
            }
        }.trim()
    }

    private fun installRefreshListeners() {
        val listener = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updatePreview()
        }
        listOf(
            id.document,
            name.document,
            terminalAlias.document,
            description.document,
            scriptPath.textField.document,
            arguments.document,
            interpreter.document,
            inlineCommand.document,
            inlineShell.document,
            workingDirectory.document,
            startupOrder.document,
            startupDelay.document,
            favoriteSlot.document,
        ).forEach { it.addDocumentListener(listener) }
        val environmentEditorListener = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                updatePreview()
            }
        }
        environmentNameEditor.document.addDocumentListener(environmentEditorListener)
        environmentValueEditor.document.addDocumentListener(environmentEditorListener)
        listOf(scope, sourceKind, terminalPolicy, autoStart, globalMode).forEach { combo ->
            combo.addActionListener { updatePreview() }
        }
        enabled.addActionListener { updatePreview() }
        environmentModel.addTableModelListener { event ->
            if (event.firstRow >= 0 &&
                event.firstRow < environmentModel.rowCount &&
                event.column in setOf(ENVIRONMENT_KIND_COLUMN, ENVIRONMENT_VALUE_COLUMN)
            ) {
                refreshSecretStatus(event.firstRow)
            }
            updateSecretButtons()
            updatePreview()
        }
        environment.selectionModel.addListSelectionListener { updateSecretButtons() }
    }

    private fun installScriptChooser() {
        scriptPath.addActionListener {
            val projectPath = Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize()
            val localFileSystem = LocalFileSystem.getInstance()
            val current = TaskScriptPath.resolve(scriptPath.text, projectPath)
                ?.let(localFileSystem::findFileByNioFile)
            val initial = current ?: localFileSystem.findFileByNioFile(projectPath)
            val selected = FileChooser.chooseFile(
                FileChooserDescriptor(
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                ),
                project,
                initial,
            ) ?: return@addActionListener
            val selectedPath = selected.toNioPath().toAbsolutePath().normalize()
            scriptPath.text = TaskScriptPath.store(selectedPath, projectPath)
        }
    }

    private fun installSecretActions() {
        setSecret.addActionListener {
            val selection = selectedSecret() ?: return@addActionListener
            val dialog = SecretValueDialog(project)
            if (!dialog.showAndGet()) return@addActionListener
            val secret = dialog.takeValue() ?: return@addActionListener
            secretScope.launch {
                try {
                    secretStore.set(selection.referenceKey, secret)
                    updateSecretStatus(selection.referenceKey, true)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    showSecretOperationFailure()
                }
            }
        }
        clearSecret.addActionListener {
            val selection = selectedSecret() ?: return@addActionListener
            secretScope.launch {
                try {
                    secretStore.set(selection.referenceKey, null)
                    updateSecretStatus(selection.referenceKey, false)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    showSecretOperationFailure()
                }
            }
        }
    }

    private fun refreshSecretStatuses() {
        (0 until environmentModel.rowCount)
            .mapNotNull { row -> secretAt(row)?.referenceKey }
            .distinct()
            .forEach { key ->
                (0 until environmentModel.rowCount).forEach { row ->
                    if (secretAt(row)?.referenceKey == key) {
                        environmentModel.setValueAt(
                            message("task.editor.environment.secret.checking"),
                            row,
                            SECRET_STATUS_COLUMN,
                        )
                    }
                }
                secretScope.launch {
                    try {
                        val exists = secretStore.exists(key)
                        updateSecretStatus(key, exists)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        updateRowsForKey(key, message("task.editor.environment.secret.not.set"))
                    }
                }
            }
    }

    private fun refreshSecretStatus(row: Int) {
        if (row !in 0 until environmentModel.rowCount) return
        val secret = secretAt(row)
        if (secret == null) {
            environmentModel.setValueAt(
                message("task.editor.environment.secret.not.applicable"),
                row,
                SECRET_STATUS_COLUMN,
            )
            return
        }
        environmentModel.setValueAt(
            message("task.editor.environment.secret.checking"),
            row,
            SECRET_STATUS_COLUMN,
        )
        secretScope.launch {
            try {
                updateSecretStatus(secret.referenceKey, secretStore.exists(secret.referenceKey))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                updateRowsForKey(secret.referenceKey, message("task.editor.environment.secret.not.set"))
            }
        }
    }

    private fun selectedSecret(): SecretSelection? = secretAt(environment.selectedRow)

    private fun secretAt(row: Int): SecretSelection? {
        if (row !in 0 until environmentModel.rowCount) return null
        val kind = environmentModel.getValueAt(row, ENVIRONMENT_KIND_COLUMN) as? EnvironmentRowKind ?: return null
        val key = environmentModel.getValueAt(row, ENVIRONMENT_VALUE_COLUMN)?.toString()?.trim().orEmpty()
        return if (kind == EnvironmentRowKind.SECRET && key.isNotEmpty()) SecretSelection(key) else null
    }

    private fun updateSecretButtons() {
        val enabled = selectedSecret() != null
        setSecret.isEnabled = enabled
        clearSecret.isEnabled = enabled
    }

    private suspend fun updateSecretStatus(key: String, exists: Boolean) {
        updateRowsForKey(
            key,
            message(
                if (exists) "task.editor.environment.secret.set" else "task.editor.environment.secret.not.set",
            ),
        )
    }

    private suspend fun updateRowsForKey(key: String, status: String) = withContext(Dispatchers.EDT) {
        (0 until environmentModel.rowCount).forEach { row ->
            if (secretAt(row)?.referenceKey == key) {
                environmentModel.setValueAt(status, row, SECRET_STATUS_COLUMN)
            }
        }
    }

    private suspend fun showSecretOperationFailure() = withContext(Dispatchers.EDT) {
        setErrorText(message("task.editor.secret.operation.failed"))
    }

    private fun readState(): TaskEditorState = model.state.copy(
        id = id.text.trim(),
        name = name.text,
        terminalAlias = terminalAlias.text,
        description = description.text,
        scope = scope.selectedItem as TaskScope,
        enabled = enabled.isSelected,
        sourceKind = sourceKind.selectedItem as SourceKind,
        scriptPath = scriptPath.text,
        arguments = arguments.text.lines().filter(String::isNotBlank),
        interpreter = interpreter.text,
        inlineCommand = inlineCommand.text,
        inlineShell = inlineShell.text,
        workingDirectory = workingDirectory.text,
        terminalPolicy = terminalPolicy.selectedItem as TerminalPolicy,
        exitDetection = model.state.exitDetection,
        autoStartTrigger = when (autoStart.selectedIndex) {
            1 -> AutoStartTrigger.IDE_START
            2 -> AutoStartTrigger.PROJECT_OPEN
            else -> null
        },
        startupOrder = startupOrder.text.toIntOrNull() ?: -1,
        startupDelaySeconds = startupDelay.text.toIntOrNull() ?: -1,
        globalMode = globalMode.selectedItem as GlobalAutoStartMode,
        favoriteSlot = favoriteSlot.text.trim().takeIf(String::isNotEmpty)?.toIntOrNull() ?: favoriteSlot.text
            .takeIf(String::isNotBlank)?.let { Int.MIN_VALUE },
        environmentRows = (0 until environmentModel.rowCount).map { row ->
            EnvironmentRow(
                name = environmentCellValue(row, 0)?.toString().orEmpty().trim(),
                kind = environmentCellValue(row, ENVIRONMENT_KIND_COLUMN) as? EnvironmentRowKind
                    ?: EnvironmentRowKind.PLAIN,
                value = environmentCellValue(row, ENVIRONMENT_VALUE_COLUMN)?.toString().orEmpty(),
            )
        },
    )

    private fun environmentCellValue(row: Int, column: Int): Any? {
        if (environment.isEditing && environment.editingRow == row && environment.editingColumn == column) {
            return when (val editor = environment.editorComponent) {
                is javax.swing.text.JTextComponent -> editor.text
                is JComboBox<*> -> editor.selectedItem
                else -> environmentModel.getValueAt(row, column)
            }
        }
        return environmentModel.getValueAt(row, column)
    }

    private fun componentFor(field: String): JComponent = when {
        field == "id" -> id
        field == "scriptPath" -> scriptPath
        field == "inlineCommand" -> inlineCommand
        field == "favoriteSlot" -> favoriteSlot
        field == "startupOrder" -> startupOrder
        field == "startupDelaySeconds" -> startupDelay
        field.startsWith("environment.") -> environment
        else -> id
    }

    override fun dispose() {
        secretScope.cancel()
        super.dispose()
    }

    private fun sourceKindText(value: SourceKind): String = message(
        when (value) {
            SourceKind.SCRIPT -> "task.editor.source.script"
            SourceKind.INLINE -> "task.editor.source.inline"
        },
    )

    private fun scopeText(value: TaskScope): String = message(
        when (value) {
            TaskScope.GLOBAL -> "task.editor.scope.global"
            TaskScope.PROJECT_SHARED -> "task.editor.scope.project.shared"
            TaskScope.PROJECT_PRIVATE -> "task.editor.scope.project.private"
        },
    )

    private fun terminalPolicyText(value: TerminalPolicy): String = message(
        when (value) {
            TerminalPolicy.REUSE_TASK_TERMINAL -> "task.editor.terminal.reuse.task"
            TerminalPolicy.REUSE_SHARED -> "task.editor.terminal.reuse.shared"
            TerminalPolicy.ALWAYS_NEW -> "task.editor.terminal.always.new"
        },
    )

    private fun globalModeText(value: GlobalAutoStartMode): String = message(
        when (value) {
            GlobalAutoStartMode.ONCE_PER_IDE_SESSION -> "task.editor.global.once"
            GlobalAutoStartMode.ONCE_PER_PROJECT -> "task.editor.global.each.project"
        },
    )

    private fun environmentKindText(value: EnvironmentRowKind): String = message(
        when (value) {
            EnvironmentRowKind.PLAIN -> "task.editor.environment.plain"
            EnvironmentRowKind.SYSTEM -> "task.editor.environment.system"
            EnvironmentRowKind.SECRET -> "task.editor.environment.secret"
        },
    )

    private fun secretStatusText(kind: EnvironmentRowKind, exists: Boolean?): String = when {
        kind != EnvironmentRowKind.SECRET -> message("task.editor.environment.secret.not.applicable")
        exists == true -> message("task.editor.environment.secret.set")
        else -> message("task.editor.environment.secret.not.set")
    }

    private fun <T> enumCombo(values: Array<T>, selected: T, text: (T) -> String): JComboBox<T> =
        JComboBox(values).apply {
            selectedItem = selected
            renderer = LocalizedComboRenderer(text)
        }

    private fun message(key: String, vararg values: Any): String = DevWorkspaceBundle.message(key, *values)

    private data class SecretSelection(val referenceKey: String)

    private class LocalizedComboRenderer<T>(
        private val text: (T) -> String,
    ) : DefaultListCellRenderer() {
        @Suppress("UNCHECKED_CAST")
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component = super.getListCellRendererComponent(
            list,
            value?.let { text(it as T) }.orEmpty(),
            index,
            isSelected,
            cellHasFocus,
        )
    }

    private class LocalizedEnumTableCellRenderer(
        private val text: (EnvironmentRowKind) -> String,
    ) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: javax.swing.JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component = super.getTableCellRendererComponent(
            table,
            (value as? EnvironmentRowKind)?.let(text).orEmpty(),
            isSelected,
            hasFocus,
            row,
            column,
        )
    }

    private class SecretValueDialog(project: Project) : DialogWrapper(project, true) {
        private val password = JBPasswordField()
        private var submitted: String? = null

        init {
            title = DevWorkspaceBundle.message("task.editor.secret.dialog.title")
            init()
            setOKButtonText(DevWorkspaceBundle.message("task.editor.button.ok"))
            setCancelButtonText(DevWorkspaceBundle.message("task.editor.button.cancel"))
        }

        override fun createCenterPanel(): JComponent = panel {
            row(DevWorkspaceBundle.message("task.editor.secret.dialog.label")) {
                cell(password).align(Align.FILL)
            }
        }

        override fun doOKAction() {
            val characters = password.password
            submitted = String(characters)
            characters.fill('\u0000')
            password.text = ""
            super.doOKAction()
        }

        fun takeValue(): String? = submitted.also { submitted = null }
    }

    private companion object {
        const val ENVIRONMENT_KIND_COLUMN = 1
        const val ENVIRONMENT_VALUE_COLUMN = 2
        const val SECRET_STATUS_COLUMN = 3
    }
}

internal fun commitEnvironmentTableEdit(table: JBTable): Boolean {
    if (!table.isEditing) return true
    val editor = table.cellEditor ?: return false
    return editor.stopCellEditing()
}

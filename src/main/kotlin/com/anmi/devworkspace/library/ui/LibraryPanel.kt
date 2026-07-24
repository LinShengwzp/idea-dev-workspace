package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.library.search.LibraryQuery
import com.anmi.devworkspace.library.search.LibrarySearchEngine
import com.anmi.devworkspace.library.search.LibrarySearchRecord
import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.files.LibraryPathResolver
import com.anmi.devworkspace.library.service.LibraryMutationService
import com.anmi.devworkspace.library.service.LibraryService
import com.anmi.devworkspace.library.service.LibraryState
import com.anmi.devworkspace.library.storage.MarkdownContentStore
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {
    private val library = project.service<LibraryService>()
    private val mutations = project.service<LibraryMutationService>()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val presentation = LibraryListModel(LibrarySearchEngine())
    private val listModel = DefaultListModel<LibraryListItem>()
    private val list = JBList(listModel).apply {
        cellRenderer = LibraryItemRenderer()
        emptyText.text = message("library.empty.title")
    }
    private val search = SearchTextField()
    private val details = DetailsPanel()
    private var records: List<LibrarySearchRecord> = emptyList()
    private var query = LibraryQuery()
    private var pendingReveal: LibraryItemKey? = null

    private val filterPanel = LibraryFilterPanel { updated ->
        query = updated
        render()
    }

    init {
        setToolbar(createToolbar())
        setContent(createMainContent())
        search.textEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                filterPanel.updateText(search.text)
            }
        })
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                details.show(list.selectedValue)
                updateToolbarActions()
            }
        }
        collectState()
        updateToolbarActions()
    }

    fun focusSearch() {
        search.requestFocusInWindow()
        search.textEditor.requestFocusInWindow()
    }

    fun reveal(key: LibraryItemKey) {
        pendingReveal = key
        search.text = ""
        render()
        focusSearch()
    }

    override fun dispose() {
        project.service<LibraryUiController>().detach(this)
        coroutineScope.cancel()
    }

    private fun createMainContent(): JComponent {
        val center = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(search, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }
        val contentAndDetails = JBSplitter(false, 0.68f).apply {
            firstComponent = center
            secondComponent = details
            dividerWidth = JBUI.scale(4)
        }
        return JBSplitter(false, 0.22f).apply {
            firstComponent = filterPanel
            secondComponent = contentAndDetails
            dividerWidth = JBUI.scale(4)
        }
    }

    private val contextualActions = mutableListOf<PanelAction>()

    private fun createToolbar(): JComponent {
        val actions = listOf(
            PanelAction("library.action.new", AllIcons.General.Add) { openNew() },
            PanelAction("library.action.manage.groups", AllIcons.Nodes.Folder) { manageGroups() },
            PanelAction("library.action.edit", AllIcons.Actions.Edit, requiresSelection = true) {
                selectedItem()?.let { openEditor(it, copy = false) }
            },
            PanelAction("library.action.copy", AllIcons.Actions.Copy, requiresSelection = true) {
                selectedItem()?.let { openEditor(it, copy = true) }
            },
            PanelAction("library.action.favorite", AllIcons.Nodes.Favorite, requiresSelection = true) {
                selectedItem()?.let { selected -> coroutineScope.launch { mutations.toggleFavorite(selected) } }
            },
            PanelAction("library.action.open", AllIcons.Actions.MenuOpen, requiresSelection = true),
            PanelAction("library.action.relocate", AllIcons.Actions.MenuOpen, requiresSelection = true) {
                selectedItem()?.let(::relocate)
            },
            PanelAction("library.action.import", AllIcons.ToolbarDecorator.Import),
            PanelAction("library.action.export", AllIcons.ToolbarDecorator.Export),
            PanelAction("library.action.delete", AllIcons.General.Remove, requiresSelection = true) {
                selectedItem()?.let(::delete)
            },
        )
        contextualActions += actions
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "DevLibrary",
            DefaultActionGroup(actions),
            true,
        )
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun collectState() {
        coroutineScope.launch {
            library.state.collect { state ->
                val nextRecords = records(state)
                withContext(Dispatchers.EDT) {
                    records = nextRecords
                    render()
                }
            }
        }
    }

    private fun records(state: LibraryState): List<LibrarySearchRecord> {
        val groups = state.groups.associateBy { it.scope to it.id }
        return state.items.map { item ->
            val group = item.groupId?.let { id -> groups[item.scope to id] }
            LibrarySearchRecord(
                item = item,
                groupName = group?.name,
                groupOrder = group?.order ?: Int.MAX_VALUE,
            )
        }
    }

    private fun render() {
        val selectedKey = pendingReveal ?: list.selectedValue?.key
        val rows = presentation.present(records, query)
        listModel.clear()
        rows.forEach(listModel::addElement)
        list.selectedIndex = presentation.selectionIndex(rows, selectedKey)
        if (list.selectedIndex >= 0) {
            pendingReveal = null
            list.ensureIndexIsVisible(list.selectedIndex)
        }
        details.show(list.selectedValue)
        updateToolbarActions()
    }

    private fun updateToolbarActions() {
        contextualActions.forEach { action ->
            action.enabled = !action.requiresSelection || list.selectedValue != null
        }
    }

    private fun selectedItem(): LibraryItem? = list.selectedValue?.item

    private fun manageGroups() {
        val original = library.state.value.groups
        val dialog = LibraryGroupManagerDialog(project, original)
        if (!dialog.showAndGet()) return
        val updated = dialog.result ?: return
        val keys = updated.mapTo(hashSetOf()) { it.scope to it.id }
        coroutineScope.launch {
            original.filterNot { (it.scope to it.id) in keys }.forEach { mutations.deleteGroup(it) }
            updated.forEach { mutations.saveGroup(it) }
        }
    }

    private fun openNew() {
        val dialog = LibraryEditorDialog(
            project,
            LibraryEditorState(
                title = "",
                type = LibraryItemType.MARKDOWN,
                scope = LibraryScope.PROJECT_PRIVATE,
            ),
            library.state.value.groups,
        )
        if (!dialog.showAndGet()) return
        dialog.result?.let { state -> coroutineScope.launch { mutations.create(state) } }
    }

    private fun openEditor(item: LibraryItem, copy: Boolean) {
        coroutineScope.launch {
            val markdown = if (item.type == LibraryItemType.MARKDOWN) {
                MarkdownContentStore(library.path(item.scope).parent).read(item.id)
            } else {
                null
            }
            withContext(Dispatchers.EDT) {
                val dialog = LibraryEditorDialog(
                    project,
                    item.toEditorState(if (copy) null else item.id, markdown),
                    library.state.value.groups,
                )
                if (!dialog.showAndGet()) return@withContext
                val state = dialog.result ?: return@withContext
                coroutineScope.launch {
                    if (copy) mutations.create(state) else mutations.update(item, state)
                }
            }
        }
    }

    private fun relocate(item: LibraryItem) {
        if (item.type == LibraryItemType.MARKDOWN || item.type == LibraryItemType.LINK) return
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
        val selected = FileChooser.chooseFile(descriptor, project, null) ?: return
        val base = project.basePath?.let(java.nio.file.Path::of) ?: return
        val target = LibraryPathResolver(base).persist(selected.toNioPath())
        coroutineScope.launch { mutations.relocate(item, target) }
    }

    private fun delete(item: LibraryItem) {
        if (
            Messages.showYesNoDialog(
                project,
                message("library.delete.message", item.title),
                message("library.delete.title"),
                Messages.getQuestionIcon(),
            ) != Messages.YES
        ) {
            return
        }
        coroutineScope.launch { mutations.delete(item) }
    }

    private fun LibraryItem.toEditorState(editorId: String?, markdown: String?): LibraryEditorState =
        LibraryEditorState(
            id = editorId,
            title = title,
            type = type,
            scope = scope,
            groupId = groupId,
            tags = tags,
            note = note,
            favorite = favorite,
            target = target,
            markdown = markdown,
        )

    private inner class PanelAction(
        key: String,
        icon: javax.swing.Icon,
        val requiresSelection: Boolean = false,
        private val callback: (() -> Unit)? = null,
    ) : AnAction(message(key), null, icon) {
        var enabled: Boolean = true

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = callback != null && enabled
        }

        override fun actionPerformed(event: AnActionEvent) {
            callback?.invoke()
        }
    }

    private class DetailsPanel : JBPanel<DetailsPanel>(CardLayout()) {
        private val cards = layout as CardLayout
        private val empty = JBLabel(message("library.details.empty"))
        private val title = JBLabel()
        private val metadata = JBLabel()
        private val note = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty()
        }
        private val item = JBPanel<JBPanel<*>>(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(8)
            add(
                JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    add(title)
                },
                BorderLayout.NORTH,
            )
            add(JBScrollPane(note), BorderLayout.CENTER)
            add(metadata, BorderLayout.SOUTH)
        }

        init {
            border = JBUI.Borders.empty()
            add(empty, EMPTY)
            add(item, ITEM)
            cards.show(this, EMPTY)
        }

        fun show(value: LibraryListItem?) {
            if (value == null) {
                cards.show(this, EMPTY)
                return
            }
            title.text = value.item.title
            note.text = value.item.note.orEmpty()
            metadata.text = message(
                "library.details.metadata",
                message("library.type.${value.item.type.name.lowercase()}"),
                message("library.scope.${value.item.scope.name.lowercase().replace('_', '.')}"),
            )
            cards.show(this, ITEM)
        }

        private companion object {
            const val EMPTY = "empty"
            const val ITEM = "item"
        }
    }

    private companion object {
        fun message(key: String, vararg params: Any): String =
            DevWorkspaceBundle.message(key, *params)
    }
}

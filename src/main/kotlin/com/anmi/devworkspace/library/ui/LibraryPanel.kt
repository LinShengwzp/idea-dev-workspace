package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.library.search.LibraryQuery
import com.anmi.devworkspace.library.search.LibrarySearchEngine
import com.anmi.devworkspace.library.search.LibrarySearchRecord
import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.files.LibraryPathResolver
import com.anmi.devworkspace.library.files.LibraryFileStatusService
import com.anmi.devworkspace.library.open.IdeaLibraryItemOpener
import com.anmi.devworkspace.library.service.LibraryMutationService
import com.anmi.devworkspace.library.service.LibraryService
import com.anmi.devworkspace.library.service.LibraryState
import com.anmi.devworkspace.library.service.LibraryTransferService
import com.anmi.devworkspace.library.preview.ImagePreview
import com.anmi.devworkspace.library.preview.ImagePreviewService
import com.anmi.devworkspace.library.preview.ImagePreviewSize
import com.anmi.devworkspace.library.preview.MarkdownPreviewService
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
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
    private val transfers = project.service<LibraryTransferService>()
    private val pathResolver = LibraryPathResolver(Path.of(requireNotNull(project.basePath)))
    private val fileStatus = LibraryFileStatusService(pathResolver)
    private val imagePreview = ImagePreviewService()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val presentation = LibraryListModel(LibrarySearchEngine())
    private val listModel = DefaultListModel<LibraryListItem>()
    private val list = JBList(listModel).apply {
        cellRenderer = LibraryItemRenderer()
        emptyText.text = message("library.empty.title")
    }
    private val search = SearchTextField()
    private val details = DetailsPanel()
    private val errorBanner = JBLabel(message("library.error.banner")).apply {
        icon = AllIcons.General.Warning
        border = JBUI.Borders.empty(5, 8)
        isVisible = false
    }
    private val opener by lazy {
        IdeaLibraryItemOpener(
            project = project,
            resolver = pathResolver,
            statusService = fileStatus,
            showMarkdownPreview = { item -> refreshDetails(item) },
            showImagePreview = { item, _ -> refreshDetails(item) },
            showMissing = { item ->
                Messages.showWarningDialog(
                    project,
                    message("library.open.missing.message", item.title),
                    message("library.open.missing.title"),
                )
            },
        )
    }
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
                list.selectedValue?.item?.let(::refreshDetails)
                updateToolbarActions()
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1) openSelected()
            }
        })
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
        filterPanel.clearFilters()
        render()
        focusSearch()
    }

    override fun dispose() {
        project.service<LibraryUiController>().detach(this)
        imagePreview.dispose()
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
        val split = JBSplitter(false, 0.22f).apply {
            firstComponent = filterPanel
            secondComponent = contentAndDetails
            dividerWidth = JBUI.scale(4)
        }
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(errorBanner, BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)
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
            PanelAction("library.action.open", AllIcons.Actions.MenuOpen, requiresSelection = true) { openSelected() },
            PanelAction("library.action.relocate", AllIcons.Actions.MenuOpen, requiresSelection = true) {
                selectedItem()?.let(::relocate)
            },
            PanelAction("library.action.import", AllIcons.ToolbarDecorator.Import) { importArchive() },
            PanelAction("library.action.export", AllIcons.ToolbarDecorator.Export) { exportArchive() },
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
                    errorBanner.isVisible = state.errors.isNotEmpty()
                    errorBanner.toolTipText = state.errors.values.joinToString("<br>", "<html>", "</html>") {
                        "${it.sourceFile}: ${it.message}"
                    }
                    render()
                }
            }
        }
    }

    private suspend fun records(state: LibraryState): List<LibrarySearchRecord> {
        val groups = state.groups.associateBy { it.scope to it.id }
        return state.items.map { item ->
            val group = item.groupId?.let { id -> groups[item.scope to id] }
            LibrarySearchRecord(
                item = item,
                groupName = group?.name,
                groupOrder = group?.order ?: Int.MAX_VALUE,
                markdownBody = if (item.type == LibraryItemType.MARKDOWN) {
                    MarkdownContentStore(library.path(item.scope).parent).read(item.id)
                } else {
                    null
                },
                pathState = fileStatus.check(item),
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
        list.selectedValue?.item?.let(::refreshDetails)
        updateToolbarActions()
    }

    private fun updateToolbarActions() {
        contextualActions.forEach { action ->
            action.enabled = !action.requiresSelection || list.selectedValue != null
        }
    }

    private fun selectedItem(): LibraryItem? = list.selectedValue?.item

    private fun openSelected() {
        selectedItem()?.let { item -> coroutineScope.launch { opener.open(item) } }
    }

    private fun refreshDetails(item: LibraryItem) {
        val key = LibraryItemKey(item.scope, item.id)
        coroutineScope.launch {
            when (item.type) {
                LibraryItemType.MARKDOWN -> {
                    val preview = MarkdownPreviewService(
                        MarkdownContentStore(library.path(item.scope).parent),
                    ).load(item.id)
                    withContext(Dispatchers.EDT) {
                        if (list.selectedValue?.key == key) details.showHtml(preview.renderedHtml)
                    }
                }
                LibraryItemType.IMAGE -> {
                    val target = item.target?.let(pathResolver::resolve) ?: return@launch
                    val preview = imagePreview.load(target, ImagePreviewSize(720, 520))
                    withContext(Dispatchers.EDT) {
                        if (list.selectedValue?.key == key) details.showImage(preview)
                    }
                }
                else -> Unit
            }
        }
    }

    private fun importArchive() {
        val selected = FileChooser.chooseFile(
            FileChooserDescriptor(true, false, false, false, false, false)
                .withFileFilter { it.extension.equals("zip", ignoreCase = true) },
            project,
            null,
        ) ?: return
        val scope = chooseScope("library.import.scope") ?: return
        coroutineScope.launch {
            val result = transfers.importArchive(selected.toNioPath(), scope)
            withContext(Dispatchers.EDT) {
                Messages.showInfoMessage(
                    project,
                    message("library.import.complete", result.importedItemIds.size),
                    message("library.action.import"),
                )
            }
        }
    }

    private fun exportArchive() {
        val directory = FileChooser.chooseFile(
            FileChooserDescriptor(false, true, false, false, false, false),
            project,
            null,
        ) ?: return
        val scope = chooseScope("library.export.scope") ?: return
        val output = directory.toNioPath().resolve("dev-library-${scope.name.lowercase()}.zip")
        coroutineScope.launch {
            val result = transfers.export(scope, output)
            withContext(Dispatchers.EDT) {
                Messages.showInfoMessage(
                    project,
                    message("library.export.complete", result.exportedItemIds.size, output),
                    message("library.action.export"),
                )
            }
        }
    }

    private fun chooseScope(messageKey: String): LibraryScope? {
        val scopes = LibraryScope.entries
        val labels = scopes.map { message("library.scope.${it.name.lowercase().replace('_', '.')}") }.toTypedArray()
        val selectedIndex = Messages.showChooseDialog(
            project,
            message(messageKey),
            message(messageKey),
            Messages.getQuestionIcon(),
            labels,
            labels.last(),
        )
        return scopes.getOrNull(selectedIndex)
    }

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
        private val preview = JEditorPane("text/html", "").apply {
            isEditable = false
            border = JBUI.Borders.empty()
        }
        private val image = JLabel().apply {
            horizontalAlignment = JLabel.CENTER
            verticalAlignment = JLabel.CENTER
        }
        private val bodyCards = CardLayout()
        private val body = JBPanel<JBPanel<*>>(bodyCards).apply {
            add(JBScrollPane(note), NOTE)
            add(JBScrollPane(preview), PREVIEW)
            add(JBScrollPane(image), IMAGE)
        }
        private val item = JBPanel<JBPanel<*>>(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(8)
            add(
                JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    add(title)
                },
                BorderLayout.NORTH,
            )
            add(body, BorderLayout.CENTER)
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
            bodyCards.show(body, NOTE)
            metadata.text = message(
                "library.details.metadata",
                message("library.type.${value.item.type.name.lowercase()}"),
                message("library.scope.${value.item.scope.name.lowercase().replace('_', '.')}"),
            )
            cards.show(this, ITEM)
        }

        fun showHtml(html: String) {
            preview.text = html
            preview.caretPosition = 0
            bodyCards.show(body, PREVIEW)
        }

        fun showImage(value: ImagePreview) {
            image.icon = (value as? ImagePreview.Available)?.let { ImageIcon(it.image) }
            image.text = if (value is ImagePreview.Placeholder) message("library.preview.unavailable") else null
            bodyCards.show(body, IMAGE)
        }

        private companion object {
            const val EMPTY = "empty"
            const val ITEM = "item"
            const val NOTE = "note"
            const val PREVIEW = "preview"
            const val IMAGE = "image"
        }
    }

    private companion object {
        fun message(key: String, vararg params: Any): String =
            DevWorkspaceBundle.message(key, *params)
    }
}

package com.anmi.devworkspace.library.actions

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.service.LibraryMutationService
import com.anmi.devworkspace.library.service.LibraryService
import com.anmi.devworkspace.library.ui.LibraryEditorDialog
import com.anmi.devworkspace.library.ui.LibraryEditorState
import com.anmi.devworkspace.library.ui.LibraryItemKey
import com.anmi.devworkspace.library.ui.LibraryQuickAddDialog
import com.anmi.devworkspace.library.ui.LibraryToolWindowFactory
import com.anmi.devworkspace.library.ui.LibraryUiController
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.datatransfer.DataFlavor
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * IntelliJ adapter for DataContext capture and dialogs.
 *
 * The action copies only editor/VFS metadata on EDT. Repository and Markdown
 * writes are delegated to the mutation service from a background coroutine.
 */
@Service(Service.Level.PROJECT)
class LibraryQuickAddService(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val extractor = LibraryContextExtractor()
    private val library: LibraryService = project.service()
    private val mutations: LibraryMutationService = project.service()

    fun open(dataContext: DataContext) {
        val drafts = capture(dataContext)
        if (drafts.isEmpty()) return
        val properties = PropertiesComponent.getInstance(project)
        val dialog = LibraryQuickAddDialog(
            project = project,
            drafts = drafts,
            groups = library.state.value.groups,
            rememberedGroup = properties.getValue(LAST_GROUP),
            rememberedTags = properties.getValue(LAST_TAGS)
                ?.split(TAG_SEPARATOR)
                ?.filterTo(linkedSetOf()) { it.isNotEmpty() }
                .orEmpty(),
        )
        dialog.show()
        dialog.fullEdit?.let(::openFullEditor)
        val states = dialog.results ?: return
        states.firstOrNull()?.let { state ->
            state.groupId?.let { properties.setValue(LAST_GROUP, it) }
            properties.setValue(LAST_TAGS, state.tags.joinToString(TAG_SEPARATOR))
        }
        scope.launch(Dispatchers.Default) {
            val created = states.map { mutations.create(it) }
            withContext(Dispatchers.EDT) { notifySaved(created) }
        }
    }

    fun capture(dataContext: DataContext): List<LibraryQuickAddDraft> {
        val base = project.basePath?.let(Path::of) ?: return emptyList()
        val editor = CommonDataKeys.EDITOR.getData(dataContext)
        val editorFile = CommonDataKeys.VIRTUAL_FILE.getData(dataContext)
        val contextComponent = PlatformCoreDataKeys.CONTEXT_COMPONENT.getData(dataContext)
        val focusedEditor = editor != null &&
            contextComponent != null &&
            SwingUtilities.isDescendingFrom(contextComponent, editor.component)
        val editorCapture = if (focusedEditor && editorFile?.isDirectory == false) {
            captureEditor(base, editor, editorFile)
        } else {
            null
        }
        val selectedFiles = if (focusedEditor) {
            emptyList()
        } else {
            CommonDataKeys.VIRTUAL_FILE_ARRAY.getData(dataContext)
                ?.filterNot(VirtualFile::isDirectory)
                .orEmpty()
                .map { file -> EditorCapture(base, file.toNioPath()) }
        }
        val clipboard = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor) as? String
        return extractor.resolve(
            LibraryCaptureCandidates(
                editorSelection = editorCapture?.takeIf { it.selection != null },
                projectFiles = selectedFiles,
                focusedEditor = editorCapture?.takeIf { it.selection == null },
                clipboard = clipboard,
            ),
        )
    }

    private fun captureEditor(
        base: Path,
        editor: com.intellij.openapi.editor.Editor,
        file: VirtualFile,
    ): EditorCapture {
        val selection = editor.selectionModel.selectedText
        val start = editor.selectionModel.selectionStart
        val end = editor.selectionModel.selectionEnd
        return EditorCapture(
            projectDirectory = base,
            file = file.toNioPath(),
            selection = selection,
            startLine = selection?.let { editor.document.getLineNumber(start) + 1 },
            endLine = selection?.let {
                editor.document.getLineNumber((end - 1).coerceAtLeast(start)) + 1
            },
            language = selection?.let { language(file) },
        )
    }

    private fun openFullEditor(initial: LibraryEditorState) {
        val dialog = LibraryEditorDialog(project, initial, library.state.value.groups)
        if (!dialog.showAndGet()) return
        val state = dialog.result ?: return
        scope.launch(Dispatchers.Default) {
            val created = mutations.create(state)
            withContext(Dispatchers.EDT) { notifySaved(listOf(created)) }
        }
    }

    private fun notifySaved(items: List<LibraryItem>) {
        val first = items.first()
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Dev Workspace Library")
            .createNotification(
                message("library.quick.add.saved", items.size),
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimple(message("library.quick.add.view")) {
                ToolWindowManager.getInstance(project).getToolWindow(LibraryToolWindowFactory.ID)?.activate {
                    project.service<LibraryUiController>().reveal(LibraryItemKey(first.scope, first.id))
                }
            })
            .notify(project)
    }

    private fun language(file: VirtualFile): String =
        file.extension?.lowercase().orEmpty().ifBlank { file.fileType.name.lowercase() }

    private companion object {
        const val LAST_GROUP = "dev.workspace.library.quick.add.group"
        const val LAST_TAGS = "dev.workspace.library.quick.add.tags"
        const val TAG_SEPARATOR = "\u001f"

        fun message(key: String, vararg params: Any): String =
            DevWorkspaceBundle.message(key, *params)
    }
}

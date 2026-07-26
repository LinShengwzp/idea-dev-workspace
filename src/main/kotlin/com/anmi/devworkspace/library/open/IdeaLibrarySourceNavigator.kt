package com.anmi.devworkspace.library.open

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.library.domain.LibraryItemSource
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * IntelliJ adapter for source navigation.
 *
 * Filesystem/VFS refresh stays off EDT. The pure navigator determines the
 * one-based range; this adapter only translates it to editor offsets.
 */
class IdeaLibrarySourceNavigator(
    private val project: Project,
    private val decisions: LibrarySourceNavigator = LibrarySourceNavigator(
        project.basePath?.let(java.nio.file.Path::of),
    ),
) {
    suspend fun open(source: LibraryItemSource): LibrarySourceNavigationDecision {
        val resolved = decisions.resolve(source).getOrNull()
        if (resolved == null) {
            val unavailable = decisions.decide(source, exists = false, lineCount = null)
            showUnavailable(unavailable)
            return unavailable
        }
        val virtualFile = withContext(Dispatchers.IO) {
            try {
                if (!Files.isRegularFile(resolved)) {
                    null
                } else {
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolved)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (cancellation: ProcessCanceledException) {
                throw cancellation
            } catch (_: RuntimeException) {
                null
            }
        }
        if (virtualFile == null) {
            val unavailable = LibrarySourceNavigationDecision.Unavailable(
                LibrarySourceUnavailableReason.MISSING_FILE,
            )
            showUnavailable(unavailable)
            return unavailable
        }
        return withContext(Dispatchers.EDT) {
            val editor = FileEditorManager.getInstance(project).openTextEditor(
                OpenFileDescriptor(project, virtualFile),
                true,
            )
            if (editor == null) {
                val unavailable = LibrarySourceNavigationDecision.Unavailable(
                    LibrarySourceUnavailableReason.MISSING_FILE,
                )
                showUnavailableOnEdt(unavailable)
                return@withContext unavailable
            }
            val decision = decisions.decide(
                source = source,
                exists = true,
                lineCount = editor.document.lineCount,
            )
            if (decision is LibrarySourceNavigationDecision.Navigate) {
                applyDecision(editor, decision)
                decision.warning?.let(::showWarningOnEdt)
            }
            decision
        }
    }

    private fun applyDecision(
        editor: com.intellij.openapi.editor.Editor,
        decision: LibrarySourceNavigationDecision.Navigate,
    ) {
        val document = editor.document
        val caretIndex = decision.caretLine - 1
        editor.caretModel.moveToOffset(document.getLineStartOffset(caretIndex))
        val selectionStart = decision.selectionStartLine
        val selectionEnd = decision.selectionEndLine
        if (selectionStart != null && selectionEnd != null) {
            editor.selectionModel.setSelection(
                document.getLineStartOffset(selectionStart - 1),
                document.getLineEndOffset(selectionEnd - 1),
            )
        } else {
            editor.selectionModel.removeSelection()
        }
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    private suspend fun showUnavailable(decision: LibrarySourceNavigationDecision) {
        withContext(Dispatchers.EDT) { showUnavailableOnEdt(decision) }
    }

    private fun showUnavailableOnEdt(decision: LibrarySourceNavigationDecision) {
        val reason = (decision as? LibrarySourceNavigationDecision.Unavailable)?.reason ?: return
        val key = when (reason) {
            LibrarySourceUnavailableReason.NO_PROJECT -> "library.source.no.project"
            LibrarySourceUnavailableReason.INVALID_PATH -> "library.source.invalid.path"
            LibrarySourceUnavailableReason.MISSING_FILE -> "library.source.missing"
        }
        Messages.showWarningDialog(project, message(key), message("library.source.warning.title"))
    }

    private fun showWarningOnEdt(warning: LibrarySourceNavigationWarning) {
        val key = when (warning) {
            LibrarySourceNavigationWarning.START_BEYOND_EOF -> "library.source.start.beyond.eof"
            LibrarySourceNavigationWarning.END_TRUNCATED -> "library.source.end.truncated"
        }
        Messages.showWarningDialog(project, message(key), message("library.source.warning.title"))
    }

    private fun message(key: String): String = DevWorkspaceBundle.message(key)
}

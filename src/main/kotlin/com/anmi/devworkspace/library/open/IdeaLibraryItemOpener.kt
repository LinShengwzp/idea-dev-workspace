package com.anmi.devworkspace.library.open

import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.files.LibraryFileStatusService
import com.anmi.devworkspace.library.files.LibraryPathResolver
import com.anmi.devworkspace.library.search.LibraryPathState
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Desktop
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * IntelliJ adapter for [LibraryItemOpener].
 *
 * VFS discovery and filesystem status checks run away from the EDT; editor,
 * browser, and preview callbacks are dispatched on the EDT. System application
 * launch stays on the IO dispatcher because desktop integration may block.
 */
class IdeaLibraryItemOpener(
    private val project: Project,
    private val resolver: LibraryPathResolver,
    private val statusService: LibraryFileStatusService,
    private val decisions: LibraryOpenDecisionEngine = LibraryOpenDecisionEngine(),
    private val showMarkdownPreview: suspend (LibraryItem) -> Unit,
    private val showImagePreview: suspend (LibraryItem, Path) -> Unit,
    private val showMissing: suspend (LibraryItem) -> Unit,
) : LibraryItemOpener {
    override suspend fun open(
        item: LibraryItem,
        preference: LibraryOpenPreference,
    ): LibraryOpenDecision {
        val pathState = statusService.check(item)
        val path = if (pathState == LibraryPathState.AVAILABLE) {
            runCatching { resolveTarget(item) }.getOrNull()
        } else {
            null
        }
        val effectivePathState = if (item.type in FILE_TYPES && path == null) {
            LibraryPathState.MISSING
        } else {
            pathState
        }
        val virtualFile = if (path != null && shouldInspectIdeaType(item, preference)) {
            withContext(Dispatchers.IO) {
                try {
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (cancellation: ProcessCanceledException) {
                    throw cancellation
                } catch (_: RuntimeException) {
                    null
                }
            }
        } else {
            null
        }
        val decision = decisions.decide(
            item = item,
            pathState = effectivePathState,
            ideaReadable = virtualFile?.fileType != UnknownFileType.INSTANCE && virtualFile != null,
            preference = preference,
        )
        execute(decision.action, item, path, virtualFile)
        return decision
    }

    private suspend fun execute(
        action: LibraryOpenAction,
        item: LibraryItem,
        path: Path?,
        virtualFile: VirtualFile?,
    ) {
        when (action) {
            LibraryOpenAction.BROWSER -> withContext(Dispatchers.EDT) {
                BrowserUtil.browse(requireNotNull(item.target))
            }
            LibraryOpenAction.MARKDOWN_PREVIEW -> withContext(Dispatchers.EDT) {
                showMarkdownPreview(item)
            }
            LibraryOpenAction.IDEA_EDITOR -> withContext(Dispatchers.EDT) {
                FileEditorManager.getInstance(project).openFile(requireNotNull(virtualFile), true)
            }
            LibraryOpenAction.IMAGE_PREVIEW -> withContext(Dispatchers.EDT) {
                showImagePreview(item, requireNotNull(path))
            }
            LibraryOpenAction.SYSTEM -> withContext(Dispatchers.IO) {
                val resolved = requireNotNull(path)
                if (!Desktop.isDesktopSupported()) {
                    withContext(Dispatchers.EDT) { showMissing(item) }
                    return@withContext
                }
                try {
                    Desktop.getDesktop().open(resolved.toFile())
                } catch (_: IOException) {
                    withContext(Dispatchers.EDT) { showMissing(item) }
                } catch (_: SecurityException) {
                    withContext(Dispatchers.EDT) { showMissing(item) }
                } catch (_: UnsupportedOperationException) {
                    withContext(Dispatchers.EDT) { showMissing(item) }
                }
            }
            LibraryOpenAction.MISSING -> withContext(Dispatchers.EDT) {
                showMissing(item)
            }
        }
    }

    private fun resolveTarget(item: LibraryItem): Path? =
        if (item.type in FILE_TYPES) item.target?.let(resolver::resolve) else null

    private fun shouldInspectIdeaType(
        item: LibraryItem,
        preference: LibraryOpenPreference,
    ): Boolean =
        item.type == LibraryItemType.FILE ||
            (item.type == LibraryItemType.IMAGE && preference == LibraryOpenPreference.IDEA)

    private companion object {
        val FILE_TYPES = setOf(LibraryItemType.FILE, LibraryItemType.IMAGE, LibraryItemType.MEDIA)
    }
}

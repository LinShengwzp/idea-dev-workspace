package com.anmi.devworkspace.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class EditorPosition(val line: Int, val column: Int)

class TaskConfigNavigation(
    private val project: Project,
    private val missingFile: (String) -> Unit,
) {
    suspend fun open(path: Path, line: Int = 1, column: Int = 1) {
        val file = withContext(Dispatchers.IO) {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path.toAbsolutePath().normalize())
        }
        withContext(Dispatchers.EDT) {
            if (file == null) {
                missingFile(DevWorkspaceBundle.message("task.navigation.missing"))
            } else {
                val position = editorPosition(line, column)
                FileEditorManager.getInstance(project).openTextEditor(
                    OpenFileDescriptor(project, file, position.line, position.column),
                    true,
                )
            }
        }
    }

    suspend fun showInFileManager(path: Path) {
        val normalized = withContext(Dispatchers.IO) {
            path.toAbsolutePath().normalize().takeIf(Files::exists)
        }
        withContext(Dispatchers.EDT) {
            if (normalized == null) {
                missingFile(DevWorkspaceBundle.message("task.navigation.missing"))
            } else {
                RevealFileAction.openFile(normalized)
            }
        }
    }

    suspend fun showDiff(path: Path, proposedText: String) {
        val diskText = withContext(Dispatchers.IO) {
            if (Files.exists(path)) Files.readString(path, StandardCharsets.UTF_8) else "version = 1"
        }
        withContext(Dispatchers.EDT) {
            val factory = DiffContentFactory.getInstance()
            DiffManager.getInstance().showDiff(
                project,
                SimpleDiffRequest(
                    DevWorkspaceBundle.message("task.navigation.diff.title"),
                    factory.create(project, diskText),
                    factory.create(project, proposedText),
                    DevWorkspaceBundle.message("task.navigation.diff.current"),
                    DevWorkspaceBundle.message("task.navigation.diff.proposed"),
                ),
            )
        }
    }

    companion object {
        fun editorPosition(line: Int, column: Int): EditorPosition =
            EditorPosition((line - 1).coerceAtLeast(0), (column - 1).coerceAtLeast(0))
    }
}

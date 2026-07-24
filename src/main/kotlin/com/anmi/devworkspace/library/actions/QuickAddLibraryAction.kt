package com.anmi.devworkspace.library.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.ProjectManager
import java.awt.datatransfer.DataFlavor

/**
 * Stable Action System registration for quick add.
 *
 * Task 9 supplies DataContext extraction and persistence; keeping the action
 * ID stable now makes it visible to Find Action and user keymaps.
 */
class QuickAddLibraryAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val editorFile = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty()
        val validFileContext = editorFile?.isDirectory == false ||
            (files.isNotEmpty() && files.none { it.isDirectory })
        val clipboard = CopyPasteManager.getInstance().areDataFlavorsAvailable(DataFlavor.stringFlavor)
        event.presentation.isEnabled = resolveProject(event) != null && (validFileContext || clipboard)
    }

    override fun actionPerformed(event: AnActionEvent) {
        resolveProject(event)?.service<LibraryQuickAddService>()?.open(event.dataContext)
    }

    private fun resolveProject(event: AnActionEvent) =
        event.project ?: ProjectManager.getInstance().openProjects.singleOrNull()
}

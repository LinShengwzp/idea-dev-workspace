package com.anmi.devworkspace.library.actions

import com.anmi.devworkspace.library.ui.LibraryToolWindowFactory
import com.anmi.devworkspace.library.ui.LibraryUiController
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowManager

class OpenLibraryAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ToolWindowManager.getInstance(project)
            .getToolWindow(LibraryToolWindowFactory.ID)
            ?.activate { project.service<LibraryUiController>().focusSearch() }
    }
}

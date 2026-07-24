package com.anmi.devworkspace.library.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service

class AddProjectFilesToLibraryAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty()
        event.presentation.isEnabledAndVisible =
            event.project != null && files.isNotEmpty() && files.none { it.isDirectory }
    }

    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<LibraryQuickAddService>()?.open(event.dataContext)
    }
}

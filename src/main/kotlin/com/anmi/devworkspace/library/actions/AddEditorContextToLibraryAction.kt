package com.anmi.devworkspace.library.actions

import com.anmi.devworkspace.DevWorkspaceBundle
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service

class AddEditorContextToLibraryAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR)
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val valid = event.project != null && editor != null && file?.isDirectory == false
        event.presentation.isEnabledAndVisible = valid
        if (valid) {
            event.presentation.text = DevWorkspaceBundle.message(
                if (editor.selectionModel.hasSelection()) {
                    "library.context.add.selection"
                } else {
                    "library.context.add.current.file"
                },
            )
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<LibraryQuickAddService>()?.open(event.dataContext)
    }
}

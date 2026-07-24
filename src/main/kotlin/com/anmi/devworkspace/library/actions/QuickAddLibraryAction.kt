package com.anmi.devworkspace.library.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread

/**
 * Stable Action System registration for quick add.
 *
 * Task 9 supplies DataContext extraction and persistence; keeping the action
 * ID stable now makes it visible to Find Action and user keymaps.
 */
class QuickAddLibraryAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = false
    }

    override fun actionPerformed(event: AnActionEvent) = Unit
}

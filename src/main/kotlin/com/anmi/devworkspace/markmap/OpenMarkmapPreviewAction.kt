package com.anmi.devworkspace.markmap

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.wm.ToolWindowManager

class OpenMarkmapPreviewAction(
    private val matcher: MarkmapSuffixMatcher = MarkmapSuffixMatcher(),
) : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val fileName = event.getData(CommonDataKeys.VIRTUAL_FILE)?.name
        val available = fileName != null && matcher.matches(fileName)
        event.presentation.isEnabledAndVisible = available
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val document = event.getData(CommonDataKeys.EDITOR)?.document
            ?: event.getData(CommonDataKeys.VIRTUAL_FILE)?.let {
                FileDocumentManager.getInstance().getDocument(it)
            }
        if (document == null) {
            LOG.warn("No document is available for MarkMap preview")
            return
        }

        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(MarkmapToolWindowFactory.ID)
        if (toolWindow == null) {
            LOG.warn("MarkMap Tool Window is unavailable")
            return
        }

        toolWindow.activate {
            if (!project.isDisposed) {
                project.service<MarkmapPreviewService>().preview(document)
            }
        }
    }

    companion object {
        const val ACTION_ID: String = "com.anmi.devworkspace.markmap.openPreview"
        private val LOG = Logger.getInstance(OpenMarkmapPreviewAction::class.java)
    }
}

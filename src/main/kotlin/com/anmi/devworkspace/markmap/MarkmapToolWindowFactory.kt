package com.anmi.devworkspace.markmap

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class MarkmapToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<MarkmapPreviewService>()
        val panel = MarkmapPreviewPanel(service)
        val content = ContentFactory.getInstance().createContent(panel, null, false)

        // Content owns the JCEF panel so closing the project/content always releases Chromium.
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        service.setPanel(panel)
    }

    companion object {
        const val ID: String = "Markmap"
    }
}

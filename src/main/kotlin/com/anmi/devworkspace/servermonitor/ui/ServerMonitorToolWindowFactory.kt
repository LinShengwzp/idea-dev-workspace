package com.anmi.devworkspace.servermonitor.ui

import com.anmi.devworkspace.settings.DevWorkspaceSettingsState
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ServerMonitorToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (!DevWorkspaceSettingsState.getInstance().state.serverMonitorEnabled) {
            toolWindow.hide()
            return
        }
        val panel = ServerMonitorPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean {
        return DevWorkspaceSettingsState.getInstance().state.serverMonitorEnabled
    }

    companion object {
        const val ID: String = "Server Monitor"
    }
}

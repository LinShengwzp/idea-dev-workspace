package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.settings.DevWorkspaceSettingsState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class LibraryToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (!DevWorkspaceSettingsState.getInstance().state.libraryEnabled) {
            toolWindow.hide()
            return
        }
        val panel = LibraryPanel(project)
        project.service<LibraryUiController>().attach(panel)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean {
        return DevWorkspaceSettingsState.getInstance().state.libraryEnabled
    }

    companion object {
        const val ID: String = "Dev Library"
    }
}

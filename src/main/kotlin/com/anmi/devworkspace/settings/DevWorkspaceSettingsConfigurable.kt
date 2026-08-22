package com.anmi.devworkspace.settings

import com.anmi.devworkspace.DevWorkspaceBundle.message
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Stable parent node for Dev Workspace settings pages.
 */
class DevWorkspaceSettingsConfigurable : Configurable {
    private val settings = DevWorkspaceSettingsState.getInstance()
    private var tasksCheckBox: JBCheckBox? = null
    private var libraryCheckBox: JBCheckBox? = null
    private var markmapCheckBox: JBCheckBox? = null
    private var serverMonitorCheckBox: JBCheckBox? = null

    override fun getDisplayName(): String = message("settings.devworkspace.name")

    override fun createComponent(): JComponent {
        tasksCheckBox = JBCheckBox(message("settings.devworkspace.module.tasks"))
        libraryCheckBox = JBCheckBox(message("settings.devworkspace.module.library"))
        markmapCheckBox = JBCheckBox(message("settings.devworkspace.module.markmap"))
        serverMonitorCheckBox = JBCheckBox(message("settings.devworkspace.module.serverMonitor"))

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(JBLabel(message("settings.devworkspace.description")), BorderLayout.NORTH)
            add(
                FormBuilder.createFormBuilder()
                    .addLabeledComponent(message("settings.devworkspace.modules.label"), tasksCheckBox!!)
                    .addComponent(libraryCheckBox!!)
                    .addComponent(markmapCheckBox!!)
                    .addComponent(serverMonitorCheckBox!!)
                    .addComponentFillVertically(JPanel(), 0)
                    .panel,
                BorderLayout.CENTER,
            )
        }
    }

    override fun isModified(): Boolean {
        return tasksCheckBox?.isSelected != settings.state.tasksEnabled
            || libraryCheckBox?.isSelected != settings.state.libraryEnabled
            || markmapCheckBox?.isSelected != settings.state.markmapEnabled
            || serverMonitorCheckBox?.isSelected != settings.state.serverMonitorEnabled
    }

    override fun apply() {
        val newTasksEnabled = tasksCheckBox?.isSelected ?: true
        val newLibraryEnabled = libraryCheckBox?.isSelected ?: true
        val newMarkmapEnabled = markmapCheckBox?.isSelected ?: true
        val newServerMonitorEnabled = serverMonitorCheckBox?.isSelected ?: true

        val tasksChanged = newTasksEnabled != settings.state.tasksEnabled
        val libraryChanged = newLibraryEnabled != settings.state.libraryEnabled
        val markmapChanged = newMarkmapEnabled != settings.state.markmapEnabled
        val serverMonitorChanged = newServerMonitorEnabled != settings.state.serverMonitorEnabled

        settings.state.tasksEnabled = newTasksEnabled
        settings.state.libraryEnabled = newLibraryEnabled
        settings.state.markmapEnabled = newMarkmapEnabled
        settings.state.serverMonitorEnabled = newServerMonitorEnabled

        if (tasksChanged || libraryChanged || markmapChanged || serverMonitorChanged) {
            updateToolWindowsInAllProjects(
                tasksEnabled = newTasksEnabled,
                libraryEnabled = newLibraryEnabled,
                markmapEnabled = newMarkmapEnabled,
                serverMonitorEnabled = newServerMonitorEnabled,
            )
        }
    }

    override fun reset() {
        tasksCheckBox?.isSelected = settings.state.tasksEnabled
        libraryCheckBox?.isSelected = settings.state.libraryEnabled
        markmapCheckBox?.isSelected = settings.state.markmapEnabled
        serverMonitorCheckBox?.isSelected = settings.state.serverMonitorEnabled
    }

    private fun updateToolWindowsInAllProjects(
        tasksEnabled: Boolean,
        libraryEnabled: Boolean,
        markmapEnabled: Boolean,
        serverMonitorEnabled: Boolean,
    ) {
        val projects = ProjectManager.getInstance().openProjects
        for (project in projects) {
            if (project.isDisposed) continue
            val manager = ToolWindowManager.getInstance(project)
            updateToolWindow(manager, "Dev Tasks", tasksEnabled)
            updateToolWindow(manager, "Dev Library", libraryEnabled)
            updateToolWindow(manager, "Markmap", markmapEnabled)
            updateToolWindow(manager, "Server Monitor", serverMonitorEnabled)
        }
    }

    private fun updateToolWindow(manager: ToolWindowManager, id: String, enabled: Boolean) {
        val toolWindow = manager.getToolWindow(id)
        if (toolWindow != null) {
            if (enabled) {
                toolWindow.show(null)
            } else {
                toolWindow.hide(null)
            }
        }
    }

    companion object {
        const val ID: String = "com.anmi.devworkspace.settings"
    }
}

package com.anmi.devworkspace.settings

import com.anmi.devworkspace.DevWorkspaceBundle.message
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Stable parent node for Dev Workspace settings pages.
 */
class DevWorkspaceSettingsConfigurable : Configurable {
    override fun getDisplayName(): String = message("settings.devworkspace.name")

    override fun createComponent(): JComponent =
        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(JBLabel(message("settings.devworkspace.description")), BorderLayout.NORTH)
        }

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    companion object {
        const val ID: String = "com.anmi.devworkspace.settings"
    }
}

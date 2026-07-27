package com.anmi.devworkspace.markmap.settings

import com.anmi.devworkspace.DevWorkspaceBundle.message
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class MarkmapConfigurable(
    settings: MarkmapSettingsState = MarkmapSettingsState.getInstance(),
) : SearchableConfigurable {
    private val model = MarkmapSettingsEditorModel(settings)
    private var suffixesField: JBTextArea? = null

    override fun getId(): String = ID

    override fun getDisplayName(): String = message("settings.markmap.name")

    override fun createComponent(): JComponent {
        val field = JBTextArea(model.text, 8, 40).apply {
            lineWrap = false
        }
        suffixesField = field

        val restoreDefaults = JButton(message("settings.markmap.restore.defaults")).apply {
            addActionListener {
                this@MarkmapConfigurable.model.restoreDefaults()
                field.text = this@MarkmapConfigurable.model.text
            }
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(
                FormBuilder.createFormBuilder()
                    .addLabeledComponent(
                        message("settings.markmap.suffixes.label"),
                        JBScrollPane(field),
                    )
                    .addComponent(JBLabel(message("settings.markmap.suffixes.description")))
                    .addComponent(restoreDefaults)
                    .addComponentFillVertically(JPanel(), 0)
                    .panel,
                BorderLayout.CENTER,
            )
        }
    }

    override fun getPreferredFocusedComponent(): JComponent? = suffixesField

    override fun isModified(): Boolean {
        readEditor()
        return model.isModified()
    }

    override fun apply() {
        readEditor()
        model.apply()
        suffixesField?.text = model.text
    }

    override fun reset() {
        model.reset()
        suffixesField?.text = model.text
    }

    override fun disposeUIResources() {
        suffixesField = null
    }

    private fun readEditor() {
        suffixesField?.let { model.text = it.text }
    }

    companion object {
        const val ID: String = "com.anmi.devworkspace.settings.markmap"
    }
}

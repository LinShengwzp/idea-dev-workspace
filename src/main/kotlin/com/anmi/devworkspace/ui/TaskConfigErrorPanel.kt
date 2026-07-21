package com.anmi.devworkspace.ui

import com.anmi.devworkspace.config.TaskConfigError
import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout

class TaskConfigErrorPanel(
    private val openLocation: (TaskConfigError) -> Unit,
    reload: () -> Unit,
) : JBPanel<TaskConfigErrorPanel>(BorderLayout()) {
    private val details = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
    }
    private var error: TaskConfigError? = null
    private val openButton = ActionLink("Open Error Location").also { button ->
        button.addActionListener { error?.let(openLocation) }
    }

    init {
        val actions = JBPanel<JBPanel<*>>().apply {
            add(openButton)
            add(ActionLink("Reload").also { button -> button.addActionListener { reload() } })
        }
        add(
            FormBuilder.createFormBuilder()
                .addLabeledComponent(JBLabel(AllIcons.General.Error), details, true)
                .addComponent(actions)
                .panel,
            BorderLayout.CENTER,
        )
        isVisible = false
    }

    fun showMessages(messages: TaskBannerMessages) {
        error = messages.configError
        openButton.isEnabled = error != null
        isVisible = error != null || messages.operationalMessage != null
        details.text = listOfNotNull(
            error?.let { "${it.sourceFile} — line ${it.line}, column ${it.column}: ${it.message}" },
            messages.operationalMessage,
        ).joinToString("\n")
        revalidate()
        repaint()
    }
}

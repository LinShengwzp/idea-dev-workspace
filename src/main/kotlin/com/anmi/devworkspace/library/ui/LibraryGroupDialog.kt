package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.library.domain.LibraryScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComboBox
import javax.swing.JComponent

class LibraryGroupDialog(
    project: Project,
    initial: LibraryGroupEditorState = LibraryGroupEditorState(
        name = "",
        description = "",
        order = 0,
        scope = LibraryScope.PROJECT_PRIVATE,
    ),
) : DialogWrapper(project) {
    private val model = LibraryGroupEditorModel()
    private val name = JBTextField(initial.name)
    private val description = JBTextField(initial.description)
    private val order = JBTextField(initial.order.toString())
    private val scope = JComboBox(LibraryScope.entries.toTypedArray()).apply {
        selectedItem = initial.scope
        renderer = LibraryLocalizedEnumRenderer()
    }
    var result: LibraryGroupEditorState? = null
        private set

    init {
        title = message("library.group.editor.title")
        init()
        initValidation()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(message("library.group.name"), name)
            .addLabeledComponent(message("library.group.description"), description)
            .addLabeledComponent(message("library.group.order"), order)
            .addLabeledComponent(message("library.editor.scope"), scope)
            .panel

    override fun doValidate(): ValidationInfo? {
        val state = read() ?: return ValidationInfo(message("library.group.validation.order"), order)
        return if (model.isValid(state)) null else ValidationInfo(message("library.group.validation.name"), name)
    }

    override fun doOKAction() {
        val state = read() ?: return
        if (!model.isValid(state)) return
        result = state
        super.doOKAction()
    }

    private fun read(): LibraryGroupEditorState? =
        order.text.toIntOrNull()?.let {
            LibraryGroupEditorState(name.text, description.text, it, scope.selectedItem as LibraryScope)
        }

    private companion object {
        fun message(key: String): String = DevWorkspaceBundle.message(key)
    }
}

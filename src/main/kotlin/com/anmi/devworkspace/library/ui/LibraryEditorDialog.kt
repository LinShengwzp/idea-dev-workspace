package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.library.domain.LibraryGroup
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Component
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList

class LibraryEditorDialog(
    project: Project,
    initial: LibraryEditorState,
    groups: List<LibraryGroup>,
) : DialogWrapper(project) {
    private val model = LibraryEditorModel()
    private val itemId = initial.id
    private val favorite = initial.favorite
    private val source = initial.source
    private val availableGroups = groups
    private val titleField = JBTextField(initial.title)
    private val typeField = JComboBox(LibraryItemType.entries.toTypedArray()).apply {
        selectedItem = initial.type
        renderer = LibraryLocalizedEnumRenderer()
    }
    private val scopeField = JComboBox(LibraryScope.entries.toTypedArray()).apply {
        selectedItem = initial.scope
        renderer = LibraryLocalizedEnumRenderer()
    }
    private val groupField = JComboBox<String>().apply { renderer = GroupRenderer(groups) }
    private val tagsField = JBTextField(initial.tags.joinToString(", "))
    private val noteField = JBTextArea(initial.note.orEmpty(), 4, 42)
    private val targetField = JBTextField(initial.target.orEmpty())
    private val markdownField = JBTextArea(initial.markdown.orEmpty(), 12, 42)
    var result: LibraryEditorState? = null
        private set

    init {
        title = message(if (initial.id == null) "library.editor.title.new" else "library.editor.title.edit")
        setOKButtonText(message("library.editor.save"))
        updateGroups(initial.scope, initial.groupId)
        scopeField.addActionListener { updateGroups(scopeField.selectedItem as LibraryScope, null) }
        init()
        initValidation()
    }

    override fun createCenterPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(message("library.editor.title"), titleField)
            .addLabeledComponent(message("library.editor.type"), typeField)
            .addLabeledComponent(message("library.editor.scope"), scopeField)
            .addLabeledComponent(message("library.editor.group"), groupField)
            .addLabeledComponent(message("library.editor.tags"), tagsField)
            .addLabeledComponent(message("library.editor.target"), targetField)
            .addLabeledComponent(message("library.editor.note"), JBScrollPane(noteField))
            .addLabeledComponent(message("library.editor.content"), JBScrollPane(markdownField))
            .panel.apply { border = JBUI.Borders.empty(8) }
        return JBScrollPane(form).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(JBUI.scale(620), JBUI.scale(540))
        }
    }

    override fun doValidate(): ValidationInfo? {
        val error = model.validate(readState()).firstOrNull() ?: return null
        val component = when (error.field) {
            LibraryEditorField.TITLE -> titleField
            LibraryEditorField.TARGET -> targetField
        }
        return ValidationInfo(message(error.messageKey), component)
    }

    override fun doOKAction() {
        val draft = model.toDraft(readState())
        if (model.validate(draft).isNotEmpty()) return
        result = draft
        super.doOKAction()
    }

    private fun readState(): LibraryEditorState =
        LibraryEditorState(
            id = itemId,
            title = titleField.text,
            type = typeField.selectedItem as LibraryItemType,
            scope = scopeField.selectedItem as LibraryScope,
            groupId = (groupField.selectedItem as? String)?.takeIf(String::isNotBlank),
            tags = tagsField.text.split(',').mapTo(linkedSetOf()) { it.trim() }.filterTo(linkedSetOf()) {
                it.isNotEmpty()
            },
            note = noteField.text,
            favorite = favorite,
            target = targetField.text,
            markdown = markdownField.text,
            source = source,
        )

    private fun updateGroups(scope: LibraryScope, selected: String?) {
        val ids = availableGroups.filter { it.scope == scope }.map(LibraryGroup::id)
        groupField.model = DefaultComboBoxModel((listOf("") + ids).toTypedArray())
        groupField.selectedItem = selected?.takeIf(ids::contains).orEmpty()
    }

    private class GroupRenderer(groups: List<LibraryGroup>) : DefaultListCellRenderer() {
        private val names = groups.associate { it.id to it.name }

        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val id = value as? String
            text = if (id.isNullOrBlank()) message("library.group.none") else names[id] ?: id
            return component
        }
    }

    private companion object {
        fun message(key: String): String = DevWorkspaceBundle.message(key)
    }
}

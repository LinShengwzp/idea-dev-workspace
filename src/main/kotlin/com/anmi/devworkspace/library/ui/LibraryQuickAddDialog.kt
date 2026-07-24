package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.library.actions.LibraryQuickAddDraft
import com.anmi.devworkspace.library.domain.LibraryGroup
import com.anmi.devworkspace.library.domain.LibraryScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Component
import javax.swing.Action
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JTabbedPane

class LibraryQuickAddDialog(
    project: Project,
    drafts: List<LibraryQuickAddDraft>,
    groups: List<LibraryGroup>,
    rememberedGroup: String?,
    rememberedTags: Set<String>,
) : DialogWrapper(project) {
    private val editors = drafts.map { draft ->
        DraftEditor(draft, groups, rememberedGroup, rememberedTags)
    }
    private val tabs = JTabbedPane()
    var results: List<LibraryEditorState>? = null
        private set
    var fullEdit: LibraryEditorState? = null
        private set

    init {
        title = message("library.quick.add.title")
        setOKButtonText(message("library.quick.add.save"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        editors.forEachIndexed { index, editor ->
            tabs.addTab(
                if (editors.size == 1) message("library.quick.add.item") else "${index + 1}. ${editor.draft.title}",
                editor.component,
            )
        }
        return tabs.apply { preferredSize = Dimension(JBUI.scale(600), JBUI.scale(430)) }
    }

    override fun createLeftSideActions(): Array<Action> =
        arrayOf(object : DialogWrapperAction(message("library.quick.add.full.edit")) {
            override fun doAction(event: java.awt.event.ActionEvent?) {
                fullEdit = editors[tabs.selectedIndex.coerceAtLeast(0)].state()
                close(CANCEL_EXIT_CODE)
            }
        })

    override fun doOKAction() {
        val states = editors.map(DraftEditor::state)
        if (states.any { LibraryEditorModel().validate(it).isNotEmpty() }) return
        results = states
        super.doOKAction()
    }

    private class DraftEditor(
        val draft: LibraryQuickAddDraft,
        groups: List<LibraryGroup>,
        rememberedGroup: String?,
        rememberedTags: Set<String>,
    ) {
        private val availableGroups = groups
        private val title = JBTextField(draft.title)
        private val scope = JComboBox(LibraryScope.entries.toTypedArray()).apply {
            selectedItem = draft.scope
            renderer = LibraryLocalizedEnumRenderer()
        }
        private val group = JComboBox<String>().apply { renderer = GroupRenderer(groups) }
        private val tags = JBTextField((draft.tags.ifEmpty { rememberedTags }).joinToString(", "))
        private val preview = JBTextArea(draft.markdown ?: draft.target.orEmpty(), 9, 42).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        init {
            updateGroups(draft.scope, draft.groupId ?: rememberedGroup)
            scope.addActionListener { updateGroups(scope.selectedItem as LibraryScope, null) }
        }
        val component: JComponent = JBScrollPane(
            FormBuilder.createFormBuilder()
                .addLabeledComponent(message("library.editor.title"), title)
                .addLabeledComponent(message("library.editor.scope"), scope)
                .addLabeledComponent(message("library.editor.group"), group)
                .addLabeledComponent(message("library.editor.tags"), tags)
                .addLabeledComponent(
                    message("library.quick.add.source"),
                    JBLabel(draft.sourceDescription),
                )
                .addLabeledComponent(message("library.quick.add.preview"), JBScrollPane(preview))
                .panel.apply { border = JBUI.Borders.empty(8) },
        ).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        fun state(): LibraryEditorState =
            LibraryEditorState(
                title = title.text,
                type = draft.type,
                scope = scope.selectedItem as LibraryScope,
                groupId = (group.selectedItem as? String)?.takeIf(String::isNotBlank),
                tags = tags.text.split(',').mapTo(linkedSetOf()) { it.trim() }
                    .filterTo(linkedSetOf()) { it.isNotEmpty() },
                note = draft.sourceDescription,
                target = draft.target,
                markdown = draft.markdown,
            )

        private fun updateGroups(selectedScope: LibraryScope, selected: String?) {
            val ids = availableGroups.filter { it.scope == selectedScope }.map(LibraryGroup::id)
            group.model = DefaultComboBoxModel((listOf("") + ids).toTypedArray())
            group.selectedItem = selected?.takeIf(ids::contains).orEmpty()
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
    }

    private companion object {
        fun message(key: String): String = DevWorkspaceBundle.message(key)
    }
}

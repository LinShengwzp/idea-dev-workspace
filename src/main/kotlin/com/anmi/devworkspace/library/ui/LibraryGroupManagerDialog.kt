package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.library.domain.LibraryGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import java.awt.Dimension
import java.util.UUID
import javax.swing.DefaultListModel
import javax.swing.JComponent

class LibraryGroupManagerDialog(
    private val project: Project,
    groups: List<LibraryGroup>,
) : DialogWrapper(project) {
    private val model = DefaultListModel<LibraryGroup>().apply { groups.forEach(::addElement) }
    private val list = JBList(model).apply {
        cellRenderer = object : ColoredListCellRenderer<LibraryGroup>() {
            override fun customizeCellRenderer(
                list: javax.swing.JList<out LibraryGroup>,
                value: LibraryGroup?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                if (value == null) return
                append(value.name)
                append(
                    "  " + DevWorkspaceBundle.message(
                        "library.scope.${value.scope.name.lowercase().replace('_', '.')}",
                    ),
                    SimpleTextAttributes.GRAYED_ATTRIBUTES,
                )
            }
        }
    }
    var result: List<LibraryGroup>? = null
        private set

    init {
        title = DevWorkspaceBundle.message("library.group.manager.title")
        init()
    }

    override fun createCenterPanel(): JComponent =
        ToolbarDecorator.createDecorator(list)
            .setAddAction {
                edit(null)?.let(model::addElement)
            }
            .setEditAction {
                val index = list.selectedIndex
                if (index >= 0) edit(model[index])?.let { model[index] = it }
            }
            .setRemoveAction {
                val index = list.selectedIndex
                if (index >= 0) model.remove(index)
            }
            .createPanel()
            .apply { preferredSize = Dimension(520, 320) }

    override fun doOKAction() {
        result = (0 until model.size()).map(model::get)
        super.doOKAction()
    }

    private fun edit(group: LibraryGroup?): LibraryGroup? {
        val dialog = LibraryGroupDialog(
            project,
            group?.let {
                LibraryGroupEditorState(it.name, it.description.orEmpty(), it.order, it.scope)
            } ?: LibraryGroupEditorState("", "", 0, com.anmi.devworkspace.library.domain.LibraryScope.PROJECT_PRIVATE),
        )
        if (!dialog.showAndGet()) return null
        val state = dialog.result ?: return null
        return LibraryGroup(
            id = group?.id ?: UUID.randomUUID().toString(),
            name = state.name.trim(),
            description = state.description.trim().takeIf(String::isNotEmpty),
            order = state.order,
            scope = state.scope,
        )
    }
}

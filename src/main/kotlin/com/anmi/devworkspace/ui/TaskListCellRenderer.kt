package com.anmi.devworkspace.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.domain.TaskStatus
import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList

class TaskListCellRenderer : ColoredListCellRenderer<TaskListItem>() {
    override fun customizeCellRenderer(
        list: JList<out TaskListItem>,
        value: TaskListItem,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        icon = value.status.icon()
        append(value.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append("  ${value.autoText}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append("  ${value.scopeText}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        value.favoriteText?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        if (value.hasOverrides) {
            append(
                "  ${DevWorkspaceBundle.message("task.list.override")}",
                SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES,
            )
        }
        append("  ${value.statusText}", statusAttributes(value.status))
        value.failureText?.let { append("  $it", SimpleTextAttributes.ERROR_ATTRIBUTES) }
    }

    private fun statusAttributes(status: TaskStatus): SimpleTextAttributes = when (status) {
        TaskStatus.FAILED, TaskStatus.UNKNOWN, TaskStatus.INTERRUPTED -> SimpleTextAttributes.ERROR_ATTRIBUTES
        TaskStatus.SUCCEEDED -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.namedColor("StatusBar.Green", JBColor.foreground()))
        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
    }

    private fun TaskStatus.icon() = when (this) {
        TaskStatus.RUNNING, TaskStatus.PREPARING, TaskStatus.WAITING_FOR_TERMINAL -> AllIcons.Actions.Execute
        TaskStatus.STOPPING, TaskStatus.STOPPED, TaskStatus.INTERRUPTED -> AllIcons.Actions.Suspend
        TaskStatus.SUCCEEDED -> AllIcons.General.InspectionsOK
        TaskStatus.FAILED -> AllIcons.General.Error
        TaskStatus.UNKNOWN -> AllIcons.General.Warning
        TaskStatus.IDLE -> AllIcons.General.Information
    }
}

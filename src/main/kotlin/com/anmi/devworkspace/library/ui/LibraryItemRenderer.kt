package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList

class LibraryItemRenderer : ColoredListCellRenderer<LibraryListItem>() {
    override fun customizeCellRenderer(
        list: JList<out LibraryListItem>,
        value: LibraryListItem?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value == null) return
        if (value.item.favorite) append("★ ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append(value.item.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append(
            "  ${message("library.type.${value.item.type.name.lowercase()}")}",
            SimpleTextAttributes.GRAYED_ATTRIBUTES,
        )
        value.groupName?.let { group ->
            append("  $group", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        append(
            "  ${message("library.scope.${value.item.scope.name.lowercase().replace('_', '.')}")}",
            SimpleTextAttributes.GRAYED_ATTRIBUTES,
        )
    }

    private fun message(key: String): String = DevWorkspaceBundle.message(key)
}

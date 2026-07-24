package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

class LibraryLocalizedEnumRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        text = when (value) {
            is LibraryScope -> message("library.scope.${value.name.lowercase().replace('_', '.')}")
            is LibraryItemType -> message("library.type.${value.name.lowercase()}")
            else -> value?.toString().orEmpty()
        }
        return component
    }

    private fun message(key: String): String = DevWorkspaceBundle.message(key)
}

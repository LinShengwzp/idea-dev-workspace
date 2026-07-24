package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.search.LibraryQuery
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.ListSelectionModel

class LibraryFilterPanel(
    private val changed: (LibraryQuery) -> Unit,
) : JBPanel<LibraryFilterPanel>(BorderLayout()) {
    private val scopes = enumList(LibraryScope.entries)
    private val types = enumList(LibraryItemType.entries)
    private val favorite = JBCheckBox(message("library.filter.favorite"))
    private var text: String = ""

    init {
        border = JBUI.Borders.empty(6)
        add(JBLabel(message("library.filter.title")), BorderLayout.NORTH)
        add(
            JBPanel<JBPanel<*>>(GridLayout(2, 1, 0, 6)).apply {
                add(section(message("library.filter.scope"), scopes))
                add(section(message("library.filter.type"), types))
            },
            BorderLayout.CENTER,
        )
        add(favorite, BorderLayout.SOUTH)
        scopes.addListSelectionListener { if (!it.valueIsAdjusting) publish() }
        types.addListSelectionListener { if (!it.valueIsAdjusting) publish() }
        favorite.addActionListener { publish() }
    }

    fun updateText(value: String) {
        text = value
        publish()
    }

    fun clearFilters() {
        scopes.clearSelection()
        types.clearSelection()
        favorite.isSelected = false
        publish()
    }

    private fun publish() {
        changed(
            LibraryQuery(
                text = text,
                scopes = scopes.selectedValuesList.toSet(),
                types = types.selectedValuesList.toSet(),
                favoriteOnly = favorite.isSelected,
            ),
        )
    }

    private fun <T : Enum<T>> enumList(values: List<T>): JBList<T> =
        JBList(DefaultListModel<T>().apply { values.forEach(::addElement) }).apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            cellRenderer = EnumFilterRenderer()
            visibleRowCount = values.size
        }

    private fun section(title: String, list: JBList<*>): JBPanel<*> =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBLabel(title), BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }

    private class EnumFilterRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            selected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, selected, cellHasFocus)
            if (value !is Enum<*>) return component
            val prefix = when (value) {
                is LibraryScope -> "library.scope."
                is LibraryItemType -> "library.type."
                else -> return component
            }
            text = message(prefix + value.name.lowercase().replace('_', '.'))
            return component
        }
    }

    private companion object {
        fun message(key: String): String = DevWorkspaceBundle.message(key)
    }
}

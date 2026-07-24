package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.search.LibraryPathState
import com.anmi.devworkspace.library.search.LibraryQuery
import com.anmi.devworkspace.library.search.LibrarySearchRecord
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel

class LibraryFilterPanel(
    private val changed: (LibraryQuery) -> Unit,
    private val clearSearch: () -> Unit = {},
) : JBPanel<LibraryFilterPanel>(BorderLayout()) {
    private val allResources = JToggleButton(message("library.filter.all.resources")).apply {
        horizontalAlignment = JToggleButton.LEFT
        border = JBUI.Borders.empty(6, 8)
    }
    private val groups = choiceList<GroupChoice>()
    private val scopes = choiceList<LibraryScope>()
    private val types = choiceList<LibraryItemType>()
    private val tags = choiceList<String>()
    private val statuses = choiceList<LibraryPathState>()
    private val favorite = JBCheckBox(message("library.filter.favorite"))
    private var state = LibraryFilterState()
    private var syncing = false
    private val previousSelections = mutableMapOf<JBList<*>, Set<Int>>()

    init {
        border = JBUI.Borders.empty(6)
        add(
            JBScrollPane(
                JBPanel<JBPanel<*>>().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(allResources)
                    add(Box.createVerticalStrut(JBUI.scale(4)))
                    add(section("library.filter.group", groups))
                    add(section("library.filter.scope", scopes))
                    add(section("library.filter.type", types))
                    add(section("library.filter.tags", tags))
                    add(section("library.filter.status", statuses))
                    add(favorite)
                },
            ).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            },
            BorderLayout.CENTER,
        )
        rebuildStaticOptions()
        configure(groups)
        configure(scopes)
        configure(types)
        configure(tags)
        configure(statuses)
        allResources.addActionListener {
            clearSearch()
            applyState(state.clearAll())
        }
        favorite.addActionListener {
            if (!syncing) {
                state = state.copy(favoriteOnly = favorite.isSelected)
                publish()
            }
        }
        syncSelections()
    }

    fun updateText(value: String) {
        if (state.text == value) return
        state = state.copy(text = value)
        updateAllResourcesSelection()
        publish()
    }

    fun updateOptions(records: Collection<LibrarySearchRecord>) {
        val previousState = state
        val selectedGroups = state.groupNames
        val includeUngrouped = state.includeUngrouped
        val selectedTags = state.tags
        val options = LibraryFilterOptions.from(records)
        syncing = true
        try {
            replaceChoices(
                groups,
                buildList {
                    add(Choice(null, message("library.filter.all.groups")))
                    options.groupNames.forEach { (normalized, label) ->
                        add(Choice(GroupChoice.Named(normalized), label))
                    }
                    if (options.hasUngrouped) {
                        add(Choice(GroupChoice.Ungrouped, message("library.filter.ungrouped")))
                    }
                },
            )
            replaceChoices(
                tags,
                buildList {
                    add(Choice(null, message("library.filter.all.tags")))
                    options.tags.forEach { (normalized, label) ->
                        add(Choice(normalized, label))
                    }
                },
            )
        } finally {
            syncing = false
        }
        state = state.copy(
            groupNames = selectedGroups.intersect(options.groupNames.keys),
            includeUngrouped = includeUngrouped && options.hasUngrouped,
            tags = selectedTags.intersect(options.tags.keys),
        )
        syncSelections()
        if (state != previousState) publish()
    }

    fun clearFilters() {
        applyState(state.clearAll())
    }

    private fun rebuildStaticOptions() {
        replaceChoices(
            scopes,
            listOf(Choice<LibraryScope>(null, message("library.filter.all.scopes"))) +
                LibraryScope.entries.map { value ->
                    Choice<LibraryScope>(
                        value,
                        message("library.scope.${value.name.lowercase().replace('_', '.')}"),
                    )
                },
        )
        replaceChoices(
            types,
            listOf(Choice<LibraryItemType>(null, message("library.filter.all.types"))) +
                LibraryItemType.entries.map { value ->
                    Choice<LibraryItemType>(value, message("library.type.${value.name.lowercase()}"))
                },
        )
        replaceChoices(
            statuses,
            listOf(
                Choice(null, message("library.filter.all.statuses")),
                Choice(LibraryPathState.AVAILABLE, message("library.filter.status.available")),
                Choice(LibraryPathState.MISSING, message("library.filter.status.missing")),
            ),
        )
        replaceChoices(groups, listOf(Choice(null, message("library.filter.all.groups"))))
        replaceChoices(tags, listOf(Choice(null, message("library.filter.all.tags"))))
    }

    private fun <T> configure(list: JBList<Choice<T>>) {
        list.addListSelectionListener { event ->
            if (syncing || event.valueIsAdjusting) return@addListSelectionListener
            normalizeAllSelection(list)
            readStateFromLists()
            publish()
        }
    }

    private fun <T> normalizeAllSelection(list: JBList<Choice<T>>) {
        val selected = list.selectedIndices.toSet()
        val previous = previousSelections[list].orEmpty()
        val normalized = when {
            selected.isEmpty() -> setOf(0)
            0 !in selected -> selected
            selected.size == 1 -> selected
            previous == setOf(0) -> selected - 0
            else -> setOf(0)
        }
        setSelectedIndices(list, normalized)
        previousSelections[list] = normalized
    }

    private fun readStateFromLists() {
        state = state.copy(
            scopes = selectedValues(scopes).toSet(),
            types = selectedValues(types).toSet(),
            groupNames = selectedValues(groups)
                .filterIsInstance<GroupChoice.Named>()
                .mapTo(linkedSetOf(), GroupChoice.Named::normalizedName),
            includeUngrouped = GroupChoice.Ungrouped in selectedValues(groups),
            tags = selectedValues(tags).toSet(),
            pathStates = selectedValues(statuses).toSet(),
        )
        updateAllResourcesSelection()
    }

    private fun syncSelections() {
        syncing = true
        try {
            selectValues(scopes, state.scopes)
            selectValues(types, state.types)
            selectValues(
                groups,
                state.groupNames.mapTo(linkedSetOf<GroupChoice>()) { GroupChoice.Named(it) }.apply {
                    if (state.includeUngrouped) add(GroupChoice.Ungrouped)
                },
            )
            selectValues(tags, state.tags)
            selectValues(statuses, state.pathStates)
            favorite.isSelected = state.favoriteOnly
            updateAllResourcesSelection()
        } finally {
            syncing = false
        }
    }

    private fun applyState(updated: LibraryFilterState) {
        state = updated
        syncSelections()
        publish()
    }

    private fun publish() {
        changed(state.toQuery())
    }

    private fun updateAllResourcesSelection() {
        allResources.isSelected = state == LibraryFilterState()
    }

    private fun <T> selectedValues(list: JBList<Choice<T>>): List<T> =
        list.selectedValuesList.mapNotNull(Choice<T>::value)

    private fun <T> selectValues(list: JBList<Choice<T>>, values: Set<T>) {
        val indices = if (values.isEmpty()) {
            setOf(0)
        } else {
            (0 until list.model.size)
                .filterTo(linkedSetOf()) { index -> list.model.getElementAt(index).value in values }
                .ifEmpty { setOf(0) }
        }
        setSelectedIndices(list, indices)
        previousSelections[list] = indices
    }

    private fun <T> setSelectedIndices(list: JBList<Choice<T>>, indices: Set<Int>) {
        syncing = true
        try {
            list.selectedIndices = indices.sorted().toIntArray()
        } finally {
            syncing = false
        }
    }

    private fun <T> replaceChoices(list: JBList<Choice<T>>, choices: List<Choice<T>>) {
        val model = list.model as DefaultListModel<Choice<T>>
        model.clear()
        choices.forEach(model::addElement)
        list.visibleRowCount = choices.size.coerceAtMost(6)
    }

    private fun section(key: String, list: JBList<*>): Component =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(6)
            add(TitledSeparator(message(key)), BorderLayout.NORTH)
            add(
                JBScrollPane(list).apply {
                    border = JBUI.Borders.emptyLeft(8)
                    horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                },
                BorderLayout.CENTER,
            )
        }

    private fun <T> choiceList(): JBList<Choice<T>> =
        JBList(DefaultListModel<Choice<T>>()).apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            cellRenderer = ChoiceRenderer()
            fixedCellHeight = JBUI.scale(24)
        }

    private data class Choice<T>(val value: T?, val label: String)

    private sealed interface GroupChoice {
        data class Named(val normalizedName: String) : GroupChoice
        data object Ungrouped : GroupChoice
    }

    private class ChoiceRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            selected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, selected, cellHasFocus)
            if (value is Choice<*>) text = value.label
            border = JBUI.Borders.emptyLeft(6)
            return component
        }
    }

    private companion object {
        fun message(key: String): String = DevWorkspaceBundle.message(key)
    }
}

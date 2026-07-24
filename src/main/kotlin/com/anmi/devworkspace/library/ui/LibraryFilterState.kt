package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.search.LibraryPathState
import com.anmi.devworkspace.library.search.LibraryQuery
import com.anmi.devworkspace.library.search.LibrarySearchRecord
import java.util.Locale

/**
 * UI-independent filter state.
 *
 * Empty sets mean “all” for a dimension. This keeps reset actions local to
 * their section while [clearAll] also clears the search text.
 */
data class LibraryFilterState(
    val text: String = "",
    val scopes: Set<LibraryScope> = emptySet(),
    val types: Set<LibraryItemType> = emptySet(),
    val groupNames: Set<String> = emptySet(),
    val includeUngrouped: Boolean = false,
    val tags: Set<String> = emptySet(),
    val favoriteOnly: Boolean = false,
    val pathStates: Set<LibraryPathState> = emptySet(),
) {
    fun clearAll(): LibraryFilterState = LibraryFilterState()
    fun clearGroups(): LibraryFilterState = copy(groupNames = emptySet(), includeUngrouped = false)
    fun clearScopes(): LibraryFilterState = copy(scopes = emptySet())
    fun clearTypes(): LibraryFilterState = copy(types = emptySet())
    fun clearTags(): LibraryFilterState = copy(tags = emptySet())
    fun clearPathStates(): LibraryFilterState = copy(pathStates = emptySet())

    fun toQuery(): LibraryQuery = LibraryQuery(
        text = text,
        scopes = scopes,
        types = types,
        groupNames = groupNames,
        includeUngrouped = includeUngrouped,
        tags = tags,
        favoriteOnly = favoriteOnly,
        pathStates = pathStates,
    )
}

/**
 * Visual filter options aggregate display names only; items and groups retain
 * their repository scopes in domain state.
 */
data class LibraryFilterOptions(
    val groupNames: Map<String, String>,
    val tags: Map<String, String>,
    val hasUngrouped: Boolean,
) {
    companion object {
        fun from(records: Collection<LibrarySearchRecord>): LibraryFilterOptions {
            val groups = linkedMapOf<String, String>()
            val tags = linkedMapOf<String, String>()
            records.forEach { record ->
                record.groupName?.trim()?.takeIf(String::isNotEmpty)?.let { name ->
                    groups.putIfAbsent(normalize(name), name)
                }
                record.item.tags.forEach { tag ->
                    tag.trim().takeIf(String::isNotEmpty)?.let { name ->
                        tags.putIfAbsent(normalize(name), name)
                    }
                }
            }
            return LibraryFilterOptions(
                groupNames = groups.toSortedMap(),
                tags = tags.toSortedMap(),
                hasUngrouped = records.any { it.item.groupId == null },
            )
        }

        private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
    }
}

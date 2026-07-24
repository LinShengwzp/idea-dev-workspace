package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.search.LibraryQuery
import com.anmi.devworkspace.library.search.LibrarySearchEngine
import com.anmi.devworkspace.library.search.LibrarySearchRecord

data class LibraryItemKey(
    val scope: LibraryScope,
    val id: String,
)

data class LibraryListItem(
    val item: LibraryItem,
    val groupName: String?,
    val groupScope: LibraryScope?,
) {
    val key: LibraryItemKey = LibraryItemKey(item.scope, item.id)
}

/** Pure presentation mapping; same-named groups remain distinguishable by repository scope. */
class LibraryListModel(
    private val searchEngine: LibrarySearchEngine,
) {
    fun present(
        records: Collection<LibrarySearchRecord>,
        query: LibraryQuery,
    ): List<LibraryListItem> =
        searchEngine.search(records, query).map { record ->
            LibraryListItem(
                item = record.item,
                groupName = record.groupName,
                groupScope = record.item.scope.takeIf { record.item.groupId != null },
            )
        }

    fun selectionIndex(items: List<LibraryListItem>, key: LibraryItemKey?): Int =
        key?.let { selected -> items.indexOfFirst { it.key == selected } } ?: -1
}

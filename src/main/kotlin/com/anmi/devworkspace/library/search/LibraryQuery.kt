package com.anmi.devworkspace.library.search

import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope

enum class LibraryPathState {
    NOT_APPLICABLE,
    AVAILABLE,
    MISSING,
}

/**
 * Search-ready data assembled outside the pure query engine.
 *
 * [markdownBody] must be the current body text for this invocation. The
 * engine deliberately keeps no index-hash-based body cache, so external
 * edits below `contents/` become searchable without changing `library.json`.
 */
data class LibrarySearchRecord(
    val item: LibraryItem,
    val groupName: String? = null,
    val groupOrder: Int = Int.MAX_VALUE,
    val markdownBody: String? = null,
    val pathState: LibraryPathState = LibraryPathState.NOT_APPLICABLE,
    val sourcePathState: LibraryPathState = LibraryPathState.NOT_APPLICABLE,
)

/**
 * Empty selections mean "all".
 *
 * Different non-empty dimensions are combined with AND. Multiple selections
 * within one dimension are combined with OR; tags use case-insensitive exact
 * equality rather than substring matching.
 */
data class LibraryQuery(
    val text: String = "",
    val scopes: Set<LibraryScope> = emptySet(),
    val types: Set<LibraryItemType> = emptySet(),
    val groupIds: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val favoriteOnly: Boolean = false,
    val pathStates: Set<LibraryPathState> = emptySet(),
    val sort: LibrarySort = LibrarySort.DEFAULT,
)

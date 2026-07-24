package com.anmi.devworkspace.library.search

import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.service.LibraryState

/**
 * Rebuilds volatile search data from current external state.
 *
 * Markdown bodies and path status intentionally bypass the library index hash:
 * an explicit refresh must observe edits and deletions even when library.json
 * itself is byte-for-byte unchanged.
 */
class LibraryRecordLoader(
    private val readMarkdown: suspend (LibraryScope, String) -> String?,
    private val pathState: suspend (LibraryItem) -> LibraryPathState,
    private val sourcePathState: suspend (LibraryItem) -> LibraryPathState = {
        LibraryPathState.NOT_APPLICABLE
    },
) {
    suspend fun load(state: LibraryState): List<LibrarySearchRecord> {
        val groups = state.groups.associateBy { it.scope to it.id }
        return state.items.map { item ->
            val group = item.groupId?.let { id -> groups[item.scope to id] }
            LibrarySearchRecord(
                item = item,
                groupName = group?.name,
                groupOrder = group?.order ?: Int.MAX_VALUE,
                markdownBody = if (item.type == LibraryItemType.MARKDOWN) {
                    readMarkdown(item.scope, item.id)
                } else {
                    null
                },
                pathState = pathState(item),
                sourcePathState = sourcePathState(item),
            )
        }
    }
}

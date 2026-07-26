package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.library.domain.LibraryItemSource
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.search.LibraryPathState
import java.time.Instant

enum class LibraryDetailsStatus {
    NOT_APPLICABLE,
    AVAILABLE,
    TARGET_MISSING,
    SOURCE_MISSING,
    TARGET_AND_SOURCE_MISSING,
}

enum class LibraryPreviewMode {
    NONE,
    MARKDOWN,
    IMAGE,
}

data class LibraryDetails(
    val title: String,
    val type: LibraryItemType,
    val scope: LibraryScope,
    val groupName: String?,
    val ungrouped: Boolean,
    val tags: List<String>,
    val status: LibraryDetailsStatus,
    val note: String?,
    val target: String?,
    val source: LibraryItemSource?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val previewMode: LibraryPreviewMode,
)

/** Pure mapping keeps rich-details completeness testable without IntelliJ UI classes. */
class LibraryDetailsModel {
    fun present(row: LibraryListItem): LibraryDetails = LibraryDetails(
        title = row.item.title,
        type = row.item.type,
        scope = row.item.scope,
        groupName = row.groupName,
        ungrouped = row.item.groupId == null,
        tags = row.item.tags.sortedWith(String.CASE_INSENSITIVE_ORDER),
        status = status(row.pathState, row.sourcePathState),
        note = row.item.note,
        target = row.item.target,
        source = row.item.source,
        createdAt = row.item.createdAt,
        updatedAt = row.item.updatedAt,
        previewMode = when (row.item.type) {
            LibraryItemType.MARKDOWN -> LibraryPreviewMode.MARKDOWN
            LibraryItemType.IMAGE -> LibraryPreviewMode.IMAGE
            else -> LibraryPreviewMode.NONE
        },
    )

    private fun status(
        target: LibraryPathState,
        source: LibraryPathState,
    ): LibraryDetailsStatus = when {
        target == LibraryPathState.MISSING && source == LibraryPathState.MISSING ->
            LibraryDetailsStatus.TARGET_AND_SOURCE_MISSING
        target == LibraryPathState.MISSING -> LibraryDetailsStatus.TARGET_MISSING
        source == LibraryPathState.MISSING -> LibraryDetailsStatus.SOURCE_MISSING
        target == LibraryPathState.AVAILABLE || source == LibraryPathState.AVAILABLE ->
            LibraryDetailsStatus.AVAILABLE
        else -> LibraryDetailsStatus.NOT_APPLICABLE
    }
}

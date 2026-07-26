package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.search.LibraryPathState

enum class LibraryIconKind {
    MARKDOWN,
    LINK,
    FILE,
    IMAGE,
    MEDIA,
}

enum class LibraryRowWarning {
    NONE,
    TARGET_MISSING,
    SOURCE_MISSING,
    TARGET_AND_SOURCE_MISSING,
}

data class LibraryRowPresentation(
    val title: String,
    val iconKind: LibraryIconKind,
    val scope: LibraryScope,
    val groupName: String?,
    val tags: List<String>,
    val summary: String?,
    val warning: LibraryRowWarning,
    val favorite: Boolean,
)

/** Pure row mapping keeps content choice and warning rules independent of Swing rendering. */
class LibraryRowPresentationModel {
    fun present(row: LibraryListItem): LibraryRowPresentation {
        val targetMissing = row.pathState == LibraryPathState.MISSING
        val sourceMissing = row.sourcePathState == LibraryPathState.MISSING
        return LibraryRowPresentation(
            title = row.item.title,
            iconKind = row.item.type.toIconKind(),
            scope = row.item.scope,
            groupName = row.groupName,
            tags = row.item.tags.sortedWith(String.CASE_INSENSITIVE_ORDER),
            summary = compact(row.item.note)
                ?: compact(row.markdownBody)
                ?: compact(row.item.target),
            warning = when {
                targetMissing && sourceMissing -> LibraryRowWarning.TARGET_AND_SOURCE_MISSING
                targetMissing -> LibraryRowWarning.TARGET_MISSING
                sourceMissing -> LibraryRowWarning.SOURCE_MISSING
                else -> LibraryRowWarning.NONE
            },
            favorite = row.item.favorite,
        )
    }

    private fun LibraryItemType.toIconKind(): LibraryIconKind = when (this) {
        LibraryItemType.MARKDOWN -> LibraryIconKind.MARKDOWN
        LibraryItemType.LINK -> LibraryIconKind.LINK
        LibraryItemType.FILE -> LibraryIconKind.FILE
        LibraryItemType.IMAGE -> LibraryIconKind.IMAGE
        LibraryItemType.MEDIA -> LibraryIconKind.MEDIA
    }

    private fun compact(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.replace(WHITESPACE, " ")
            ?.takeIf(String::isNotEmpty)
            ?: return null
        return if (normalized.length <= SUMMARY_LIMIT) normalized else normalized.take(SUMMARY_LIMIT) + "…"
    }

    private companion object {
        const val SUMMARY_LIMIT = 120
        val WHITESPACE = Regex("\\s+")
    }
}

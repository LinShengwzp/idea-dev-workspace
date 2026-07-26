package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.library.domain.LibraryItemSource
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope

data class LibraryEditorState(
    val id: String? = null,
    val title: String,
    val type: LibraryItemType,
    val scope: LibraryScope,
    val groupId: String? = null,
    val tags: Set<String> = emptySet(),
    val note: String? = null,
    val favorite: Boolean = false,
    val target: String? = null,
    val markdown: String? = null,
    val source: LibraryItemSource? = null,
)

enum class LibraryEditorField {
    TITLE,
    TARGET,
}

data class LibraryEditorError(
    val field: LibraryEditorField,
    val messageKey: String,
)

object LibraryTargetEditorPolicy {
    fun showsChooser(type: LibraryItemType): Boolean =
        type == LibraryItemType.FILE ||
            type == LibraryItemType.IMAGE ||
            type == LibraryItemType.MEDIA
}

/** UI-independent validation keeps paths and Markdown bodies out of Swing state machinery. */
class LibraryEditorModel {
    fun validate(state: LibraryEditorState): List<LibraryEditorError> = buildList {
        if (state.title.isBlank()) add(LibraryEditorError(LibraryEditorField.TITLE, "library.validation.title"))
        if (state.type != LibraryItemType.MARKDOWN && state.target.isNullOrBlank()) {
            add(LibraryEditorError(LibraryEditorField.TARGET, "library.validation.target"))
        }
    }

    fun toDraft(state: LibraryEditorState): LibraryEditorState =
        state.copy(
            title = state.title.trim(),
            groupId = state.groupId?.trim()?.takeIf(String::isNotEmpty),
            tags = state.tags.mapTo(linkedSetOf()) { it.trim() }.filterTo(linkedSetOf()) { it.isNotEmpty() },
            note = state.note?.trim()?.takeIf(String::isNotEmpty),
            target = if (state.type == LibraryItemType.MARKDOWN) null else state.target?.trim(),
            markdown = state.markdown.orEmpty().takeIf { state.type == LibraryItemType.MARKDOWN },
        )
}

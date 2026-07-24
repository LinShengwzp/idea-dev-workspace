package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.library.domain.LibraryScope

data class LibraryGroupEditorState(
    val name: String,
    val description: String,
    val order: Int,
    val scope: LibraryScope,
)

class LibraryGroupEditorModel {
    fun isValid(state: LibraryGroupEditorState): Boolean =
        state.name.isNotBlank() && state.order >= 0
}

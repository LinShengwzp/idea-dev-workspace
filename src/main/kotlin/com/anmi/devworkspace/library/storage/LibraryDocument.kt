package com.anmi.devworkspace.library.storage

import com.anmi.devworkspace.library.domain.LibraryGroup
import com.anmi.devworkspace.library.domain.LibraryItem

/** The complete versioned index stored by one library scope. */
data class LibraryDocument(
    val version: Int = CURRENT_VERSION,
    val groups: List<LibraryGroup> = emptyList(),
    val items: List<LibraryItem> = emptyList(),
) {
    companion object {
        const val LEGACY_VERSION: Int = 1
        const val CURRENT_VERSION: Int = 2
    }
}

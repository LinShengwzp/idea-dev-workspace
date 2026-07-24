package com.anmi.devworkspace.library.domain

/** Stable origin metadata for content captured from an editor selection. */
data class LibraryItemSource(
    val kind: LibrarySourceKind,
    val path: String,
    val startLine: Int?,
    val endLine: Int?,
) {
    init {
        require((startLine == null) == (endLine == null)) {
            "Source line range must be either fully absent or fully present"
        }
        if (startLine != null && endLine != null) {
            require(startLine >= 1) { "Source startLine must be at least 1" }
            require(endLine >= startLine) { "Source endLine must not precede startLine" }
        }
    }
}

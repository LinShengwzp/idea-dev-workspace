package com.anmi.devworkspace.library.domain

import java.time.Instant

/**
 * Metadata for a library entry.
 *
 * External files remain path references in [target]. Markdown text is stored
 * separately and [contentFile] points to its repository-relative body file.
 */
data class LibraryItem(
    val id: String,
    val title: String,
    val type: LibraryItemType,
    val scope: LibraryScope,
    val groupId: String?,
    val tags: Set<String>,
    val note: String?,
    val favorite: Boolean,
    val target: String?,
    val contentFile: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

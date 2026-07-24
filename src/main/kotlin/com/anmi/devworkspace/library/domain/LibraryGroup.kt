package com.anmi.devworkspace.library.domain

/** A single-level group owned by one library repository. */
data class LibraryGroup(
    val id: String,
    val name: String,
    val description: String?,
    val order: Int,
    val scope: LibraryScope,
)

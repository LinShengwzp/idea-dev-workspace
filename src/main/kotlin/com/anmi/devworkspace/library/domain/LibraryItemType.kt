package com.anmi.devworkspace.library.domain

/** The persisted kind of a library item; it is not re-detected while loading. */
enum class LibraryItemType {
    MARKDOWN,
    LINK,
    FILE,
    IMAGE,
    MEDIA,
}

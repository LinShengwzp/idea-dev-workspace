package com.anmi.devworkspace.library.domain

/**
 * Identifies the repository that owns a library record.
 *
 * Scope is supplied by the repository rather than persisted in `library.json`,
 * so records from all three repositories can coexist without override semantics.
 */
enum class LibraryScope {
    GLOBAL,
    PROJECT_SHARED,
    PROJECT_PRIVATE,
}

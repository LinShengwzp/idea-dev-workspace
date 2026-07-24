package com.anmi.devworkspace.library.storage

import com.anmi.devworkspace.library.domain.LibraryScope
import java.nio.file.Path
import java.time.Instant

/** A validated immutable view of one scope's index at a point in time. */
data class LibrarySnapshot(
    val scope: LibraryScope,
    val document: LibraryDocument,
    val sourceFile: Path,
    val contentHash: String,
    val loadedAt: Instant,
)

/** Sanitized load failure metadata suitable for later UI presentation. */
data class LibraryLoadError(
    val sourceFile: Path,
    val message: String,
)

sealed interface LibraryLoadResult {
    data class Success(val snapshot: LibrarySnapshot) : LibraryLoadResult

    /**
     * The current file is invalid, but this scope has an independently cached
     * last-valid snapshot that remains safe to publish.
     */
    data class Recovered(
        val snapshot: LibrarySnapshot,
        val error: LibraryLoadError,
    ) : LibraryLoadResult

    data class Failure(val error: LibraryLoadError) : LibraryLoadResult
}

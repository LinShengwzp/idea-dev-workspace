package com.anmi.devworkspace.library.storage

import com.anmi.devworkspace.library.domain.LibraryScope
import java.nio.file.Path

/**
 * Persistence boundary for exactly one library scope.
 *
 * Implementations must keep all disk work off the EDT and must never infer
 * override semantics between repositories.
 */
interface LibraryRepository {
    val scope: LibraryScope
    val root: Path
    val path: Path

    suspend fun load(): LibraryLoadResult

    suspend fun save(document: LibraryDocument): LibrarySnapshot
}

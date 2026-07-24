package com.anmi.devworkspace.library.storage

import com.anmi.devworkspace.library.domain.LibraryScope
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Resolves the three independent repository roots.
 *
 * The project-private key is derived from the normalized absolute project
 * path so private data remains outside the project and does not enter Git.
 */
class LibraryRepositoryPaths(
    projectBase: Path,
    configDir: Path,
    systemDir: Path,
) {
    val projectShared: Path = projectBase.resolve(".dev-workspace/library")
    val projectPrivate: Path = systemDir
        .resolve("dev-workspace/projects")
        .resolve(sha256(normalize(projectBase)))
        .resolve("library")
    val global: Path = configDir.resolve("dev-workspace/library")
    val lastValidCacheRoot: Path = systemDir.resolve("dev-workspace/last-valid/library")

    fun root(scope: LibraryScope): Path = when (scope) {
        LibraryScope.GLOBAL -> global
        LibraryScope.PROJECT_SHARED -> projectShared
        LibraryScope.PROJECT_PRIVATE -> projectPrivate
    }

    fun index(scope: LibraryScope): Path = root(scope).resolve("library.json")

    fun contents(scope: LibraryScope): Path = root(scope).resolve("contents")

    private fun normalize(path: Path): String = path.toAbsolutePath().normalize().toString()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

package com.anmi.devworkspace.library.files

import com.anmi.devworkspace.library.domain.LibraryItem
import java.nio.file.Path

/**
 * Converts between persisted forward-slash paths and local absolute paths.
 *
 * Only paths contained by the project use `${PROJECT_DIR}`. Token expansion
 * is containment-checked so a crafted relative segment cannot escape the
 * project root.
 */
class LibraryPathResolver(projectDirectory: Path) {
    private val projectRoot = projectDirectory.toAbsolutePath().normalize()

    fun persist(path: Path): String {
        val normalized = path.toAbsolutePath().normalize()
        if (normalized.startsWith(projectRoot)) {
            val relative = projectRoot.relativize(normalized).toString().replace('\\', '/')
            return if (relative.isEmpty()) PROJECT_TOKEN else "$PROJECT_TOKEN/$relative"
        }
        return normalized.toString().replace('\\', '/')
    }

    fun resolve(storedPath: String): Path {
        val value = storedPath.trim()
        require(value.isNotEmpty()) { "Library path must not be blank" }
        if (value == PROJECT_TOKEN || value.startsWith("$PROJECT_TOKEN/")) {
            val relative = value.removePrefix(PROJECT_TOKEN).removePrefix("/")
            val resolved = projectRoot.resolve(relative).normalize()
            require(resolved.startsWith(projectRoot)) { "Project library path escapes the project root" }
            return resolved
        }

        val path = Path.of(value).normalize()
        require(path.isAbsolute) { "External library path must be absolute" }
        return path
    }

    /** Relocation changes only the stored target and never moves or copies the external file. */
    fun relocate(item: LibraryItem, selectedPath: Path): LibraryItem =
        item.copy(target = persist(selectedPath))

    companion object {
        const val PROJECT_TOKEN: String = "\${PROJECT_DIR}"
    }
}

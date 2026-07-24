package com.anmi.devworkspace.library.service

import com.anmi.devworkspace.library.domain.LibraryItemType
import java.util.Locale

/**
 * Suggests an item type only while creating an entry.
 *
 * The persisted type remains authoritative on later loads, and an explicit
 * [manualOverride] always wins over this heuristic.
 */
class LibraryTypeDetector {
    fun detect(input: String, manualOverride: LibraryItemType? = null): LibraryItemType {
        if (manualOverride != null) return manualOverride
        val value = input.trim()
        val lower = value.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> LibraryItemType.LINK
            extension(lower) in IMAGE_EXTENSIONS -> LibraryItemType.IMAGE
            extension(lower) in MEDIA_EXTENSIONS -> LibraryItemType.MEDIA
            looksLikePath(value) -> LibraryItemType.FILE
            else -> LibraryItemType.MARKDOWN
        }
    }

    private fun extension(value: String): String? {
        val name = value.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.lastIndex) null else name.substring(dot + 1)
    }

    private fun looksLikePath(value: String): Boolean {
        if (value.isBlank() || '\n' in value || '\r' in value) return false
        if (value.startsWith("\${PROJECT_DIR}/") || value.startsWith("\${PROJECT_DIR}\\")) return true
        if (value.startsWith("./") || value.startsWith("../") || value.startsWith(".\\") || value.startsWith("..\\")) {
            return true
        }
        if (value.startsWith("/") || value.startsWith("\\\\") || WINDOWS_DRIVE.matches(value)) return true
        if ('/' in value || '\\' in value) return true
        return extension(value) != null && value.none(Char::isWhitespace)
    }

    private companion object {
        val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/].+")
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "ico", "tif", "tiff")
        val MEDIA_EXTENSIONS = setOf(
            "mp3", "wav", "flac", "aac", "ogg", "m4a",
            "mp4", "mkv", "mov", "avi", "webm", "wmv", "m4v",
        )
    }
}

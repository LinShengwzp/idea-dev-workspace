package com.anmi.devworkspace.library.open

import com.anmi.devworkspace.library.domain.LibraryItemSource
import com.anmi.devworkspace.library.files.LibraryPathResolver
import java.nio.file.InvalidPathException
import java.nio.file.Path

enum class LibrarySourceUnavailableReason {
    NO_PROJECT,
    INVALID_PATH,
    MISSING_FILE,
}

enum class LibrarySourceNavigationWarning {
    START_BEYOND_EOF,
    END_TRUNCATED,
}

sealed interface LibrarySourceNavigationDecision {
    data class Navigate(
        val path: Path,
        val caretLine: Int,
        val selectionStartLine: Int?,
        val selectionEndLine: Int?,
        val warning: LibrarySourceNavigationWarning?,
    ) : LibrarySourceNavigationDecision

    data class Unavailable(
        val reason: LibrarySourceUnavailableReason,
    ) : LibrarySourceNavigationDecision
}

/**
 * Pure source-navigation policy.
 *
 * Stored and resulting lines remain one-based. IntelliJ-specific offset,
 * selection, scrolling, and warning effects belong to the adapter.
 */
class LibrarySourceNavigator(
    projectDirectory: Path?,
) {
    private val resolver = projectDirectory?.let(::LibraryPathResolver)

    fun resolve(source: LibraryItemSource): Result<Path> {
        val pathResolver = resolver ?: return Result.failure(NoProjectException())
        return try {
            Result.success(pathResolver.resolve(source.path))
        } catch (error: InvalidPathException) {
            Result.failure(error)
        } catch (error: IllegalArgumentException) {
            Result.failure(error)
        }
    }

    fun decide(
        source: LibraryItemSource,
        exists: Boolean,
        lineCount: Int?,
    ): LibrarySourceNavigationDecision {
        val path = resolve(source).getOrElse { error ->
            return LibrarySourceNavigationDecision.Unavailable(
                if (error is NoProjectException) {
                    LibrarySourceUnavailableReason.NO_PROJECT
                } else {
                    LibrarySourceUnavailableReason.INVALID_PATH
                },
            )
        }
        if (!exists || lineCount == null) {
            return LibrarySourceNavigationDecision.Unavailable(
                LibrarySourceUnavailableReason.MISSING_FILE,
            )
        }
        val availableLines = lineCount.coerceAtLeast(1)
        val requestedStart = source.startLine
        val requestedEnd = source.endLine
        if (requestedStart == null || requestedEnd == null) {
            return LibrarySourceNavigationDecision.Navigate(
                path = path,
                caretLine = 1,
                selectionStartLine = null,
                selectionEndLine = null,
                warning = null,
            )
        }
        if (requestedStart > availableLines) {
            return LibrarySourceNavigationDecision.Navigate(
                path = path,
                caretLine = availableLines,
                selectionStartLine = availableLines,
                selectionEndLine = availableLines,
                warning = LibrarySourceNavigationWarning.START_BEYOND_EOF,
            )
        }
        val truncatedEnd = requestedEnd.coerceAtMost(availableLines)
        return LibrarySourceNavigationDecision.Navigate(
            path = path,
            caretLine = requestedStart,
            selectionStartLine = requestedStart,
            selectionEndLine = truncatedEnd,
            warning = LibrarySourceNavigationWarning.END_TRUNCATED.takeIf {
                requestedEnd > availableLines
            },
        )
    }

    private class NoProjectException : IllegalStateException()
}

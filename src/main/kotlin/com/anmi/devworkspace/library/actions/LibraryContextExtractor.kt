package com.anmi.devworkspace.library.actions

import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.files.LibraryPathResolver
import com.anmi.devworkspace.library.service.LibraryTypeDetector
import java.nio.file.Path

data class EditorCapture(
    val projectDirectory: Path,
    val file: Path,
    val selection: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val language: String? = null,
)

data class LibraryQuickAddDraft(
    val title: String,
    val scope: LibraryScope,
    val type: LibraryItemType,
    val groupId: String? = null,
    val tags: Set<String> = emptySet(),
    val sourceDescription: String,
    val target: String? = null,
    val markdown: String? = null,
)

/** Pure context-to-draft conversion; IntelliJ DataContext access stays in the action adapter. */
class LibraryContextExtractor(
    private val types: LibraryTypeDetector = LibraryTypeDetector(),
) {
    fun fromEditor(capture: EditorCapture): LibraryQuickAddDraft {
        val resolver = LibraryPathResolver(capture.projectDirectory)
        val storedPath = resolver.persist(capture.file)
        val relative = storedPath.removePrefix("${LibraryPathResolver.PROJECT_TOKEN}/")
        val selected = capture.selection?.takeIf(String::isNotEmpty)
        if (selected == null) {
            return LibraryQuickAddDraft(
                title = capture.file.fileName.toString(),
                scope = LibraryScope.PROJECT_PRIVATE,
                type = types.detect(storedPath),
                sourceDescription = relative,
                target = storedPath,
            )
        }
        val lines = if (capture.startLine != null && capture.endLine != null) {
            ":${capture.startLine}" + if (capture.endLine != capture.startLine) "-${capture.endLine}" else ""
        } else {
            ""
        }
        val body = capture.language?.takeIf(String::isNotBlank)?.let { language ->
            "```$language\n$selected\n```"
        } ?: selected
        return LibraryQuickAddDraft(
            title = capture.file.fileName.toString(),
            scope = LibraryScope.PROJECT_PRIVATE,
            type = LibraryItemType.MARKDOWN,
            sourceDescription = "$relative$lines",
            markdown = body,
        )
    }

    fun fromClipboard(text: String): LibraryQuickAddDraft {
        val value = text.trim()
        val type = types.detect(value)
        return LibraryQuickAddDraft(
            title = value.lineSequence().first().take(80),
            scope = LibraryScope.GLOBAL,
            type = type,
            sourceDescription = "clipboard",
            target = value.takeIf { type != LibraryItemType.MARKDOWN },
            markdown = value.takeIf { type == LibraryItemType.MARKDOWN },
        )
    }
}

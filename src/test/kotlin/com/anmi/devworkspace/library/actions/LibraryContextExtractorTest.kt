package com.anmi.devworkspace.library.actions

import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.domain.LibrarySourceKind
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryContextExtractorTest {
    private val extractor = LibraryContextExtractor()

    @Test
    fun `code selection becomes fenced project private markdown`() {
        val draft = extractor.fromEditor(
            EditorCapture(
                projectDirectory = Path.of("D:/work/app"),
                file = Path.of("D:/work/app/src/Main.kt"),
                selection = "fun main() {}",
                startLine = 4,
                endLine = 4,
                language = "kotlin",
            ),
        )

        assertEquals(LibraryScope.PROJECT_PRIVATE, draft.scope)
        assertEquals(LibraryItemType.MARKDOWN, draft.type)
        assertEquals("```kotlin\nfun main() {}\n```", draft.markdown)
        assertEquals(LibrarySourceKind.EDITOR_SELECTION, draft.source?.kind)
        assertEquals("\${PROJECT_DIR}/src/Main.kt", draft.source?.path)
        assertEquals(4, draft.source?.startLine)
        assertEquals(4, draft.source?.endLine)
        assertTrue(draft.sourceDescription.contains("src/Main.kt"))
    }

    @Test
    fun `editor without selection captures current file path`() {
        val draft = extractor.fromEditor(
            EditorCapture(
                projectDirectory = Path.of("D:/work/app"),
                file = Path.of("D:/work/app/readme.pdf"),
            ),
        )

        assertEquals(LibraryItemType.FILE, draft.type)
        assertEquals("\${PROJECT_DIR}/readme.pdf", draft.target)
    }

    @Test
    fun `context free clipboard link defaults global`() {
        val draft = extractor.fromClipboard("https://example.test/docs")

        assertEquals(LibraryScope.GLOBAL, draft.scope)
        assertEquals(LibraryItemType.LINK, draft.type)
    }

    @Test
    fun `explicit editor selection wins every other context`() {
        val result = extractor.resolve(
            LibraryCaptureCandidates(
                editorSelection = editor("src/Selection.kt", selection = "selected"),
                projectFiles = listOf(editor("project.txt")),
                focusedEditor = editor("focused.txt"),
                clipboard = "https://clipboard.example",
            ),
        )

        assertEquals("Selection.kt", result.single().title)
        assertEquals(LibraryItemType.MARKDOWN, result.single().type)
    }

    @Test
    fun `project view selection wins focused editor and clipboard`() {
        val result = extractor.resolve(
            LibraryCaptureCandidates(
                projectFiles = listOf(editor("one.png"), editor("two.mp4")),
                focusedEditor = editor("focused.txt"),
                clipboard = "https://clipboard.example",
            ),
        )

        assertEquals(listOf("one.png", "two.mp4"), result.map { it.title })
    }

    @Test
    fun `focused editor wins clipboard`() {
        val result = extractor.resolve(
            LibraryCaptureCandidates(
                focusedEditor = editor("focused.txt"),
                clipboard = "https://clipboard.example",
            ),
        )

        assertEquals("focused.txt", result.single().title)
    }

    @Test
    fun `unfocused editor does not suppress clipboard URL`() {
        val result = extractor.resolve(
            LibraryCaptureCandidates(
                clipboard = "https://clipboard.example",
            ),
        )

        assertEquals("https://clipboard.example", result.single().target)
        assertEquals(LibraryScope.GLOBAL, result.single().scope)
    }

    private fun editor(relative: String, selection: String? = null) = EditorCapture(
        projectDirectory = Path.of("D:/work/app"),
        file = Path.of("D:/work/app").resolve(relative),
        selection = selection,
        startLine = selection?.let { 2 },
        endLine = selection?.let { 3 },
        language = selection?.let { "kotlin" },
    )
}

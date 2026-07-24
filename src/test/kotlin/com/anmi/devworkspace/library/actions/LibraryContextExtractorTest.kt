package com.anmi.devworkspace.library.actions

import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
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
        assertTrue(draft.sourceDescription.contains("src/Main.kt:4"))
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
}

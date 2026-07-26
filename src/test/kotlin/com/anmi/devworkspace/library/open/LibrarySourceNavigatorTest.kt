package com.anmi.devworkspace.library.open

import com.anmi.devworkspace.library.domain.LibraryItemSource
import com.anmi.devworkspace.library.domain.LibrarySourceKind
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LibrarySourceNavigatorTest {
    private val project = Path.of("D:/workspace/project").toAbsolutePath().normalize()
    private val navigator = LibrarySourceNavigator(project)

    @Test
    fun `valid source range retains one-based caret and selection lines`() {
        val decision = navigator.decide(source(3, 5), exists = true, lineCount = 10)

        assertEquals(
            LibrarySourceNavigationDecision.Navigate(
                path = project.resolve("src/Main.kt"),
                caretLine = 3,
                selectionStartLine = 3,
                selectionEndLine = 5,
                warning = null,
            ),
            decision,
        )
    }

    @Test
    fun `missing source file is unavailable`() {
        val decision = navigator.decide(source(3, 5), exists = false, lineCount = null)

        assertEquals(
            LibrarySourceNavigationDecision.Unavailable(LibrarySourceUnavailableReason.MISSING_FILE),
            decision,
        )
    }

    @Test
    fun `start beyond EOF navigates to last available line and warns`() {
        val decision = assertIs<LibrarySourceNavigationDecision.Navigate>(
            navigator.decide(source(20, 22), exists = true, lineCount = 10),
        )

        assertEquals(10, decision.caretLine)
        assertEquals(10, decision.selectionStartLine)
        assertEquals(10, decision.selectionEndLine)
        assertEquals(LibrarySourceNavigationWarning.START_BEYOND_EOF, decision.warning)
    }

    @Test
    fun `end beyond EOF truncates selection and warns`() {
        val decision = assertIs<LibrarySourceNavigationDecision.Navigate>(
            navigator.decide(source(3, 20), exists = true, lineCount = 10),
        )

        assertEquals(3, decision.caretLine)
        assertEquals(3, decision.selectionStartLine)
        assertEquals(10, decision.selectionEndLine)
        assertEquals(LibrarySourceNavigationWarning.END_TRUNCATED, decision.warning)
    }

    @Test
    fun `missing project context is unavailable without resolving path`() {
        val decision = LibrarySourceNavigator(null)
            .decide(source(1, 1), exists = true, lineCount = 1)

        assertEquals(
            LibrarySourceNavigationDecision.Unavailable(LibrarySourceUnavailableReason.NO_PROJECT),
            decision,
        )
    }

    private fun source(start: Int, end: Int) = LibraryItemSource(
        kind = LibrarySourceKind.EDITOR_SELECTION,
        path = "\${PROJECT_DIR}/src/Main.kt",
        startLine = start,
        endLine = end,
    )
}

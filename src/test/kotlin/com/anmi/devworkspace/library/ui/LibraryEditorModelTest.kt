package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryItemSource
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.domain.LibrarySourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryEditorModelTest {
    private val model = LibraryEditorModel()

    @Test
    fun `requires title and target for a file reference`() {
        val errors = model.validate(
            LibraryEditorState(
                title = "",
                type = LibraryItemType.FILE,
                scope = LibraryScope.PROJECT_PRIVATE,
                target = "",
            ),
        )

        assertEquals(setOf(LibraryEditorField.TITLE, LibraryEditorField.TARGET), errors.map { it.field }.toSet())
    }

    @Test
    fun `markdown keeps body separate from persisted target`() {
        val source = LibraryItemSource(
            LibrarySourceKind.EDITOR_SELECTION,
            "\${PROJECT_DIR}/src/Main.kt",
            2,
            3,
        )
        val draft = model.toDraft(
            LibraryEditorState(
                title = "片段",
                type = LibraryItemType.MARKDOWN,
                scope = LibraryScope.GLOBAL,
                markdown = "正文",
                source = source,
            ),
        )

        assertTrue(model.validate(draft).isEmpty())
        assertEquals(null, draft.target)
        assertEquals("正文", draft.markdown)
        assertEquals(source, draft.source)
    }
}

package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.library.domain.LibraryScope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryGroupEditorModelTest {
    @Test
    fun `group requires a name and non negative order`() {
        val model = LibraryGroupEditorModel()

        assertFalse(model.isValid(LibraryGroupEditorState("", "", -1, LibraryScope.GLOBAL)))
        assertTrue(model.isValid(LibraryGroupEditorState("文档", "", 0, LibraryScope.PROJECT_PRIVATE)))
    }
}

package com.anmi.devworkspace.library.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryTagChipEditorModelTest {
    @Test
    fun `enter commits one trimmed tag`() {
        val model = LibraryTagChipEditorModel()

        assertEquals(listOf("kotlin"), model.commit("  kotlin  ").tags)
        assertEquals("", model.state.input)
    }

    @Test
    fun `commas semicolons and pasted text create multiple tags`() {
        val model = LibraryTagChipEditorModel()

        model.commit("kotlin, jvm，IDEA; plugin；tools")

        assertEquals(listOf("kotlin", "jvm", "IDEA", "plugin", "tools"), model.state.tags)
    }

    @Test
    fun `empty tags are ignored and duplicates are case insensitive`() {
        val model = LibraryTagChipEditorModel(listOf("Kotlin"))

        model.commit(" ; kotlin, KOTLIN, jvm ,, ")

        assertEquals(listOf("Kotlin", "jvm"), model.state.tags)
    }

    @Test
    fun `backspace with empty input removes last tag`() {
        val model = LibraryTagChipEditorModel(listOf("one", "two"))

        model.backspace("")

        assertEquals(listOf("one"), model.state.tags)
    }

    @Test
    fun `backspace with text keeps tags and explicit remove deletes requested tag`() {
        val model = LibraryTagChipEditorModel(listOf("one", "two"))

        model.backspace("draft")
        assertEquals(listOf("one", "two"), model.state.tags)
        model.remove("one")

        assertEquals(listOf("two"), model.state.tags)
    }
}

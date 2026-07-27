package com.anmi.devworkspace.markmap.settings

import com.anmi.devworkspace.markmap.MarkmapSuffixMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkmapSettingsStateTest {
    @Test
    fun `new settings use default suffixes`() {
        assertEquals(
            listOf("mm.md", "mm.markdown"),
            MarkmapSettingsState().suffixes(),
        )
    }

    @Test
    fun `loading and updating settings normalizes suffixes`() {
        val settings = MarkmapSettingsState()

        settings.loadState(
            MarkmapSettingsState.State(
                mutableListOf(" .MM.MD ", "", "mm.md", "..mind.md"),
            ),
        )

        assertEquals(listOf("mm.md", "mind.md"), settings.suffixes())
    }

    @Test
    fun `editor model applies normalizes and resets saved suffixes`() {
        val settings = MarkmapSettingsState()
        val model = MarkmapSettingsEditorModel(settings)

        model.text = " .MM.MD \n\n.mm.md\nmind.md"
        assertTrue(model.isModified())
        model.apply()

        assertEquals(listOf("mm.md", "mind.md"), settings.suffixes())
        assertFalse(model.isModified())

        model.text = "other.md"
        model.reset()
        assertEquals("mm.md\nmind.md", model.text)
        assertFalse(model.isModified())
    }

    @Test
    fun `restore defaults changes editor without saving until apply`() {
        val settings = MarkmapSettingsState().apply {
            updateSuffixes(listOf("mind.md"))
        }
        val model = MarkmapSettingsEditorModel(settings)

        model.restoreDefaults()

        assertEquals(MarkmapSuffixMatcher.DEFAULT_SUFFIXES.joinToString("\n"), model.text)
        assertEquals(listOf("mind.md"), settings.suffixes())
        assertTrue(model.isModified())

        model.apply()
        assertEquals(MarkmapSuffixMatcher.DEFAULT_SUFFIXES, settings.suffixes())
    }
}

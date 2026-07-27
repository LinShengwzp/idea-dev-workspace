package com.anmi.devworkspace.markmap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkmapSuffixMatcherTest {
    @Test
    fun `default mm md suffix matches`() {
        val matcher = MarkmapSuffixMatcher { MarkmapSuffixMatcher.DEFAULT_SUFFIXES }

        assertTrue(matcher.matches("architecture.mm.md"))
    }

    @Test
    fun `default mm markdown suffix matches`() {
        val matcher = MarkmapSuffixMatcher { MarkmapSuffixMatcher.DEFAULT_SUFFIXES }

        assertTrue(matcher.matches("notes.mm.markdown"))
    }

    @Test
    fun `matching ignores case`() {
        val matcher = MarkmapSuffixMatcher { MarkmapSuffixMatcher.DEFAULT_SUFFIXES }

        assertTrue(matcher.matches("TEST.MM.MD"))
    }

    @Test
    fun `plain markdown does not match`() {
        val matcher = MarkmapSuffixMatcher { MarkmapSuffixMatcher.DEFAULT_SUFFIXES }

        assertFalse(matcher.matches("README.md"))
        assertFalse(matcher.matches("notes.markdown"))
        assertFalse(matcher.matches("example.md.txt"))
    }

    @Test
    fun `xmm md does not cross suffix boundary`() {
        val matcher = MarkmapSuffixMatcher { listOf("mm.md") }

        assertFalse(matcher.matches("xmm.md"))
        assertTrue(matcher.matches("mm.md"))
        assertTrue(matcher.matches("architecture.mm.md"))
    }

    @Test
    fun `leading dots blanks and duplicates normalize`() {
        assertEquals(
            listOf("mm.md", "mm.markdown"),
            MarkmapSuffixMatcher.normalizeSuffixes(
                listOf(" .MM.MD ", "", "   ", "...mm.markdown", "mm.md"),
            ),
        )
    }

    @Test
    fun `empty suffix list disables matching`() {
        val matcher = MarkmapSuffixMatcher { emptyList() }

        assertFalse(matcher.matches("architecture.mm.md"))
    }

    @Test
    fun `settings changes affect the next match`() {
        var suffixes = listOf("mm.md")
        val matcher = MarkmapSuffixMatcher { suffixes }

        assertTrue(matcher.matches("architecture.mm.md"))
        suffixes = listOf("mind.md")
        assertFalse(matcher.matches("architecture.mm.md"))
        assertTrue(matcher.matches("architecture.mind.md"))
    }
}

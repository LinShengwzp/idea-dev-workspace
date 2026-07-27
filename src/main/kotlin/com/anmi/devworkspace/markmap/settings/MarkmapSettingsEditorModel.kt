package com.anmi.devworkspace.markmap.settings

import com.anmi.devworkspace.markmap.MarkmapSuffixMatcher

/**
 * UI-independent lifecycle for Apply, Reset, and Restore Defaults.
 */
class MarkmapSettingsEditorModel(
    private val settings: MarkmapSettingsState,
) {
    var text: String = settings.suffixes().joinToString("\n")

    fun isModified(): Boolean =
        normalizedText() != settings.suffixes()

    fun apply() {
        settings.updateSuffixes(text.lineSequence().toList())
        text = settings.suffixes().joinToString("\n")
    }

    fun reset() {
        text = settings.suffixes().joinToString("\n")
    }

    fun restoreDefaults() {
        text = MarkmapSuffixMatcher.DEFAULT_SUFFIXES.joinToString("\n")
    }

    private fun normalizedText(): List<String> =
        MarkmapSuffixMatcher.normalizeSuffixes(text.lineSequence().toList())
}

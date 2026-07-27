package com.anmi.devworkspace.markmap

import com.anmi.devworkspace.markmap.settings.MarkmapSettingsState
import java.util.Locale

/**
 * Matches configured full filename suffixes without treating ordinary Markdown as MarkMap input.
 *
 * The provider is evaluated for every match so application-level setting changes take effect on
 * the next Action update without restarting the IDE.
 */
class MarkmapSuffixMatcher(
    private val suffixesProvider: () -> Iterable<String> = {
        MarkmapSettingsState.getInstance().suffixes()
    },
) {
    fun matches(fileName: String): Boolean {
        val normalizedName = fileName.lowercase(Locale.ROOT)
        return normalizeSuffixes(suffixesProvider()).any { suffix ->
            normalizedName == suffix || normalizedName.endsWith(".$suffix")
        }
    }

    companion object {
        val DEFAULT_SUFFIXES: List<String> = listOf("mm.md", "mm.markdown")

        fun normalizeSuffixes(values: Iterable<String>): List<String> =
            values.asSequence()
                .map { it.trim().trimStart('.').lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
    }
}

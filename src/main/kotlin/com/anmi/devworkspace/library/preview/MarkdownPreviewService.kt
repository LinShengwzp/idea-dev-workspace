package com.anmi.devworkspace.library.preview

import com.anmi.devworkspace.library.storage.MarkdownContentStore
import java.net.URI

data class MarkdownPreview(
    val source: String,
    val renderedHtml: String,
)

/**
 * Loads the current Markdown body and produces a deliberately small safe HTML subset.
 *
 * Bodies are not cached by the index hash: each invocation reads the content
 * store, so an external body edit updates both source and rendered preview.
 * Raw HTML is escaped and only safe link schemes receive anchor elements.
 */
class MarkdownPreviewService(
    private val contentStore: MarkdownContentStore,
) {
    suspend fun load(itemId: String): MarkdownPreview {
        val source = contentStore.read(itemId).orEmpty()
        return MarkdownPreview(source, SafeMarkdownRenderer.render(source))
    }
}

internal object SafeMarkdownRenderer {
    private val heading = Regex("^(#{1,6})\\s+(.+)$")
    private val unordered = Regex("^\\s*[-+*]\\s+(.+)$")
    private val ordered = Regex("^\\s*\\d+[.)]\\s+(.+)$")
    private val inline = Regex("`([^`]+)`|\\[([^]]+)]\\(([^)]+)\\)")

    fun render(source: String): String {
        val html = StringBuilder("<html><body>")
        var list: String? = null
        var inCodeBlock = false

        fun closeList() {
            list?.let { html.append("</").append(it).append('>') }
            list = null
        }

        source.lineSequence().forEach { line ->
            if (line.trimStart().startsWith("```")) {
                closeList()
                if (inCodeBlock) html.append("</code></pre>") else html.append("<pre><code>")
                inCodeBlock = !inCodeBlock
                return@forEach
            }
            if (inCodeBlock) {
                html.append(escape(line)).append('\n')
                return@forEach
            }
            if (line.isBlank()) {
                closeList()
                return@forEach
            }

            val headingMatch = heading.matchEntire(line)
            val unorderedMatch = unordered.matchEntire(line)
            val orderedMatch = ordered.matchEntire(line)
            when {
                headingMatch != null -> {
                    closeList()
                    val level = headingMatch.groupValues[1].length
                    html.append("<h").append(level).append('>')
                        .append(renderInline(headingMatch.groupValues[2]))
                        .append("</h").append(level).append('>')
                }
                unorderedMatch != null -> {
                    if (list != "ul") {
                        closeList()
                        list = "ul"
                        html.append("<ul>")
                    }
                    html.append("<li>").append(renderInline(unorderedMatch.groupValues[1])).append("</li>")
                }
                orderedMatch != null -> {
                    if (list != "ol") {
                        closeList()
                        list = "ol"
                        html.append("<ol>")
                    }
                    html.append("<li>").append(renderInline(orderedMatch.groupValues[1])).append("</li>")
                }
                else -> {
                    closeList()
                    html.append("<p>").append(renderInline(line)).append("</p>")
                }
            }
        }
        closeList()
        if (inCodeBlock) html.append("</code></pre>")
        return html.append("</body></html>").toString()
    }

    private fun renderInline(value: String): String {
        val result = StringBuilder()
        var offset = 0
        inline.findAll(value).forEach { match ->
            result.append(escape(value.substring(offset, match.range.first)))
            val code = match.groups[1]?.value
            if (code != null) {
                result.append("<code>").append(escape(code)).append("</code>")
            } else {
                val label = match.groups[2]?.value.orEmpty()
                val target = match.groups[3]?.value.orEmpty()
                if (isSafeLink(target)) {
                    result.append("<a href=\"").append(escapeAttribute(target)).append("\">")
                        .append(escape(label)).append("</a>")
                } else {
                    result.append(escape(label))
                }
            }
            offset = match.range.last + 1
        }
        result.append(escape(value.substring(offset)))
        return result.toString()
    }

    private fun isSafeLink(target: String): Boolean =
        try {
            URI(target).scheme?.lowercase() in setOf("http", "https", "mailto")
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> character
                },
            )
        }
    }

    private fun escapeAttribute(value: String): String = escape(value)
}

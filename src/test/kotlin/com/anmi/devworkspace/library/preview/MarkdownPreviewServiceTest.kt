package com.anmi.devworkspace.library.preview

import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.search.LibraryQuery
import com.anmi.devworkspace.library.search.LibrarySearchEngine
import com.anmi.devworkspace.library.search.LibrarySearchRecord
import com.anmi.devworkspace.library.storage.MarkdownContentStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownPreviewServiceTest {
    @Test
    fun `renders supported Markdown while escaping scripts`() = withTemporaryDirectory { root ->
        val store = MarkdownContentStore(root)
        store.write(
            "notes",
            """
                # 标题
                - 第一项
                - 第二项

                `code`
                [OpenAI](https://openai.com)
                <script>alert("unsafe")</script>
            """.trimIndent(),
        )

        val preview = MarkdownPreviewService(store).load("notes")

        assertTrue(preview.renderedHtml.contains("<h1>标题</h1>"))
        assertTrue(preview.renderedHtml.contains("<ul>"))
        assertTrue(preview.renderedHtml.contains("<code>code</code>"))
        assertTrue(preview.renderedHtml.contains("""href="https://openai.com""""))
        assertFalse(preview.renderedHtml.contains("<script"))
        assertTrue(preview.renderedHtml.contains("&lt;script&gt;"))
    }

    @Test
    fun `external body edit refreshes preview and searchable body without index change`() =
        withTemporaryDirectory { root ->
            val store = MarkdownContentStore(root)
            val service = MarkdownPreviewService(store)
            val item = markdownItem()
            store.write(item.id, "old body")
            val first = service.load(item.id)
            assertEquals("old body", first.source)

            Files.writeString(root.resolve("contents/${item.id}.md"), "externally refreshed body")
            val refreshed = service.load(item.id)
            val searchResult = LibrarySearchEngine().search(
                listOf(LibrarySearchRecord(item, markdownBody = refreshed.source)),
                LibraryQuery(text = "refreshed"),
            )

            assertEquals("externally refreshed body", refreshed.source)
            assertEquals(listOf(item.id), searchResult.map { it.item.id })
        }

    private fun markdownItem() = LibraryItem(
        id = "notes",
        title = "Notes",
        type = LibraryItemType.MARKDOWN,
        scope = LibraryScope.PROJECT_SHARED,
        groupId = null,
        tags = emptySet(),
        note = null,
        favorite = false,
        target = null,
        contentFile = "contents/notes.md",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun withTemporaryDirectory(block: suspend (Path) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("dev-library-markdown-preview")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}

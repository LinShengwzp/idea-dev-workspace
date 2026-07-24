package com.anmi.devworkspace.library.storage

import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MarkdownContentStoreTest {
    @Test
    fun `Markdown body is atomically written below contents`() = withTemporaryDirectory { root ->
        val store = MarkdownContentStore(root)

        store.write("notes", "# Notes")

        assertEquals("# Notes", store.read("notes"))
        assertEquals("# Notes", Files.readString(root.resolve("contents/notes.md")))
    }

    @Test
    fun `item IDs cannot escape the contents directory`() = withTemporaryDirectory { root ->
        val store = MarkdownContentStore(root)

        assertFailsWith<IllegalArgumentException> {
            store.write("../outside", "unsafe")
        }
        assertFalse(Files.exists(root.parent.resolve("outside.md")))
    }

    @Test
    fun `deleting Markdown item removes only its internal body`() = withTemporaryDirectory { root ->
        val store = MarkdownContentStore(root)
        store.write("notes", "# Notes")

        assertTrue(store.delete(item("notes", LibraryItemType.MARKDOWN)))
        assertFalse(Files.exists(root.resolve("contents/notes.md")))
    }

    @Test
    fun `deleting external item never deletes its target`() = withTemporaryDirectory { root ->
        val external = Files.createTempFile("dev-library-external", ".txt")
        try {
            val store = MarkdownContentStore(root)

            assertFalse(store.delete(item("external", LibraryItemType.FILE, external.toString())))
            assertTrue(Files.exists(external))
        } finally {
            Files.deleteIfExists(external)
        }
    }

    private fun item(id: String, type: LibraryItemType, target: String? = null) = LibraryItem(
        id = id,
        title = id,
        type = type,
        scope = LibraryScope.PROJECT_SHARED,
        groupId = null,
        tags = emptySet(),
        note = null,
        favorite = false,
        target = target,
        contentFile = if (type == LibraryItemType.MARKDOWN) "contents/$id.md" else null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun withTemporaryDirectory(block: suspend (Path) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("dev-library-content-test")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}

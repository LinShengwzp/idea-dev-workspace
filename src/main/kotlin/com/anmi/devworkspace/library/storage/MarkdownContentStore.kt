package com.anmi.devworkspace.library.storage

import com.anmi.devworkspace.config.AtomicFileWriter
import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns Markdown bodies below a repository's `contents` directory.
 *
 * It never follows [LibraryItem.target], so deleting a library record cannot
 * delete an external file referenced by that record.
 */
class MarkdownContentStore(
    private val repositoryRoot: Path,
    private val writer: AtomicFileWriter = AtomicFileWriter(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun read(itemId: String): String? = withContext(ioDispatcher) {
        val path = bodyPath(itemId)
        if (Files.exists(path)) Files.readString(path, StandardCharsets.UTF_8) else null
    }

    suspend fun write(itemId: String, content: String): Unit = withContext(ioDispatcher) {
        writer.write(bodyPath(itemId), content) { }
    }

    suspend fun delete(item: LibraryItem): Boolean = withContext(ioDispatcher) {
        if (item.type != LibraryItemType.MARKDOWN) return@withContext false
        Files.deleteIfExists(bodyPath(item.id))
    }

    fun relativePath(itemId: String): String {
        validateItemId(itemId)
        return "contents/$itemId.md"
    }

    private fun bodyPath(itemId: String): Path {
        validateItemId(itemId)
        val contents = repositoryRoot.resolve("contents").toAbsolutePath().normalize()
        val candidate = contents.resolve("$itemId.md").normalize()
        require(candidate.parent == contents) { "Markdown body must remain inside contents" }
        return candidate
    }

    private fun validateItemId(itemId: String) {
        require(itemId.matches(ITEM_ID)) { "Invalid Markdown item ID" }
    }

    private companion object {
        val ITEM_ID = Regex("[A-Za-z0-9_-]+")
    }
}

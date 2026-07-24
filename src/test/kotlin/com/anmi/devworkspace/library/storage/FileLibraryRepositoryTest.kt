package com.anmi.devworkspace.library.storage

import com.anmi.devworkspace.config.AtomicFileWriter
import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Comparator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileLibraryRepositoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-24T10:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `missing repository loads as valid empty snapshot`() = withTemporaryDirectory { directory ->
        val repository = repository(directory)

        val result = assertIs<LibraryLoadResult.Success>(repository.load())

        assertEquals(LibraryDocument(), result.snapshot.document)
        assertEquals(LibraryScope.PROJECT_SHARED, result.snapshot.scope)
        assertEquals(directory.resolve("library.json"), result.snapshot.sourceFile)
    }

    @Test
    fun `save atomically persists and reloads JSON`() = withTemporaryDirectory { directory ->
        val repository = repository(directory)
        val document = LibraryDocument(items = listOf(link("saved")))

        val saved = repository.save(document)
        val loaded = assertIs<LibraryLoadResult.Success>(repository.load()).snapshot

        assertEquals(saved.contentHash, loaded.contentHash)
        assertEquals(document, loaded.document)
        assertTrue(Files.readString(repository.path).contains("\"saved\""))
    }

    @Test
    fun `failed replacement leaves previous JSON intact`() = withTemporaryDirectory { directory ->
        val originalRepository = repository(directory)
        originalRepository.save(LibraryDocument(items = listOf(link("original"))))
        val originalText = Files.readString(originalRepository.path)
        val failingRepository = repository(
            directory,
            AtomicFileWriter { throw IOException("simulated replace failure") },
        )

        assertFailsWith<IOException> {
            failingRepository.save(LibraryDocument(items = listOf(link("replacement"))))
        }

        assertEquals(originalText, Files.readString(originalRepository.path))
    }

    @Test
    fun `corrupt layer recovers only its own last valid snapshot`() = withTemporaryDirectory { directory ->
        val repository = repository(directory)
        val valid = LibraryDocument(items = listOf(link("retained")))
        repository.save(valid)
        Files.writeString(repository.path, "{not-json")

        val recovered = assertIs<LibraryLoadResult.Recovered>(repository.load())

        assertEquals(valid, recovered.snapshot.document)
        assertEquals(repository.path, recovered.error.sourceFile)
        assertTrue(recovered.error.message.isNotBlank())
    }

    @Test
    fun `noncanonical valid JSON still refreshes recovery snapshot`() = withTemporaryDirectory { directory ->
        val repository = repository(directory)
        Files.createDirectories(directory)
        Files.writeString(
            repository.path,
            """{"items":[{"updatedAt":"1970-01-01T00:00:00Z","createdAt":"1970-01-01T00:00:00Z","contentFile":null,"target":"https://example.com/external","favorite":false,"note":null,"tags":[],"groupId":null,"type":"LINK","title":"external","id":"external"}],"groups":[],"version":1}""",
        )
        assertIs<LibraryLoadResult.Success>(repository.load())
        Files.writeString(repository.path, "{not-json")

        val recovered = assertIs<LibraryLoadResult.Recovered>(repository.load())

        assertEquals("external", recovered.snapshot.document.items.single().id)
    }

    @Test
    fun `saving removal deletes only internal Markdown body`() = withTemporaryDirectory { directory ->
        val repository = repository(directory)
        val contents = MarkdownContentStore(directory)
        val external = Files.createTempFile("dev-library-target", ".txt")
        try {
            val markdown = markdown("notes")
            repository.save(
                LibraryDocument(
                    items = listOf(markdown, link("external", external.toString())),
                ),
            )
            contents.write(markdown.id, "# Notes")

            repository.save(LibraryDocument())

            assertEquals(null, contents.read(markdown.id))
            assertTrue(Files.exists(external))
        } finally {
            Files.deleteIfExists(external)
        }
    }

    @Test
    fun `corrupt first load reports failure without inventing data`() = withTemporaryDirectory { directory ->
        val repository = repository(directory)
        Files.createDirectories(directory)
        Files.writeString(repository.path, "{not-json")

        val failure = assertIs<LibraryLoadResult.Failure>(repository.load())

        assertEquals(repository.path, failure.error.sourceFile)
    }

    private fun repository(
        root: Path,
        writer: AtomicFileWriter = AtomicFileWriter(),
    ): FileLibraryRepository {
        val codec = LibraryJsonCodec()
        val cache = LibraryLastValidStore(
            codec = codec,
            writer = AtomicFileWriter(),
            cacheRoot = root.resolveSibling("${root.fileName}-last-valid"),
        )
        return FileLibraryRepository(
            scope = LibraryScope.PROJECT_SHARED,
            root = root,
            codec = codec,
            atomicWriter = writer,
            lastValidStore = cache,
            clock = clock,
        )
    }

    private fun link(id: String, target: String = "https://example.com/$id") = LibraryItem(
        id = id,
        title = id,
        type = LibraryItemType.LINK,
        scope = LibraryScope.PROJECT_SHARED,
        groupId = null,
        tags = emptySet(),
        note = null,
        favorite = false,
        target = target,
        contentFile = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun markdown(id: String) = LibraryItem(
        id = id,
        title = id,
        type = LibraryItemType.MARKDOWN,
        scope = LibraryScope.PROJECT_SHARED,
        groupId = null,
        tags = emptySet(),
        note = null,
        favorite = false,
        target = null,
        contentFile = "contents/$id.md",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun withTemporaryDirectory(block: suspend (Path) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("dev-library-repository-test")
        val cache = directory.resolveSibling("${directory.fileName}-last-valid")
        try {
            block(directory)
        } finally {
            sequenceOf(directory, cache).filter(Files::exists).forEach { root ->
                Files.walk(root).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }
}

package com.anmi.devworkspace.library.transfer

import com.anmi.devworkspace.config.AtomicFileWriter
import com.anmi.devworkspace.library.domain.LibraryGroup
import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.storage.LibraryDocument
import com.anmi.devworkspace.library.storage.LibraryJsonCodec
import com.anmi.devworkspace.library.storage.LibraryLoadResult
import com.anmi.devworkspace.library.storage.LibraryRepository
import com.anmi.devworkspace.library.storage.LibrarySnapshot
import com.anmi.devworkspace.library.storage.MarkdownContentStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Comparator
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryArchiveTest {
    private val codec = LibraryJsonCodec()
    private val clock = Clock.fixed(Instant.parse("2026-07-24T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `export contains manifest JSON and Markdown only without external bytes`() =
        withTemporaryDirectory { root ->
            val external = Files.createTempFile("dev-library-external-source", ".bin")
            try {
                Files.writeString(external, "EXTERNAL-FILE-BYTES-MUST-NOT-BE-EXPORTED")
                val document = LibraryDocument(
                    items = listOf(
                        markdown("notes"),
                        file("external", external.toString()),
                    ),
                )
                val repository = FakeRepository(LibraryScope.PROJECT_SHARED, root, document)
                val contentStore = MarkdownContentStore(root)
                contentStore.write("notes", "# Exported notes")
                val archive = root.resolve("library.zip")

                val result = LibraryExporter(repository, contentStore, clock = clock).export(archive)

                assertTrue(result.skippedMarkdownIds.isEmpty())
                ZipFile(archive.toFile()).use { zip ->
                    val names = zip.entries().asSequence().map(ZipEntry::getName).toSet()
                    assertEquals(
                        setOf("manifest.json", "library.json", "contents/notes.md"),
                        names,
                    )
                    assertEquals("# Exported notes", zip.getInputStream(zip.getEntry("contents/notes.md")).reader().readText())
                    val exportedText = names.joinToString("\n") { name ->
                        zip.getInputStream(zip.getEntry(name)).reader().readText()
                    }
                    assertFalse(exportedText.contains("EXTERNAL-FILE-BYTES-MUST-NOT-BE-EXPORTED"))
                }
            } finally {
                Files.deleteIfExists(external)
            }
        }

    @Test
    fun `import remaps ID conflict reuses same-name group and reports missing body`() =
        withTemporaryDirectory { root ->
            val targetRepository = FakeRepository(
                LibraryScope.PROJECT_PRIVATE,
                root.resolve("target"),
                LibraryDocument(
                    groups = listOf(group("existing-group", "Docs", LibraryScope.PROJECT_PRIVATE)),
                    items = listOf(file("same", "D:/existing.txt", LibraryScope.PROJECT_PRIVATE)),
                ),
            )
            val archive = root.resolve("import.zip")
            writeArchive(
                archive,
                LibraryScope.PROJECT_SHARED,
                LibraryDocument(
                    groups = listOf(group("import-group", " docs ", LibraryScope.PROJECT_SHARED)),
                    items = listOf(
                        markdown("same", LibraryScope.PROJECT_SHARED, "import-group"),
                        markdown("missing", LibraryScope.PROJECT_SHARED, "import-group"),
                        file("external", "D:/references/guide.pdf", LibraryScope.PROJECT_SHARED),
                    ),
                ),
                mapOf("same" to "# Imported"),
            )
            val generatedIds = ArrayDeque(listOf("generated-item"))
            val importer = LibraryImporter(
                repositories = mapOf(targetRepository.scope to targetRepository),
                idGenerator = { generatedIds.removeFirst() },
            )

            val result = importer.importArchive(archive, targetRepository.scope)

            assertEquals(listOf("missing"), result.skippedMarkdownIds)
            assertEquals(2, result.importedItemIds.size)
            val importedMarkdown = targetRepository.document.items.single { it.id == "generated-item" }
            assertEquals("existing-group", importedMarkdown.groupId)
            assertEquals("contents/generated-item.md", importedMarkdown.contentFile)
            assertEquals(LibraryScope.PROJECT_PRIVATE, importedMarkdown.scope)
            assertEquals(
                "D:/references/guide.pdf",
                targetRepository.document.items.single { it.id == "external" }.target,
            )
            assertEquals(
                "# Imported",
                Files.readString(targetRepository.root.resolve("contents/generated-item.md")),
            )
            assertEquals(1, targetRepository.document.groups.size)
        }

    @Test
    fun `version 1 archive imports through shared codec`() =
        withTemporaryDirectory { root ->
            val repository = FakeRepository(
                LibraryScope.PROJECT_PRIVATE,
                root.resolve("target"),
                LibraryDocument(),
            )
            val archive = root.resolve("legacy.zip")
            writeRawArchive(
                archive = archive,
                manifest = LibraryArchiveManifest(1, Instant.EPOCH, LibraryScope.GLOBAL),
                libraryJson = """
                    {
                      "version": 1,
                      "groups": [],
                      "items": [{
                        "id": "legacy",
                        "title": "Legacy",
                        "type": "MARKDOWN",
                        "groupId": null,
                        "tags": [],
                        "note": null,
                        "favorite": false,
                        "target": null,
                        "contentFile": "contents/legacy.md",
                        "createdAt": "2026-07-24T08:00:00Z",
                        "updatedAt": "2026-07-24T09:00:00Z"
                      }]
                    }
                """.trimIndent(),
                bodies = mapOf("legacy" to "legacy body"),
            )

            LibraryImporter(mapOf(repository.scope to repository))
                .importArchive(archive, repository.scope)

            val imported = repository.document.items.single()
            assertEquals("legacy", imported.id)
            assertEquals(null, imported.source)
            assertEquals(LibraryScope.PROJECT_PRIVATE, imported.scope)
            assertEquals("legacy body", Files.readString(repository.root.resolve("contents/legacy.md")))
        }

    @Test
    fun `path traversal entry is rejected before repository modification`() =
        withTemporaryDirectory { root ->
            val repository = FakeRepository(LibraryScope.GLOBAL, root.resolve("target"), LibraryDocument())
            val archive = root.resolve("malicious.zip")
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("../outside.txt"))
                zip.write("unsafe".toByteArray())
                zip.closeEntry()
            }

            assertFailsWith<IllegalArgumentException> {
                LibraryImporter(mapOf(repository.scope to repository))
                    .importArchive(archive, repository.scope)
            }

            assertEquals(0, repository.saveCalls)
            assertFalse(Files.exists(root.resolve("outside.txt")))
        }

    @Test
    fun `invalid archive is fully validated before repository modification`() =
        withTemporaryDirectory { root ->
            val original = LibraryDocument(items = listOf(file("retained", "D:/retained.txt")))
            val repository = FakeRepository(LibraryScope.GLOBAL, root.resolve("target"), original)
            val archive = root.resolve("invalid.zip")
            writeRawArchive(
                archive,
                manifest = LibraryArchiveManifest(1, Instant.EPOCH, LibraryScope.GLOBAL),
                libraryJson = """{"version":99,"groups":[],"items":[]}""",
            )

            assertFailsWith<IllegalArgumentException> {
                LibraryImporter(mapOf(repository.scope to repository))
                    .importArchive(archive, repository.scope)
            }

            assertEquals(original, repository.document)
            assertEquals(0, repository.saveCalls)
        }

    @Test
    fun `body write failure rolls back staged bodies and leaves repository unchanged`() =
        withTemporaryDirectory { root ->
            val targetRoot = root.resolve("target")
            val original = LibraryDocument(items = listOf(file("retained", "D:/retained.txt")))
            val repository = FakeRepository(LibraryScope.GLOBAL, targetRoot, original)
            val archive = root.resolve("two-bodies.zip")
            writeArchive(
                archive,
                LibraryScope.PROJECT_SHARED,
                LibraryDocument(items = listOf(markdown("first"), markdown("second"))),
                mapOf("first" to "first body", "second" to "second body"),
            )
            val failingWriter = AtomicFileWriter { path ->
                if (path.fileName.toString() == "second.md") throw IOException("simulated body failure")
            }
            val importer = LibraryImporter(
                repositories = mapOf(repository.scope to repository),
                atomicWriter = failingWriter,
            )

            assertFailsWith<IOException> {
                importer.importArchive(archive, repository.scope)
            }

            assertEquals(original, repository.document)
            assertEquals(0, repository.saveCalls)
            assertFalse(Files.exists(targetRoot.resolve("contents/first.md")))
            assertFalse(Files.exists(targetRoot.resolve("contents/second.md")))
        }

    @Test
    fun `repository failure rolls back already written Markdown bodies`() =
        withTemporaryDirectory { root ->
            val targetRoot = root.resolve("target")
            val original = LibraryDocument(items = listOf(file("retained", "D:/retained.txt")))
            val repository = FakeRepository(
                LibraryScope.GLOBAL,
                targetRoot,
                original,
                failSave = true,
            )
            val archive = root.resolve("repository-failure.zip")
            writeArchive(
                archive,
                LibraryScope.PROJECT_SHARED,
                LibraryDocument(items = listOf(markdown("notes"))),
                mapOf("notes" to "staged body"),
            )

            assertFailsWith<IOException> {
                LibraryImporter(mapOf(repository.scope to repository))
                    .importArchive(archive, repository.scope)
            }

            assertEquals(original, repository.document)
            assertEquals(1, repository.saveCalls)
            assertFalse(Files.exists(targetRoot.resolve("contents/notes.md")))
        }

    private suspend fun writeArchive(
        archive: Path,
        sourceScope: LibraryScope,
        document: LibraryDocument,
        bodies: Map<String, String>,
    ) {
        writeRawArchive(
            archive,
            LibraryArchiveManifest(1, Instant.EPOCH, sourceScope),
            codec.encode(document),
            bodies,
        )
    }

    private fun writeRawArchive(
        archive: Path,
        manifest: LibraryArchiveManifest,
        libraryJson: String,
        bodies: Map<String, String> = emptyMap(),
    ) {
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            mapOf(
                "manifest.json" to LibraryArchiveManifestCodec.encode(manifest),
                "library.json" to libraryJson,
            ).plus(bodies.mapKeys { (id, _) -> "contents/$id.md" }).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun group(
        id: String,
        name: String,
        scope: LibraryScope = LibraryScope.PROJECT_SHARED,
    ) = LibraryGroup(id, name, null, 0, scope)

    private fun markdown(
        id: String,
        scope: LibraryScope = LibraryScope.PROJECT_SHARED,
        groupId: String? = null,
    ) = item(id, LibraryItemType.MARKDOWN, scope, groupId, null)

    private fun file(
        id: String,
        target: String,
        scope: LibraryScope = LibraryScope.PROJECT_SHARED,
    ) = item(id, LibraryItemType.FILE, scope, null, target)

    private fun item(
        id: String,
        type: LibraryItemType,
        scope: LibraryScope,
        groupId: String?,
        target: String?,
    ) = LibraryItem(
        id = id,
        title = id,
        type = type,
        scope = scope,
        groupId = groupId,
        tags = emptySet(),
        note = null,
        favorite = false,
        target = target,
        contentFile = if (type == LibraryItemType.MARKDOWN) "contents/$id.md" else null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private class FakeRepository(
        override val scope: LibraryScope,
        override val root: Path,
        var document: LibraryDocument,
        private val failSave: Boolean = false,
    ) : LibraryRepository {
        override val path: Path = root.resolve("library.json")
        var saveCalls: Int = 0
            private set

        override suspend fun load(): LibraryLoadResult.Success =
            LibraryLoadResult.Success(snapshot(document))

        override suspend fun save(document: LibraryDocument): LibrarySnapshot {
            saveCalls++
            if (failSave) throw IOException("simulated repository failure")
            this.document = document
            return snapshot(document)
        }

        private fun snapshot(document: LibraryDocument) = LibrarySnapshot(
            scope,
            document,
            path,
            "hash",
            Instant.EPOCH,
        )
    }

    private fun withTemporaryDirectory(block: suspend (Path) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("dev-library-archive")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}

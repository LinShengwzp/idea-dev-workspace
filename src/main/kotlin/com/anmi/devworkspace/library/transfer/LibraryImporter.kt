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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LibraryImportResult(
    val importedItemIds: List<String>,
    val reusedGroupIds: List<String>,
    val skippedMarkdownIds: List<String>,
)

/**
 * Imports a fully validated archive into a selected repository scope.
 *
 * The ZIP is read into bounded memory and validated before staging or touching
 * repository files. Markdown bodies are staged first, then atomically written
 * with backups; the repository index commits last. Any pre-commit failure
 * rolls back body writes, so the old index never references partial content.
 */
class LibraryImporter(
    private val repositories: Map<LibraryScope, LibraryRepository>,
    private val codec: LibraryJsonCodec = LibraryJsonCodec(),
    private val atomicWriter: AtomicFileWriter = AtomicFileWriter(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun importArchive(
        archive: Path,
        targetScope: LibraryScope,
    ): LibraryImportResult = withContext(ioDispatcher) {
        val repository = repositories[targetScope]
            ?: throw IllegalArgumentException("No repository exists for target scope ${targetScope.name}")
        val archiveData = readAndValidateArchive(archive, targetScope)
        val current = when (val loaded = repository.load()) {
            is LibraryLoadResult.Success -> loaded.snapshot.document
            is LibraryLoadResult.Recovered -> loaded.snapshot.document
            is LibraryLoadResult.Failure -> throw IllegalStateException("Target library scope cannot be loaded")
        }
        val merge = merge(current, archiveData, targetScope)
        val staged = stage(repository, merge.document, merge.bodies)
        var importFailure: Throwable? = null
        try {
            commit(repository, merge.document, staged)
        } catch (failure: Throwable) {
            importFailure = failure
            throw failure
        } finally {
            try {
                deleteStaging(staged.root)
            } catch (cleanupFailure: Throwable) {
                // Cleanup happens after the transaction boundary and must not turn a committed import into failure.
                importFailure?.addSuppressed(cleanupFailure)
            }
        }
        LibraryImportResult(
            importedItemIds = merge.importedItemIds,
            reusedGroupIds = merge.reusedGroupIds,
            skippedMarkdownIds = merge.skippedMarkdownIds,
        )
    }

    private fun readAndValidateArchive(archive: Path, targetScope: LibraryScope): ArchiveData {
        val entries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(Files.newInputStream(archive)).use { zip ->
            var entry = zip.nextEntry
            var count = 0
            while (entry != null) {
                count++
                require(count <= MAX_ENTRIES) { "Library archive contains too many entries" }
                val name = validateEntryName(entry.name, entry.isDirectory)
                if (name != null) {
                    require(name !in entries) { "Library archive contains duplicate entry: $name" }
                    val bytes = readBounded(zip) { read ->
                        totalBytes += read
                        require(totalBytes <= MAX_TOTAL_BYTES) { "Library archive is too large" }
                    }
                    entries[name] = bytes
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val manifestText = entries.remove("manifest.json")?.toString(StandardCharsets.UTF_8)
            ?: throw IllegalArgumentException("Library archive is missing manifest.json")
        val libraryText = entries.remove("library.json")?.toString(StandardCharsets.UTF_8)
            ?: throw IllegalArgumentException("Library archive is missing library.json")
        val manifest = LibraryArchiveManifestCodec.decode(manifestText)
        val document = codec.decode(libraryText, targetScope)
        val bodies = entries.mapKeys { (name, _) ->
            name.removePrefix("contents/").removeSuffix(".md")
        }.mapValues { (_, bytes) -> bytes.toString(StandardCharsets.UTF_8) }
        val markdownIds = document.items
            .filter { it.type == LibraryItemType.MARKDOWN }
            .mapTo(hashSetOf(), LibraryItem::id)
        require(bodies.keys.all(markdownIds::contains)) {
            "Library archive contains an unreferenced Markdown body"
        }
        return ArchiveData(manifest, document, bodies)
    }

    private fun validateEntryName(rawName: String, directory: Boolean): String? {
        require('\\' !in rawName) { "Library archive entry uses an unsafe separator" }
        require(!rawName.startsWith("/") && !DRIVE_PREFIX.containsMatchIn(rawName)) {
            "Library archive entry must be relative"
        }
        val segments = rawName.split('/')
        require(segments.none { it == ".." || it == "." || it.isEmpty() && it != segments.last() }) {
            "Library archive entry contains path traversal"
        }
        if (directory) {
            require(rawName == "contents/") { "Library archive contains an unexpected directory" }
            return null
        }
        require(
            rawName == "manifest.json" ||
                rawName == "library.json" ||
                CONTENT_ENTRY.matches(rawName),
        ) { "Library archive contains unexpected entry: $rawName" }
        return rawName
    }

    private fun readBounded(zip: ZipInputStream, onRead: (Long) -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryBytes = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            entryBytes += read
            require(entryBytes <= MAX_ENTRY_BYTES) { "Library archive entry is too large" }
            onRead(read.toLong())
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun merge(
        current: LibraryDocument,
        archive: ArchiveData,
        targetScope: LibraryScope,
    ): MergePlan {
        val groups = current.groups.toMutableList()
        val usedGroupIds = groups.mapTo(hashSetOf(), LibraryGroup::id)
        val groupsByName = groups.associateByTo(linkedMapOf()) { normalizeName(it.name) }
        val groupMapping = mutableMapOf<String, String>()
        val reusedGroups = mutableListOf<String>()

        archive.document.groups.forEach { imported ->
            val sameName = groupsByName[normalizeName(imported.name)]
            if (sameName != null) {
                groupMapping[imported.id] = sameName.id
                reusedGroups += sameName.id
            } else {
                val id = uniqueId(imported.id, usedGroupIds)
                val created = imported.copy(id = id, scope = targetScope)
                groups += created
                groupsByName[normalizeName(created.name)] = created
                groupMapping[imported.id] = id
            }
        }

        val items = current.items.toMutableList()
        val usedItemIds = items.mapTo(hashSetOf(), LibraryItem::id)
        val bodies = linkedMapOf<String, String>()
        val importedIds = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        archive.document.items.forEach { imported ->
            val body = if (imported.type == LibraryItemType.MARKDOWN) {
                archive.bodies[imported.id]
            } else {
                null
            }
            if (imported.type == LibraryItemType.MARKDOWN && body == null) {
                skipped += imported.id
                return@forEach
            }
            val id = uniqueId(imported.id, usedItemIds)
            val created = imported.copy(
                id = id,
                scope = targetScope,
                groupId = imported.groupId?.let(groupMapping::get),
                contentFile = if (imported.type == LibraryItemType.MARKDOWN) "contents/$id.md" else null,
            )
            items += created
            importedIds += id
            if (body != null) bodies[id] = body
        }

        val merged = LibraryDocument(groups = groups, items = items)
        codec.encode(merged)
        return MergePlan(merged, bodies, importedIds, reusedGroups.distinct(), skipped)
    }

    private fun uniqueId(preferred: String, used: MutableSet<String>): String {
        if (preferred !in used) {
            used += preferred
            return preferred
        }
        repeat(MAX_ID_ATTEMPTS) {
            val generated = idGenerator()
            require(ID_PATTERN.matches(generated)) { "Generated library ID is invalid" }
            if (used.add(generated)) return generated
        }
        throw IllegalStateException("Unable to generate a unique library ID")
    }

    private fun stage(
        repository: LibraryRepository,
        document: LibraryDocument,
        bodies: Map<String, String>,
    ): StagingArea {
        val parent = repository.root.toAbsolutePath().normalize().parent
            ?: throw IllegalArgumentException("Library repository root requires a parent")
        Files.createDirectories(parent)
        val stagingRoot = Files.createTempDirectory(parent, ".library-import-")
        try {
            val index = stagingRoot.resolve("library.json")
            val json = codec.encode(document)
            Files.writeString(index, json, StandardCharsets.UTF_8)
            codec.decode(Files.readString(index, StandardCharsets.UTF_8), repository.scope)
            val stagedBodies = bodies.mapValues { (id, content) ->
                require(ID_PATTERN.matches(id)) { "Imported Markdown ID is invalid" }
                val path = stagingRoot.resolve("contents/$id.md")
                Files.createDirectories(path.parent)
                Files.writeString(path, content, StandardCharsets.UTF_8)
                path
            }
            return StagingArea(stagingRoot, stagedBodies)
        } catch (exception: Throwable) {
            try {
                deleteStaging(stagingRoot)
            } catch (cleanupFailure: Throwable) {
                exception.addSuppressed(cleanupFailure)
            }
            throw exception
        }
    }

    private suspend fun commit(
        repository: LibraryRepository,
        document: LibraryDocument,
        staging: StagingArea,
    ) {
        val backups = linkedMapOf<Path, String?>()
        try {
            staging.bodies.toSortedMap().forEach { (id, stagedPath) ->
                val destination = repository.root.resolve("contents/$id.md").toAbsolutePath().normalize()
                backups[destination] = if (Files.exists(destination)) {
                    Files.readString(destination, StandardCharsets.UTF_8)
                } else {
                    null
                }
                atomicWriter.write(
                    destination,
                    Files.readString(stagedPath, StandardCharsets.UTF_8),
                ) { }
            }
            repository.save(document)
        } catch (cancellation: CancellationException) {
            rollback(backups, cancellation)
            throw cancellation
        } catch (exception: Throwable) {
            rollback(backups, exception)
            throw exception
        }
    }

    private fun rollback(backups: Map<Path, String?>, original: Throwable) {
        backups.entries.toList().asReversed().forEach { (path, content) ->
            try {
                if (content == null) {
                    Files.deleteIfExists(path)
                } else {
                    atomicWriter.write(path, content) { }
                }
            } catch (rollbackFailure: Throwable) {
                original.addSuppressed(rollbackFailure)
            }
        }
    }

    private fun deleteStaging(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun normalizeName(value: String): String = value.trim().lowercase(Locale.ROOT)

    private data class ArchiveData(
        val manifest: LibraryArchiveManifest,
        val document: LibraryDocument,
        val bodies: Map<String, String>,
    )

    private data class MergePlan(
        val document: LibraryDocument,
        val bodies: Map<String, String>,
        val importedItemIds: List<String>,
        val reusedGroupIds: List<String>,
        val skippedMarkdownIds: List<String>,
    )

    private data class StagingArea(
        val root: Path,
        val bodies: Map<String, Path>,
    )

    private companion object {
        const val MAX_ENTRIES = 10_000
        const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val MAX_ID_ATTEMPTS = 100
        val CONTENT_ENTRY = Regex("^contents/[A-Za-z0-9_-]+\\.md$")
        val DRIVE_PREFIX = Regex("^[A-Za-z]:")
        val ID_PATTERN = Regex("[A-Za-z0-9_-]+")
    }
}

package com.anmi.devworkspace.library.transfer

import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.storage.LibraryJsonCodec
import com.anmi.devworkspace.library.storage.LibraryLoadResult
import com.anmi.devworkspace.library.storage.LibraryRepository
import com.anmi.devworkspace.library.storage.MarkdownContentStore
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LibraryExportResult(
    val exportedItemIds: List<String>,
    val skippedMarkdownIds: List<String>,
)

/**
 * Exports metadata and plugin-owned Markdown bodies only.
 *
 * External paths remain JSON strings; their target files are never opened,
 * copied, or added to the ZIP.
 */
class LibraryExporter(
    private val repository: LibraryRepository,
    private val contentStore: MarkdownContentStore,
    private val codec: LibraryJsonCodec = LibraryJsonCodec(),
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun export(output: Path): LibraryExportResult = withContext(ioDispatcher) {
        val snapshot = when (val loaded = repository.load()) {
            is LibraryLoadResult.Success -> loaded.snapshot
            is LibraryLoadResult.Recovered -> loaded.snapshot
            is LibraryLoadResult.Failure -> throw IllegalStateException("Library scope cannot be exported")
        }
        val bodies = linkedMapOf<String, String>()
        val skipped = mutableListOf<String>()
        val exportedItems = snapshot.document.items.mapNotNull { item ->
            if (item.type != LibraryItemType.MARKDOWN) return@mapNotNull item
            val body = contentStore.read(item.id)
            if (body == null) {
                skipped += item.id
                null
            } else {
                bodies[item.id] = body
                item
            }
        }
        val exportedDocument = snapshot.document.copy(items = exportedItems)
        val manifest = LibraryArchiveManifest(
            formatVersion = LibraryArchiveManifest.CURRENT_VERSION,
            exportedAt = Instant.now(clock),
            sourceScope = repository.scope,
        )

        val absoluteOutput = output.toAbsolutePath().normalize()
        Files.createDirectories(absoluteOutput.parent)
        val temporary = Files.createTempFile(absoluteOutput.parent, ".library-export-", ".zip")
        try {
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                writeEntry(zip, "manifest.json", LibraryArchiveManifestCodec.encode(manifest), manifest.exportedAt)
                writeEntry(zip, "library.json", codec.encode(exportedDocument), manifest.exportedAt)
                bodies.toSortedMap().forEach { (id, body) ->
                    writeEntry(zip, "contents/$id.md", body, manifest.exportedAt)
                }
            }
            replaceAtomically(temporary, absoluteOutput)
        } finally {
            Files.deleteIfExists(temporary)
        }
        LibraryExportResult(exportedItems.map { it.id }, skipped)
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String, timestamp: Instant) {
        zip.putNextEntry(ZipEntry(name).apply { time = timestamp.toEpochMilli() })
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    private fun replaceAtomically(temporary: Path, output: Path) {
        try {
            Files.move(
                temporary,
                output,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

package com.anmi.devworkspace.library.storage

import com.anmi.devworkspace.config.AtomicFileWriter
import com.anmi.devworkspace.library.domain.LibraryScope
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * File-backed repository for one scope.
 *
 * A generated index is validated before its atomic replacement. Only after
 * that commit succeeds is the recovery cache refreshed, so a failed primary
 * write leaves both the old index and its last-valid recovery point intact.
 */
class FileLibraryRepository(
    override val scope: LibraryScope,
    override val root: Path,
    private val codec: LibraryJsonCodec = LibraryJsonCodec(),
    private val atomicWriter: AtomicFileWriter = AtomicFileWriter(),
    private val lastValidStore: LibrarySnapshotCache? = null,
    private val contentStore: MarkdownContentStore = MarkdownContentStore(root),
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LibraryRepository {
    override val path: Path = root.resolve("library.json")

    override suspend fun load(): LibraryLoadResult = withContext(ioDispatcher) {
        if (!Files.exists(path)) {
            return@withContext LibraryLoadResult.Success(snapshot(EMPTY_DOCUMENT_TEXT))
        }

        try {
            val content = Files.readString(path, StandardCharsets.UTF_8)
            val snapshot = snapshot(content)
            saveRecoveryBestEffort(snapshot)
            LibraryLoadResult.Success(snapshot)
        } catch (_: IOException) {
            recoverOrFail("无法读取资料库索引")
        } catch (_: IllegalArgumentException) {
            recoverOrFail("资料库索引格式无效")
        }
    }

    override suspend fun save(document: LibraryDocument): LibrarySnapshot = withContext(ioDispatcher) {
        require(document.groups.all { it.scope == scope }) {
            "Library groups must belong to repository scope ${scope.name}"
        }
        require(document.items.all { it.scope == scope }) {
            "Library items must belong to repository scope ${scope.name}"
        }

        val previous = currentDocumentOrNull()
        val content = codec.encode(document)
        atomicWriter.write(path, content) { candidate ->
            codec.decode(candidate, scope)
        }
        val committed = snapshot(content)
        cleanupRemovedMarkdown(previous, committed.document)
        // Recovery is best effort after the primary transaction has committed.
        saveRecoveryBestEffort(committed)
        committed
    }

    private fun snapshot(content: String): LibrarySnapshot = LibrarySnapshot(
        scope = scope,
        document = codec.decode(content, scope),
        sourceFile = path,
        contentHash = contentHash(content),
        loadedAt = Instant.now(clock),
    )

    private fun recoverOrFail(message: String): LibraryLoadResult {
        val error = LibraryLoadError(path, message)
        val recovered = lastValidStore?.load(path, scope)
        return if (recovered == null) {
            LibraryLoadResult.Failure(error)
        } else {
            LibraryLoadResult.Recovered(recovered, error)
        }
    }

    private fun saveRecoveryBestEffort(snapshot: LibrarySnapshot) {
        try {
            lastValidStore?.save(snapshot)
        } catch (_: IOException) {
            // The committed repository remains valid even if its recovery cache cannot be refreshed.
        } catch (_: IllegalArgumentException) {
            // A cache validation failure must not invalidate an already committed primary index.
        }
    }

    private fun currentDocumentOrNull(): LibraryDocument? =
        try {
            if (Files.exists(path)) {
                codec.decode(Files.readString(path, StandardCharsets.UTF_8), scope)
            } else {
                null
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /**
     * The index is committed before removing bodies. This order prevents a
     * failed JSON write from orphaning content still referenced by the old
     * index. Cleanup is limited to plugin-owned Markdown files; external
     * targets are never passed to filesystem deletion.
     */
    private suspend fun cleanupRemovedMarkdown(
        previous: LibraryDocument?,
        committed: LibraryDocument,
    ) {
        if (previous == null) return
        val retainedMarkdownIds = committed.items
            .filter { it.type == com.anmi.devworkspace.library.domain.LibraryItemType.MARKDOWN }
            .mapTo(hashSetOf()) { it.id }
        previous.items
            .filter { it.type == com.anmi.devworkspace.library.domain.LibraryItemType.MARKDOWN }
            .filterNot { it.id in retainedMarkdownIds }
            .forEach { removed ->
                try {
                    contentStore.delete(removed)
                } catch (_: IOException) {
                    // An orphaned internal body is safer than reporting a committed index as failed.
                }
            }
    }

    private fun contentHash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val EMPTY_DOCUMENT_TEXT = LibraryJsonCodec().encode(LibraryDocument())
    }
}

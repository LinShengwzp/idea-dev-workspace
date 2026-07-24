package com.anmi.devworkspace.library.storage

import com.anmi.devworkspace.config.AtomicFileWriter
import com.anmi.devworkspace.library.domain.LibraryScope
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Recovery-cache boundary kept separate so each repository restores only its own scope. */
interface LibrarySnapshotCache {
    fun save(snapshot: LibrarySnapshot)
    fun load(source: Path, scope: LibraryScope): LibrarySnapshot?
}

/**
 * Keeps an independent validated recovery copy for each repository index.
 *
 * Cache writes occur only after the primary index is committed. A cache is
 * never treated as authoritative and corrupt cache data is ignored.
 */
class LibraryLastValidStore(
    private val codec: LibraryJsonCodec,
    private val writer: AtomicFileWriter,
    private val cacheRoot: Path,
) : LibrarySnapshotCache {
    override fun save(snapshot: LibrarySnapshot) {
        val content = codec.encode(snapshot.document)
        writer.write(cachePath(snapshot.sourceFile), content) { candidate ->
            val decoded = codec.decode(candidate, snapshot.scope)
            require(decoded == snapshot.document)
        }
    }

    override fun load(source: Path, scope: LibraryScope): LibrarySnapshot? {
        val cache = cachePath(source)
        if (!Files.exists(cache)) return null
        return try {
            val content = Files.readString(cache, StandardCharsets.UTF_8)
            LibrarySnapshot(
                scope = scope,
                document = codec.decode(content, scope),
                sourceFile = source,
                contentHash = contentHash(content),
                loadedAt = Files.getLastModifiedTime(cache).toInstant(),
            )
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun cachePath(source: Path): Path =
        cacheRoot.resolve("${sha256(normalize(source))}.json")

    private fun normalize(path: Path): String = path.toAbsolutePath().normalize().toString()

    private fun sha256(value: String): String = contentHash(value)

    private fun contentHash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.TaskScope
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal interface TaskSnapshotCache {
    fun save(snapshot: TaskConfigSnapshot, content: String)
    fun load(source: Path, scope: TaskScope): TaskConfigSnapshot?
}

class LastValidSnapshotStore(
    private val parser: TaskTomlParser,
    private val writer: AtomicFileWriter,
    private val cacheRoot: Path,
) : TaskSnapshotCache {
    override fun save(snapshot: TaskConfigSnapshot, content: String) {
        writer.write(cachePath(snapshot.sourceFile), content) { candidate ->
            val result = parser.parse(candidate, snapshot.scope, snapshot.sourceFile)
            val parsedSnapshot = (result as? TaskConfigLoadResult.Success)?.snapshot
            require(parsedSnapshot?.contentHash == snapshot.contentHash) {
                "Cached task configuration does not match the validated snapshot"
            }
        }
    }

    override fun load(source: Path, scope: TaskScope): TaskConfigSnapshot? {
        val path = cachePath(source)
        if (!Files.exists(path)) return null
        return try {
            val content = Files.readString(path, StandardCharsets.UTF_8)
            (parser.parse(content, scope, source) as? TaskConfigLoadResult.Success)?.snapshot
        } catch (_: IOException) {
            null
        }
    }

    private fun cachePath(source: Path): Path = cacheRoot.resolve("${sha256(normalize(source))}.toml")

    private fun normalize(path: Path): String = path.toAbsolutePath().normalize().toString()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

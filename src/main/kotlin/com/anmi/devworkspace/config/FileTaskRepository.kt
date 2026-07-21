package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.TaskScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class FileTaskRepository(
    override val scope: TaskScope,
    override val path: Path,
    private val parser: TaskTomlParser = TaskTomlParser(),
    private val tomlWriter: TaskTomlWriter = TaskTomlWriter(),
    private val atomicWriter: AtomicFileWriter = AtomicFileWriter(),
) : TaskRepository {
    override suspend fun load(): TaskConfigLoadResult = withContext(Dispatchers.IO) {
        if (!Files.exists(path)) {
            return@withContext parser.parse(EMPTY_CONFIGURATION, scope, path)
        }

        try {
            parser.parse(Files.readString(path, StandardCharsets.UTF_8), scope, path)
        } catch (_: IOException) {
            TaskConfigLoadResult.Failure(
                listOf(TaskConfigError(path, 1, 1, "Unable to read task configuration")),
            )
        }
    }

    override suspend fun save(tasks: List<DevTask>, expectedHash: String?): TaskConfigSnapshot = withContext(Dispatchers.IO) {
        val currentHash = currentContentHash()
        if (expectedHash != null && currentHash != expectedHash) {
            throw TaskConfigConflictException(scope, path, expectedHash, currentHash)
        }
        val content = tomlWriter.write(tasks)
        atomicWriter.write(
            path = path,
            content = content,
            verifyBeforeReplace = {
                if (expectedHash != null) verifyExpectedHash(expectedHash)
            },
        ) { candidate ->
            if (parser.parse(candidate, scope, path) !is TaskConfigLoadResult.Success) {
                throw IllegalArgumentException("Generated task configuration failed validation")
            }
        }
        val result = parser.parse(content, scope, path)
        (result as TaskConfigLoadResult.Success).snapshot
    }

    private fun currentContentHash(): String {
        val content = if (Files.exists(path)) {
            Files.readString(path, StandardCharsets.UTF_8)
        } else {
            EMPTY_CONFIGURATION
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun verifyExpectedHash(expectedHash: String) {
        val actualHash = currentContentHash()
        if (actualHash != expectedHash) {
            throw TaskConfigConflictException(scope, path, expectedHash, actualHash)
        }
    }

    private companion object {
        const val EMPTY_CONFIGURATION = "version = 1"
    }
}

package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.TaskScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

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

    override suspend fun save(tasks: List<DevTask>) = withContext(Dispatchers.IO) {
        val content = tomlWriter.write(tasks)
        atomicWriter.write(path, content) { candidate ->
            if (parser.parse(candidate, scope, path) !is TaskConfigLoadResult.Success) {
                throw IllegalArgumentException("Generated task configuration failed validation")
            }
        }
    }

    private companion object {
        const val EMPTY_CONFIGURATION = "version = 1"
    }
}

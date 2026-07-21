package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.TaskScope
import java.nio.file.Path

interface TaskRepository {
    val scope: TaskScope
    val path: Path

    suspend fun load(): TaskConfigLoadResult

    suspend fun save(tasks: List<DevTask>, expectedHash: String? = null): TaskConfigSnapshot
}

class TaskConfigConflictException(
    val scope: TaskScope,
    val sourceFile: Path,
    val expectedHash: String?,
    val actualHash: String?,
) : Exception("Task configuration changed on disk for scope ${scope.name} at ${sourceFile.toAbsolutePath().normalize()}")

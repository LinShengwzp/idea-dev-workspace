package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.TaskScope
import java.nio.file.Path
import java.time.Instant

data class TaskConfigSnapshot(
    val scope: TaskScope,
    val tasks: List<DevTask>,
    val sourceFile: Path,
    val contentHash: String,
    val loadedAt: Instant,
)

data class TaskConfigError(
    val sourceFile: Path,
    val line: Int,
    val column: Int,
    val message: String,
)

sealed interface TaskConfigLoadResult {
    data class Success(val snapshot: TaskConfigSnapshot) : TaskConfigLoadResult
    data class Failure(val errors: List<TaskConfigError>) : TaskConfigLoadResult
}

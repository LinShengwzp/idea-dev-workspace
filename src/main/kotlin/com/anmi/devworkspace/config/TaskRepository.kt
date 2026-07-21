package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.TaskScope
import java.nio.file.Path

interface TaskRepository {
    val scope: TaskScope
    val path: Path

    suspend fun load(): TaskConfigLoadResult

    suspend fun save(tasks: List<DevTask>)
}

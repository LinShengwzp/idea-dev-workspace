package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.TaskScope
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

class TaskRepositoryPaths(
    projectBase: Path,
    configDir: Path,
    systemDir: Path,
) {
    val projectShared: Path = projectBase.resolve(".dev-workspace/tasks.toml")
    val projectPrivate: Path = systemDir
        .resolve("dev-workspace/projects")
        .resolve(sha256(normalize(projectBase)))
        .resolve("tasks.toml")
    val global: Path = configDir.resolve("dev-workspace/tasks.toml")
    val lastValidCacheRoot: Path = systemDir.resolve("dev-workspace/last-valid")

    fun path(scope: TaskScope): Path = when (scope) {
        TaskScope.GLOBAL -> global
        TaskScope.PROJECT_SHARED -> projectShared
        TaskScope.PROJECT_PRIVATE -> projectPrivate
    }

    private fun normalize(path: Path): String = path.toAbsolutePath().normalize().toString()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

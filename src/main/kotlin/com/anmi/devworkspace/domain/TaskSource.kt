package com.anmi.devworkspace.domain

sealed interface TaskSource {
    data class ScriptFile(
        val path: String,
        val interpreter: String? = null,
        val arguments: List<String> = emptyList(),
    ) : TaskSource

    data class InlineCommand(
        val command: String,
        val shell: String? = null,
    ) : TaskSource
}

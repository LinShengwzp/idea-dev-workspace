package com.anmi.devworkspace.domain

enum class FailureCategory {
    CONFIGURATION,
    PREPARATION,
    SECURITY,
    TERMINAL,
    EXECUTION,
    STATUS_LOST,
}

data class TaskFailure(
    val category: FailureCategory,
    val userMessage: String,
    val technicalMessage: String? = null,
)

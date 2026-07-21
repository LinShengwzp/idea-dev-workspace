package com.anmi.devworkspace.domain

import java.time.Instant

data class TaskExecution(
    val taskId: String,
    val executionId: String,
    val trigger: RunTrigger,
    val status: TaskStatus,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val exitCode: Int? = null,
    val failure: TaskFailure? = null,
    val sourceScope: TaskScope,
)

data class TaskRunRecord(
    val taskId: String,
    val executionId: String,
    val startTime: Instant,
    val endTime: Instant?,
    val status: TaskStatus,
    val exitCode: Int?,
    val failureCategory: FailureCategory?,
)

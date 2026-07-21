package com.anmi.devworkspace.domain

enum class TaskScope { GLOBAL, PROJECT_SHARED, PROJECT_PRIVATE }
enum class TerminalPolicy { REUSE_TASK_TERMINAL, ALWAYS_NEW, REUSE_SHARED }
enum class AutoStartTrigger { IDE_START, PROJECT_OPEN }
enum class GlobalAutoStartMode { ONCE_PER_IDE_SESSION, ONCE_PER_PROJECT }
enum class RunTrigger { MANUAL, AUTO_IDE_START, AUTO_PROJECT_OPEN, FAVORITE_SLOT }

enum class TaskStatus {
    IDLE,
    PREPARING,
    WAITING_FOR_TERMINAL,
    RUNNING,
    SUCCEEDED,
    FAILED,
    STOPPING,
    STOPPED,
    UNKNOWN,
    INTERRUPTED;

    fun canTransitionTo(next: TaskStatus): Boolean = next in when (this) {
        IDLE -> setOf(PREPARING)
        PREPARING -> setOf(WAITING_FOR_TERMINAL, FAILED, STOPPED)
        WAITING_FOR_TERMINAL -> setOf(RUNNING, FAILED, STOPPING, STOPPED)
        RUNNING -> setOf(SUCCEEDED, FAILED, STOPPING, UNKNOWN)
        STOPPING -> setOf(STOPPED, FAILED, UNKNOWN)
        SUCCEEDED, FAILED, STOPPED, UNKNOWN, INTERRUPTED -> emptySet()
    }
}

package com.anmi.devworkspace.domain

data class AutoStartConfig(
    val trigger: AutoStartTrigger,
    val order: Int = 100,
    val delaySeconds: Int = 0,
    val globalMode: GlobalAutoStartMode = GlobalAutoStartMode.ONCE_PER_IDE_SESSION,
)

data class DevTask(
    val id: String,
    val name: String? = null,
    val terminalAlias: String? = null,
    val description: String? = null,
    val scope: TaskScope,
    val enabled: Boolean = true,
    val source: TaskSource,
    val workingDirectory: String? = null,
    val environment: Map<String, EnvironmentValue> = emptyMap(),
    val terminalPolicy: TerminalPolicy = TerminalPolicy.REUSE_TASK_TERMINAL,
    val exitDetection: Boolean = true,
    val autoStart: AutoStartConfig? = null,
    val favoriteSlot: Int? = null,
)

data class ResolvedTask(
    val effective: DevTask,
    val shadowed: List<DevTask> = emptyList(),
)

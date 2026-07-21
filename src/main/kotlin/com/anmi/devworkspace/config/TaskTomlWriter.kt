package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.AutoStartTrigger
import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.EnvironmentValue
import com.anmi.devworkspace.domain.GlobalAutoStartMode
import com.anmi.devworkspace.domain.TaskSource
import com.anmi.devworkspace.domain.TerminalPolicy

class TaskTomlWriter {
    fun write(tasks: List<DevTask>): String = buildString {
        appendLine("version = 1")

        tasks.sortedBy { it.id }.forEach { task ->
            appendLine()
            appendLine("[[tasks]]")
            appendField("id", task.id)
            task.name?.let { appendField("name", it) }
            task.terminalAlias?.let { appendField("terminalAlias", it) }
            task.description?.let { appendField("description", it) }
            if (!task.enabled) appendLine("enabled = false")

            when (val source = task.source) {
                is TaskSource.ScriptFile -> {
                    appendLine("sourceType = \"script\"")
                    appendField("script", source.path)
                    source.interpreter?.let { appendField("interpreter", it) }
                    if (source.arguments.isNotEmpty()) {
                        appendLine("arguments = ${source.arguments.joinToString(prefix = "[", postfix = "]") { quote(it) }}")
                    }
                }

                is TaskSource.InlineCommand -> {
                    appendLine("sourceType = \"inline\"")
                    appendField("command", source.command)
                    source.shell?.let { appendField("shell", it) }
                }
            }

            task.workingDirectory?.let { appendField("workingDirectory", it) }
            if (task.terminalPolicy != TerminalPolicy.REUSE_TASK_TERMINAL) {
                appendField("terminalPolicy", task.terminalPolicy.tomlValue())
            }
            if (!task.exitDetection) appendLine("exitDetection = false")
            task.favoriteSlot?.let { appendLine("favoriteSlot = $it") }

            task.autoStart?.let { autoStart ->
                appendLine()
                appendLine("[tasks.autoStart]")
                appendField("trigger", autoStart.trigger.tomlValue())
                if (autoStart.order != 100) appendLine("order = ${autoStart.order}")
                if (autoStart.delaySeconds != 0) appendLine("delaySeconds = ${autoStart.delaySeconds}")
                if (autoStart.globalMode != GlobalAutoStartMode.ONCE_PER_IDE_SESSION) {
                    appendField("globalMode", autoStart.globalMode.tomlValue())
                }
            }

            if (task.environment.isNotEmpty()) {
                appendLine()
                appendLine("[tasks.environment]")
                task.environment.toSortedMap().forEach { (key, value) ->
                    appendLine("${quote(key)} = ${quote(value.tomlValue())}")
                }
            }
        }
    }

    private fun StringBuilder.appendField(key: String, value: String) {
        appendLine("$key = ${quote(value)}")
    }

    private fun EnvironmentValue.tomlValue(): String = when (this) {
        is EnvironmentValue.Plain -> value
        is EnvironmentValue.SystemReference -> "\${ENV:$name}"
        is EnvironmentValue.SecretReference -> "\${SECRET:$key}"
    }

    private fun TerminalPolicy.tomlValue(): String = when (this) {
        TerminalPolicy.REUSE_TASK_TERMINAL -> "reuse-task"
        TerminalPolicy.ALWAYS_NEW -> "always-new"
        TerminalPolicy.REUSE_SHARED -> "reuse-shared"
    }

    private fun AutoStartTrigger.tomlValue(): String = when (this) {
        AutoStartTrigger.IDE_START -> "ide-start"
        AutoStartTrigger.PROJECT_OPEN -> "project-open"
    }

    private fun GlobalAutoStartMode.tomlValue(): String = when (this) {
        GlobalAutoStartMode.ONCE_PER_IDE_SESSION -> "once-per-ide-session"
        GlobalAutoStartMode.ONCE_PER_PROJECT -> "once-per-project"
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            append(
                when (ch) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> ch
                },
            )
        }
        append('"')
    }
}

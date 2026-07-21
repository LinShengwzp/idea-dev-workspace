package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.AutoStartConfig
import com.anmi.devworkspace.domain.AutoStartTrigger
import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.EnvironmentValue
import com.anmi.devworkspace.domain.GlobalAutoStartMode
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskSource
import com.anmi.devworkspace.domain.TerminalPolicy
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlInvalidTypeException
import org.tomlj.TomlPosition
import org.tomlj.TomlTable
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

class TaskTomlParser {
    fun parse(
        content: String,
        scope: TaskScope,
        source: Path,
    ): TaskConfigLoadResult {
        val parsed = Toml.parse(content)
        if (parsed.hasErrors()) {
            return TaskConfigLoadResult.Failure(
                parsed.errors().map { error ->
                    TaskConfigError(
                        sourceFile = source,
                        line = error.position().line(),
                        column = error.position().column(),
                        message = "Invalid TOML syntax",
                    )
                },
            )
        }

        return try {
            requireVersion(parsed)
            val tasks = parseTasks(parsed, scope)
            val validationErrors = TaskConfigValidator.validate(tasks)
            if (validationErrors.isNotEmpty()) {
                TaskConfigLoadResult.Failure(
                    validationErrors.map { TaskConfigError(source, 1, 1, it) },
                )
            } else {
                TaskConfigLoadResult.Success(
                    TaskConfigSnapshot(
                        scope = scope,
                        tasks = tasks,
                        sourceFile = source,
                        contentHash = sha256(content),
                        loadedAt = Instant.now(),
                    ),
                )
            }
        } catch (error: StructureException) {
            TaskConfigLoadResult.Failure(
                listOf(TaskConfigError(source, error.line, error.column, error.safeMessage)),
            )
        } catch (_: TomlInvalidTypeException) {
            TaskConfigLoadResult.Failure(
                listOf(TaskConfigError(source, 1, 1, "Invalid task configuration field type")),
            )
        } catch (_: RuntimeException) {
            TaskConfigLoadResult.Failure(
                listOf(TaskConfigError(source, 1, 1, "Invalid task configuration structure")),
            )
        }
    }

    private fun requireVersion(root: TomlTable) {
        val version = readField(root, "version", "Configuration", "an integer") {
            root.getLong("version")
        } ?: throw structure("Configuration is missing required integer 'version'", root.inputPositionOf("version"))
        if (version != 1L) throw structure("Configuration version must be 1", root.inputPositionOf("version"))
    }

    private fun parseTasks(root: TomlTable, scope: TaskScope): List<DevTask> {
        val tasks = readField(root, "tasks", "Configuration", "an array of tables") {
            root.getArrayOrEmpty("tasks")
        }
        return (0 until tasks.size()).map { index ->
            val taskTable = readArrayElement(tasks, index, "tasks[$index] must be a table") {
                tasks.getTable(index)
            } ?: throw structure("tasks[$index] must be a table", tasks.inputPositionOf(index))
            parseTask(taskTable, scope, index)
        }
    }

    private fun parseTask(table: TomlTable, scope: TaskScope, index: Int): DevTask {
        val id = requiredString(table, "id", "Task at index $index")
        val sourceType = requiredString(table, "sourceType", "Task '$id'")
        val source = when (sourceType) {
            "script" -> TaskSource.ScriptFile(
                path = requiredString(table, "script", "Task '$id'"),
                interpreter = optionalString(table, "interpreter", "Task '$id'"),
                arguments = stringList(table, "arguments", "Task '$id'"),
            )

            "inline" -> TaskSource.InlineCommand(
                command = requiredString(table, "command", "Task '$id'"),
                shell = optionalString(table, "shell", "Task '$id'"),
            )

            else -> throw structure("Task '$id' field 'sourceType' has an unsupported value", table.inputPositionOf("sourceType"))
        }

        return DevTask(
            id = id,
            name = optionalString(table, "name", "Task '$id'"),
            terminalAlias = optionalString(table, "terminalAlias", "Task '$id'"),
            description = optionalString(table, "description", "Task '$id'"),
            scope = scope,
            enabled = optionalBoolean(table, "enabled", "Task '$id'", true),
            source = source,
            workingDirectory = optionalString(table, "workingDirectory", "Task '$id'"),
            environment = parseEnvironment(table, id),
            terminalPolicy = parseTerminalPolicy(optionalString(table, "terminalPolicy", "Task '$id'"), table, id),
            exitDetection = optionalBoolean(table, "exitDetection", "Task '$id'", true),
            autoStart = parseAutoStart(table, id),
            favoriteSlot = optionalInt(table, "favoriteSlot", "Task '$id'"),
        )
    }

    private fun parseEnvironment(table: TomlTable, taskId: String): Map<String, EnvironmentValue> {
        val environment = readField(table, "environment", "Task '$taskId'", "a table") {
            table.getTableOrEmpty("environment")
        }
        return environment.keySet().sorted().associateWith { key ->
            val value = requiredString(environment, key, "Task '$taskId' environment")
            parseEnvironmentValue(value)
        }
    }

    private fun parseEnvironmentValue(value: String): EnvironmentValue {
        ENV_REFERENCE.matchEntire(value)?.let { return EnvironmentValue.SystemReference(it.groupValues[1]) }
        SECRET_REFERENCE.matchEntire(value)?.let { return EnvironmentValue.SecretReference(it.groupValues[1]) }
        return EnvironmentValue.Plain(value)
    }

    private fun parseAutoStart(table: TomlTable, taskId: String): AutoStartConfig? {
        val autoStart = readField(table, "autoStart", "Task '$taskId'", "a table") {
            table.getTable("autoStart")
        } ?: return null
        val triggerValue = requiredString(autoStart, "trigger", "Task '$taskId' autoStart")
        val trigger = when (triggerValue) {
            "ide-start" -> AutoStartTrigger.IDE_START
            "project-open" -> AutoStartTrigger.PROJECT_OPEN
            else -> throw structure(
                "Task '$taskId' field 'autoStart.trigger' has an unsupported value",
                autoStart.inputPositionOf("trigger"),
            )
        }
        val globalModeValue = optionalString(autoStart, "globalMode", "Task '$taskId' autoStart")
        val globalMode = when (globalModeValue) {
            null, "once-per-ide-session" -> GlobalAutoStartMode.ONCE_PER_IDE_SESSION
            "once-per-project" -> GlobalAutoStartMode.ONCE_PER_PROJECT
            else -> throw structure(
                "Task '$taskId' field 'autoStart.globalMode' has an unsupported value",
                autoStart.inputPositionOf("globalMode"),
            )
        }
        return AutoStartConfig(
            trigger = trigger,
            order = optionalInt(autoStart, "order", "Task '$taskId' autoStart") ?: 100,
            delaySeconds = optionalInt(autoStart, "delaySeconds", "Task '$taskId' autoStart") ?: 0,
            globalMode = globalMode,
        )
    }

    private fun parseTerminalPolicy(value: String?, table: TomlTable, taskId: String): TerminalPolicy = when (value) {
        null, "reuse-task" -> TerminalPolicy.REUSE_TASK_TERMINAL
        "always-new" -> TerminalPolicy.ALWAYS_NEW
        "reuse-shared" -> TerminalPolicy.REUSE_SHARED
        else -> throw structure(
            "Task '$taskId' field 'terminalPolicy' has an unsupported value",
            table.inputPositionOf("terminalPolicy"),
        )
    }

    private fun requiredString(table: TomlTable, key: String, context: String): String =
        readField(table, key, context, "a string") {
            table.getString(key)
        } ?: throw structure("$context is missing required string '$key'", table.inputPositionOf(key))

    private fun optionalString(table: TomlTable, key: String, context: String): String? =
        readField(table, key, context, "a string") {
            table.getString(key)
        }

    private fun optionalBoolean(table: TomlTable, key: String, context: String, default: Boolean): Boolean =
        readField(table, key, context, "a boolean") {
            table.getBoolean(key)
        } ?: default

    private fun optionalInt(table: TomlTable, key: String, context: String): Int? {
        val value = readField(table, key, context, "an integer") {
            table.getLong(key)
        } ?: return null
        return try {
            Math.toIntExact(value)
        } catch (_: ArithmeticException) {
            throw structure("$context field '$key' is outside the supported integer range", table.inputPositionOf(key))
        }
    }

    private fun stringList(table: TomlTable, key: String, context: String): List<String> {
        val values = readField(table, key, context, "an array of strings") {
            table.getArray(key)
        } ?: return emptyList()
        return (0 until values.size()).map { index ->
            val errorMessage = "$context field '$key' element $index must be a string"
            readArrayElement(values, index, errorMessage) {
                values.getString(index)
            } ?: throw structure(errorMessage, values.inputPositionOf(index))
        }
    }

    private inline fun <T> readField(
        table: TomlTable,
        key: String,
        context: String,
        expectedType: String,
        getter: () -> T,
    ): T = try {
        getter()
    } catch (_: TomlInvalidTypeException) {
        throw structure("$context field '$key' must be $expectedType", table.inputPositionOf(key))
    }

    private inline fun <T> readArrayElement(
        array: TomlArray,
        index: Int,
        errorMessage: String,
        getter: () -> T,
    ): T = try {
        getter()
    } catch (_: TomlInvalidTypeException) {
        throw structure(errorMessage, array.inputPositionOf(index))
    }

    private fun structure(message: String, position: TomlPosition?): StructureException =
        StructureException(
            safeMessage = message,
            line = position?.line() ?: 1,
            column = position?.column() ?: 1,
        )

    private fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private class StructureException(
        val safeMessage: String,
        val line: Int,
        val column: Int,
    ) : RuntimeException()

    private companion object {
        val ENV_REFERENCE = Regex("^\\$\\{ENV:([^}]+)}$")
        val SECRET_REFERENCE = Regex("^\\$\\{SECRET:([^}]+)}$")
    }
}

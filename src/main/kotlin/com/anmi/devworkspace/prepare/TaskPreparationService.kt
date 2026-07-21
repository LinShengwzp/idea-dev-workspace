package com.anmi.devworkspace.prepare

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.EnvironmentValue
import com.anmi.devworkspace.domain.FailureCategory
import com.anmi.devworkspace.domain.ResolvedTask
import com.anmi.devworkspace.domain.TaskFailure
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskSource
import com.anmi.devworkspace.domain.TerminalPolicy
import com.anmi.devworkspace.secrets.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.UUID

data class PreparationContext(
    val projectDir: Path,
    val userHome: Path,
    val moduleDir: Path?,
    val environment: Map<String, String>,
    val operatingSystem: OperatingSystem,
    val shellEnvironment: String?,
)

data class PreparedTask(
    val taskId: String,
    val executionId: String,
    val displayName: String,
    val command: String,
    val workingDirectory: Path,
    val environment: Map<String, String>,
    val shellType: ShellType,
    val terminalPolicy: TerminalPolicy,
    val exitDetection: Boolean,
    val sourceScope: TaskScope,
)

sealed interface PreparationResult {
    data class Success(val task: PreparedTask) : PreparationResult
    data class Failure(val failure: TaskFailure) : PreparationResult
}

class TaskPreparationService(
    private val secretStore: SecretStore,
    private val executionIdProvider: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun prepare(task: ResolvedTask, context: PreparationContext): PreparationResult =
        prepare(task, context, executionIdProvider())

    suspend fun prepare(
        task: ResolvedTask,
        context: PreparationContext,
        executionId: String,
    ): PreparationResult =
        withContext(Dispatchers.IO) {
            try {
                prepareOnIo(task.effective, context, executionId)
            } catch (failure: PreparationAbort) {
                PreparationResult.Failure(failure.failure)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: RuntimeException) {
                PreparationResult.Failure(
                    TaskFailure(
                        category = FailureCategory.PREPARATION,
                        userMessage = "Task preparation failed",
                        technicalMessage = "Unexpected task preparation failure",
                    ),
                )
            }
        }

    private suspend fun prepareOnIo(
        task: DevTask,
        context: PreparationContext,
        executionId: String,
    ): PreparationResult.Success {
        if (!task.enabled) {
            abort(FailureCategory.CONFIGURATION, "Task is disabled", "Task '${task.id}' is disabled")
        }

        val variables = VariableContext(
            projectDir = context.projectDir,
            userHome = context.userHome,
            moduleDir = context.moduleDir,
            environment = context.environment,
        )
        val workingDirectory = resolvePath(
            task.workingDirectory ?: context.projectDir.toString(),
            variables,
            context.projectDir,
            "working directory",
            task.id,
        )
        if (!Files.isDirectory(workingDirectory)) {
            abort(
                FailureCategory.PREPARATION,
                "Working directory is unavailable",
                "Task '${task.id}' working directory does not exist or is not a directory",
            )
        }

        val preparedEnvironment = resolveEnvironment(task, variables)
        val source = prepareSource(task, variables, context, workingDirectory)
        val command = if (task.exitDetection) {
            CommandWrapper.wrap(source.wrapperShell, source.command, executionId).text
        } else {
            source.command
        }

        return PreparationResult.Success(
            PreparedTask(
                taskId = task.id,
                executionId = executionId,
                displayName = task.terminalAlias
                    ?: source.scriptFileName
                    ?: task.name
                    ?: task.id,
                command = command,
                workingDirectory = workingDirectory,
                environment = preparedEnvironment.toMap(),
                shellType = source.shellType,
                terminalPolicy = task.terminalPolicy,
                exitDetection = task.exitDetection,
                sourceScope = task.scope,
            ),
        )
    }

    private suspend fun resolveEnvironment(
        task: DevTask,
        variables: VariableContext,
    ): Map<String, String> = buildMap {
        task.environment.toSortedMap().forEach { (name, value) ->
            val resolved = when (value) {
                is EnvironmentValue.Plain -> resolveText(value.value, variables, "environment variable '$name'", task.id)
                is EnvironmentValue.SystemReference -> variables.environment[value.name]
                    ?: abort(
                        FailureCategory.PREPARATION,
                        "Required environment variable is unavailable",
                        "Task '${task.id}' requires environment variable '${value.name}'",
                    )

                is EnvironmentValue.SecretReference -> resolveSecret(task.id, value.key)
            }
            put(name, resolved)
        }
    }

    private suspend fun resolveSecret(taskId: String, key: String): String {
        val value = try {
            secretStore.get(key)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            abort(
                FailureCategory.SECURITY,
                "Required secret is unavailable",
                "Task '$taskId' could not read secret '$key'",
            )
        }
        return value ?: abort(
            FailureCategory.SECURITY,
            "Required secret is unavailable",
            "Task '$taskId' is missing secret '$key'",
        )
    }

    private fun prepareSource(
        task: DevTask,
        variables: VariableContext,
        context: PreparationContext,
        workingDirectory: Path,
    ): PreparedSource = when (val source = task.source) {
        is TaskSource.InlineCommand -> {
            val explicitShell = source.shell?.let {
                resolveText(it, variables, "inline shell", task.id)
            }
            val shellType = detectShell(
                task.id,
                ShellDetectionContext(
                    operatingSystem = context.operatingSystem,
                    explicitInterpreter = explicitShell,
                    fileName = null,
                    firstLine = null,
                    shellEnvironment = context.shellEnvironment,
                ),
            )
            PreparedSource(
                command = resolveText(source.command, variables, "inline command", task.id),
                shellType = shellType,
                wrapperShell = shellType,
                scriptFileName = null,
            )
        }

        is TaskSource.ScriptFile -> {
            val scriptPath = resolvePath(source.path, variables, workingDirectory, "script path", task.id)
            if (!Files.isRegularFile(scriptPath)) {
                abort(
                    FailureCategory.PREPARATION,
                    "Script file is unavailable",
                    "Task '${task.id}' script does not exist or is not a regular file",
                )
            }
            val firstLine = Files.newBufferedReader(scriptPath).use { reader -> reader.readLine() }
            val explicitInterpreter = source.interpreter?.let {
                resolveText(it, variables, "script interpreter", task.id)
            }
            val shellType = detectShell(
                task.id,
                ShellDetectionContext(
                    operatingSystem = context.operatingSystem,
                    explicitInterpreter = explicitInterpreter,
                    fileName = scriptPath.fileName.toString(),
                    firstLine = firstLine,
                    shellEnvironment = context.shellEnvironment,
                ),
            )
            val wrapperShell = if (shellType == ShellType.DIRECT) defaultShell(task.id, context) else shellType
            val quoter = quoter(wrapperShell)
            val arguments = source.arguments.mapIndexed { index, argument ->
                quoter.quote(resolveText(argument, variables, "script argument $index", task.id))
            }
            val launcher = explicitInterpreter ?: defaultInterpreter(shellType, scriptPath)
            val tokens = buildList {
                launcher?.let { add(quoter.quote(it)) }
                add(quoter.quote(scriptPath.toString()))
                addAll(arguments)
            }
            val rawCommand = tokens.joinToString(" ").let { command ->
                if (wrapperShell == ShellType.POWERSHELL) "& $command" else command
            }
            PreparedSource(
                command = rawCommand,
                shellType = shellType,
                wrapperShell = wrapperShell,
                scriptFileName = scriptPath.fileName.toString(),
            )
        }
    }

    private fun defaultShell(taskId: String, context: PreparationContext): ShellType = detectShell(
        taskId,
        ShellDetectionContext(
            operatingSystem = context.operatingSystem,
            explicitInterpreter = null,
            fileName = null,
            firstLine = null,
            shellEnvironment = context.shellEnvironment,
        ),
    )

    private fun detectShell(taskId: String, context: ShellDetectionContext): ShellType =
        ShellDetector.detect(context).getOrElse {
            abort(
                FailureCategory.PREPARATION,
                "Unable to determine the task shell",
                "Task '$taskId' shell detection failed",
            )
        }

    private fun quoter(shellType: ShellType): ShellQuoter = when (shellType) {
        ShellType.POWERSHELL -> PowerShellQuoter
        ShellType.CMD -> CmdQuoter
        ShellType.BASH, ShellType.ZSH, ShellType.POSIX_SH -> PosixShellQuoter
        ShellType.DIRECT -> error("Direct shell must be resolved before quoting")
    }

    private fun defaultInterpreter(shellType: ShellType, scriptPath: Path): String? = when (shellType) {
        ShellType.BASH -> "bash"
        ShellType.ZSH -> "zsh"
        ShellType.POSIX_SH -> if (scriptPath.fileName.toString().endsWith(".sh", ignoreCase = true)) "/bin/sh" else null
        ShellType.POWERSHELL, ShellType.CMD, ShellType.DIRECT -> null
    }

    private fun resolveText(value: String, variables: VariableContext, field: String, taskId: String): String =
        VariableResolver.resolveText(value, variables).getOrElse { error ->
            abort(
                FailureCategory.PREPARATION,
                "Task variables could not be resolved",
                "Task '$taskId' $field could not be resolved: ${error.message ?: "unknown variable error"}",
            )
        }

    private fun resolvePath(
        value: String,
        variables: VariableContext,
        base: Path,
        field: String,
        taskId: String,
    ): Path {
        val resolved = resolveText(value, variables, field, taskId)
        return try {
            val path = Path.of(resolved)
            (if (path.isAbsolute) path else base.resolve(path)).normalize()
        } catch (_: InvalidPathException) {
            abort(
                FailureCategory.PREPARATION,
                "Task path is invalid",
                "Configured $field is not a valid path",
            )
        }
    }

    private fun abort(
        category: FailureCategory,
        userMessage: String,
        technicalMessage: String,
    ): Nothing = throw PreparationAbort(TaskFailure(category, userMessage, technicalMessage))

    private data class PreparedSource(
        val command: String,
        val shellType: ShellType,
        val wrapperShell: ShellType,
        val scriptFileName: String?,
    )

    private class PreparationAbort(val failure: TaskFailure) : RuntimeException()
}

package com.anmi.devworkspace.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.config.TaskConfigConflictException
import com.anmi.devworkspace.domain.AutoStartConfig
import com.anmi.devworkspace.domain.AutoStartTrigger
import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.EnvironmentValue
import com.anmi.devworkspace.domain.GlobalAutoStartMode
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskSource
import com.anmi.devworkspace.domain.TerminalPolicy
import com.anmi.devworkspace.prepare.CmdQuoter
import com.anmi.devworkspace.prepare.OperatingSystem
import com.anmi.devworkspace.prepare.PosixShellQuoter
import com.anmi.devworkspace.prepare.PowerShellQuoter
import com.anmi.devworkspace.prepare.ShellDetectionContext
import com.anmi.devworkspace.prepare.ShellDetector
import com.anmi.devworkspace.prepare.ShellType
import com.anmi.devworkspace.prepare.VariableContext
import com.anmi.devworkspace.prepare.VariableResolver
import java.nio.file.Path
import java.util.Collections

enum class SourceKind { SCRIPT, INLINE }

enum class EnvironmentRowKind { PLAIN, SYSTEM, SECRET }

data class EnvironmentRow(
    val name: String,
    val kind: EnvironmentRowKind,
    /** Plain value, system environment name, or secret reference key. Never a secret value. */
    val value: String,
)

data class TaskEditorState(
    val id: String = "",
    val name: String = "",
    val terminalAlias: String = "",
    val description: String = "",
    val scope: TaskScope = TaskScope.PROJECT_PRIVATE,
    val enabled: Boolean = true,
    val sourceKind: SourceKind = SourceKind.SCRIPT,
    val scriptPath: String = "",
    val arguments: List<String> = emptyList(),
    val interpreter: String = "",
    val inlineCommand: String = "",
    val inlineShell: String = "",
    val workingDirectory: String = "\${PROJECT_DIR}",
    val terminalPolicy: TerminalPolicy = TerminalPolicy.REUSE_TASK_TERMINAL,
    val exitDetection: Boolean = true,
    val autoStartTrigger: AutoStartTrigger? = null,
    val startupOrder: Int = 100,
    val startupDelaySeconds: Int = 0,
    val globalMode: GlobalAutoStartMode = GlobalAutoStartMode.ONCE_PER_IDE_SESSION,
    val favoriteSlot: Int? = null,
    val environmentRows: List<EnvironmentRow> = emptyList(),
) {
    companion object {
        fun from(task: DevTask): TaskEditorState {
            val source = task.source
            return TaskEditorState(
                id = task.id,
                name = task.name.orEmpty(),
                terminalAlias = task.terminalAlias.orEmpty(),
                description = task.description.orEmpty(),
                scope = task.scope,
                enabled = task.enabled,
                sourceKind = if (source is TaskSource.ScriptFile) SourceKind.SCRIPT else SourceKind.INLINE,
                scriptPath = (source as? TaskSource.ScriptFile)?.path.orEmpty(),
                arguments = (source as? TaskSource.ScriptFile)?.arguments.orEmpty().toList(),
                interpreter = (source as? TaskSource.ScriptFile)?.interpreter.orEmpty(),
                inlineCommand = (source as? TaskSource.InlineCommand)?.command.orEmpty(),
                inlineShell = (source as? TaskSource.InlineCommand)?.shell.orEmpty(),
                workingDirectory = task.workingDirectory ?: "\${PROJECT_DIR}",
                terminalPolicy = task.terminalPolicy,
                exitDetection = task.exitDetection,
                autoStartTrigger = task.autoStart?.trigger,
                startupOrder = task.autoStart?.order ?: 100,
                startupDelaySeconds = task.autoStart?.delaySeconds ?: 0,
                globalMode = task.autoStart?.globalMode ?: GlobalAutoStartMode.ONCE_PER_IDE_SESSION,
                favoriteSlot = task.favoriteSlot,
                environmentRows = task.environment.map { (name, value) ->
                    when (value) {
                        is EnvironmentValue.Plain -> EnvironmentRow(name, EnvironmentRowKind.PLAIN, value.value)
                        is EnvironmentValue.SystemReference -> EnvironmentRow(name, EnvironmentRowKind.SYSTEM, value.name)
                        is EnvironmentValue.SecretReference -> EnvironmentRow(name, EnvironmentRowKind.SECRET, value.key)
                    }
                },
            )
        }
    }
}

data class ValidationIssue(val field: String, val message: String, val warning: Boolean = false)

data class ScopeSnapshot(val tasks: List<DevTask>, val contentHash: String?) {
    init {
        require(tasks.all { it.scope == tasks.firstOrNull()?.scope || tasks.isEmpty() })
    }
}

class EditorSnapshots(values: Map<TaskScope, ScopeSnapshot>) {
    private val values = Collections.unmodifiableMap(
        TaskScope.entries.associateWith { scope ->
            val value = values[scope] ?: ScopeSnapshot(emptyList(), null)
            ScopeSnapshot(Collections.unmodifiableList(value.tasks.toList()), value.contentHash)
        },
    )

    operator fun get(scope: TaskScope): ScopeSnapshot = values.getValue(scope)
}

enum class EditorMode { NEW, EDIT, COPY }

sealed interface TaskConversion {
    data class Valid(val task: DevTask) : TaskConversion
    data class Invalid(val issues: List<ValidationIssue>) : TaskConversion
}

data class ScopeMutation(
    val scope: TaskScope,
    val tasks: List<DevTask>,
    val expectedHash: String?,
) {
    fun forChoice(choice: ConflictChoice): ScopeMutation =
        if (choice == ConflictChoice.OVERWRITE) copy(expectedHash = null) else this
}

data class TaskSavePlan(val mutations: List<ScopeMutation>)

sealed interface PlanResult {
    data class Valid(val plan: TaskSavePlan) : PlanResult
    data class Invalid(val issues: List<ValidationIssue>) : PlanResult
}

enum class ConflictChoice { RELOAD_AND_EDIT, VIEW_DIFF, OVERWRITE }

sealed interface SaveExecution {
    data object Saved : SaveExecution
    data class Reloaded(val partial: Boolean) : SaveExecution
}

class TaskSaveCoordinator(
    private val save: suspend (ScopeMutation) -> Unit,
    private val reload: suspend (TaskScope) -> Unit,
    private val choose: suspend (ScopeMutation) -> ConflictChoice,
    private val viewDiff: suspend (ScopeMutation) -> Unit,
) {
    suspend fun execute(plan: TaskSavePlan): SaveExecution {
        var completed = 0
        for (planned in plan.mutations) {
            var mutation = planned
            while (true) {
                try {
                    save(mutation)
                    completed++
                    break
                } catch (_: TaskConfigConflictException) {
                    when (choose(mutation)) {
                        ConflictChoice.RELOAD_AND_EDIT -> {
                            reload(mutation.scope)
                            return SaveExecution.Reloaded(partial = completed > 0)
                        }
                        ConflictChoice.VIEW_DIFF -> viewDiff(mutation)
                        ConflictChoice.OVERWRITE -> mutation = mutation.forChoice(ConflictChoice.OVERWRITE)
                    }
                }
            }
        }
        return SaveExecution.Saved
    }
}

data class TaskPreviewContext(
    val projectDir: Path,
    val userHome: Path,
    val moduleDir: Path?,
    val operatingSystem: OperatingSystem,
    val shellEnvironment: String?,
)

data class TaskPreview(
    val workingDirectory: String,
    val shell: String,
    val command: String,
    val environment: List<String>,
    val warnings: List<String>,
)

object TaskScriptPath {
    fun resolve(value: String, projectDir: Path): Path? = runCatching {
        val text = value.trim()
        if (text.isEmpty()) return null
        val portable = text.replace('\\', '/')
        val path = when {
            portable == PROJECT_TOKEN -> projectDir
            portable.startsWith("$PROJECT_TOKEN/") -> projectDir.resolve(portable.removePrefix("$PROJECT_TOKEN/"))
            else -> Path.of(text)
        }
        path.toAbsolutePath().normalize()
    }.getOrNull()

    fun store(selected: Path, projectDir: Path): String {
        val project = projectDir.toAbsolutePath().normalize()
        val file = selected.toAbsolutePath().normalize()
        return if (file.startsWith(project)) {
            val relative = project.relativize(file).joinToString("/") { it.toString() }
            "$PROJECT_TOKEN/$relative"
        } else {
            file.toString().replace('\\', '/')
        }
    }

    private const val PROJECT_TOKEN = "\${PROJECT_DIR}"
}

class TaskEditorModel(
    val state: TaskEditorState,
    private val snapshots: EditorSnapshots,
    private val mode: EditorMode = EditorMode.NEW,
    val original: DevTask? = if (mode == EditorMode.EDIT || mode == EditorMode.COPY) {
        snapshots[state.scope].tasks.firstOrNull { it.id == state.id }
    } else {
        null
    },
) {
    fun withState(state: TaskEditorState): TaskEditorModel = TaskEditorModel(state, snapshots, mode, original)

    fun validate(): List<ValidationIssue> = buildList {
        if (!ID.matches(state.id)) add(ValidationIssue("id", message("task.validation.id")))
        when (state.sourceKind) {
            SourceKind.SCRIPT -> if (state.scriptPath.isBlank()) {
                add(ValidationIssue("scriptPath", message("task.validation.script.path")))
            }
            SourceKind.INLINE -> if (state.inlineCommand.isBlank()) {
                add(ValidationIssue("inlineCommand", message("task.validation.inline.command")))
            }
        }
        if (state.favoriteSlot != null && state.favoriteSlot !in 1..9) {
            add(ValidationIssue("favoriteSlot", message("task.validation.favorite.slot")))
        }
        if (state.startupOrder < 0) add(ValidationIssue("startupOrder", message("task.validation.startup.order")))
        if (state.startupDelaySeconds < 0) {
            add(ValidationIssue("startupDelaySeconds", message("task.validation.startup.delay")))
        }

        val seen = mutableSetOf<String>()
        state.environmentRows.forEachIndexed { index, row ->
            if (!ENVIRONMENT_NAME.matches(row.name)) {
                add(ValidationIssue("environment.$index.name", message("task.validation.environment.name")))
            } else if (!seen.add(row.name)) {
                add(ValidationIssue("environment.$index.name", message("task.validation.environment.duplicate")))
            }
            when (row.kind) {
                EnvironmentRowKind.PLAIN -> Unit
                EnvironmentRowKind.SYSTEM -> if (!ENVIRONMENT_NAME.matches(row.value)) {
                    add(ValidationIssue("environment.$index.value", message("task.validation.environment.system")))
                }
                EnvironmentRowKind.SECRET -> if (row.value.isBlank()) {
                    add(ValidationIssue("environment.$index.value", message("task.validation.environment.secret")))
                }
            }
        }

        if (ID.matches(state.id) && duplicatesTarget()) {
            add(ValidationIssue("id", message("task.validation.duplicate.id")))
        }
    }

    fun toDevTask(): TaskConversion {
        val issues = validate()
        if (issues.any { !it.warning }) return TaskConversion.Invalid(issues)
        val source = when (state.sourceKind) {
            SourceKind.SCRIPT -> TaskSource.ScriptFile(
                path = state.scriptPath,
                interpreter = state.interpreter.nonBlank(),
                arguments = state.arguments.toList(),
            )
            SourceKind.INLINE -> TaskSource.InlineCommand(state.inlineCommand, state.inlineShell.nonBlank())
        }
        return TaskConversion.Valid(
            DevTask(
                id = state.id,
                name = state.name.nonBlank(),
                terminalAlias = state.terminalAlias.nonBlank(),
                description = state.description.nonBlank(),
                scope = state.scope,
                enabled = state.enabled,
                source = source,
                workingDirectory = state.workingDirectory.nonBlank(),
                environment = LinkedHashMap<String, EnvironmentValue>().apply {
                    state.environmentRows.forEach { row ->
                        put(
                            row.name,
                            when (row.kind) {
                                EnvironmentRowKind.PLAIN -> EnvironmentValue.Plain(row.value)
                                EnvironmentRowKind.SYSTEM -> EnvironmentValue.SystemReference(row.value)
                                EnvironmentRowKind.SECRET -> EnvironmentValue.SecretReference(row.value)
                            },
                        )
                    }
                },
                terminalPolicy = state.terminalPolicy,
                exitDetection = state.exitDetection,
                autoStart = state.autoStartTrigger?.let {
                    AutoStartConfig(it, state.startupOrder, state.startupDelaySeconds, state.globalMode)
                },
                favoriteSlot = state.favoriteSlot,
            ),
        )
    }

    fun planSave(): PlanResult {
        val converted = toDevTask()
        if (converted is TaskConversion.Invalid) return PlanResult.Invalid(converted.issues)
        val edited = (converted as TaskConversion.Valid).task
        val mutations = mutableListOf<ScopeMutation>()
        val originalTask = original.takeIf { mode == EditorMode.EDIT }
        if (originalTask != null && originalTask.scope != edited.scope) {
            mutations += mutation(originalTask.scope) { tasks -> tasks.filterNot { it.id == originalTask.id } }
        }
        mutations += mutation(edited.scope) { tasks ->
            tasks.filterNot { existing ->
                originalTask != null && originalTask.scope == edited.scope && existing.id == originalTask.id
            } + edited
        }
        return PlanResult.Valid(TaskSavePlan(mutations.distinctBy { it.scope }))
    }

    fun planDelete(): PlanResult {
        val originalTask = original ?: return PlanResult.Invalid(
            listOf(ValidationIssue("id", message("task.validation.delete.existing"))),
        )
        return PlanResult.Valid(
            TaskSavePlan(listOf(mutation(originalTask.scope) { tasks -> tasks.filterNot { it.id == originalTask.id } })),
        )
    }

    fun preview(context: TaskPreviewContext): TaskPreview {
        val warnings = validate().map { it.message }.toMutableList()
        val workingDirectory = resolvePreviewText(state.workingDirectory, context).getOrElse {
            warnings += message("task.editor.warning.directory.variable")
            message("task.editor.preview.unavailable")
        }
        val shellResult = ShellDetector.detect(
            ShellDetectionContext(
                operatingSystem = context.operatingSystem,
                explicitInterpreter = when (state.sourceKind) {
                    SourceKind.SCRIPT -> state.interpreter.nonBlank()
                    SourceKind.INLINE -> state.inlineShell.nonBlank()
                },
                fileName = state.scriptPath.takeIf { state.sourceKind == SourceKind.SCRIPT },
                firstLine = null,
                shellEnvironment = context.shellEnvironment,
            ),
        )
        val shell = shellResult.getOrElse {
            warnings += message("task.editor.warning.shell")
            ShellType.DIRECT
        }
        val command = when (state.sourceKind) {
            SourceKind.INLINE -> sanitize(state.inlineCommand)
            SourceKind.SCRIPT -> buildList {
                add(state.scriptPath)
                addAll(state.arguments)
            }.joinToString(" ") { sanitize(quoter(shell).quote(it)) }
        }
        val environment = state.environmentRows.map { row ->
            val rendered = when (row.kind) {
                EnvironmentRowKind.PLAIN -> sanitize(row.value)
                EnvironmentRowKind.SYSTEM -> "\${ENV:${row.value}}"
                EnvironmentRowKind.SECRET -> "\${SECRET:${row.value}}"
            }
            "${row.name}=$rendered"
        }
        return TaskPreview(sanitize(workingDirectory), shell.name, command, environment, warnings.distinct())
    }

    private fun duplicatesTarget(): Boolean {
        val originalTask = original.takeIf { mode == EditorMode.EDIT }
        return snapshots[state.scope].tasks.any { existing ->
            existing.id == state.id && !(originalTask?.scope == state.scope && originalTask.id == existing.id)
        }
    }

    private fun mutation(scope: TaskScope, transform: (List<DevTask>) -> List<DevTask>): ScopeMutation {
        val snapshot = snapshots[scope]
        return ScopeMutation(scope, transform(snapshot.tasks), snapshot.contentHash)
    }

    private fun resolvePreviewText(value: String, context: TaskPreviewContext): Result<String> {
        val protected = mutableListOf<String>()
        val safeInput = REFERENCE.replace(value) { match ->
            protected += match.value
            "__DEV_WORKSPACE_REFERENCE_${protected.lastIndex}__"
        }
        return VariableResolver.resolveText(
            safeInput,
            VariableContext(context.projectDir, context.userHome, context.moduleDir, emptyMap()),
        ).map { resolved ->
            protected.foldIndexed(resolved) { index, text, token ->
                text.replace("__DEV_WORKSPACE_REFERENCE_${index}__", token)
            }
        }
    }

    private fun quoter(shell: ShellType) = when (shell) {
        ShellType.POWERSHELL -> PowerShellQuoter
        ShellType.CMD -> CmdQuoter
        ShellType.BASH, ShellType.ZSH, ShellType.POSIX_SH, ShellType.DIRECT -> PosixShellQuoter
    }

    private fun sanitize(value: String): String = value.map { character ->
        if (character.code < 32 || character.code == 127) ' ' else character
    }.joinToString("")

    private fun String.nonBlank(): String? = takeIf(String::isNotBlank)

    private fun message(key: String): String = DevWorkspaceBundle.message(key)

    private companion object {
        val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val REFERENCE = Regex("\\$\\{(?:ENV:[^{}]+|SECRET:[^{}]+)}")
    }
}

package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.DevTask
import com.anmi.devworkspace.domain.ResolvedTask
import com.anmi.devworkspace.domain.TaskScope
import com.anmi.devworkspace.domain.TaskSource
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

data class TaskConfigurationState(
    val tasks: List<ResolvedTask> = Collections.unmodifiableList(ArrayList()),
    val errors: Map<TaskScope, List<TaskConfigError>> = Collections.unmodifiableMap(LinkedHashMap()),
    val snapshots: Map<TaskScope, TaskConfigSnapshot> = Collections.unmodifiableMap(LinkedHashMap()),
)

@Service(Service.Level.PROJECT)
class TaskConfigurationService private constructor(
    private val project: Project?,
    scope: CoroutineScope,
    private val repositories: Map<TaskScope, TaskRepository>,
    private val lastValidStore: TaskSnapshotCache?,
    startImmediately: Boolean,
) : Disposable {
    constructor(project: Project, scope: CoroutineScope) : this(
        project,
        scope,
        productionBundle(project),
        true,
    )

    private constructor(project: Project, scope: CoroutineScope, bundle: ProductionBundle, startImmediately: Boolean) : this(
        project,
        scope,
        bundle.repositories,
        bundle.lastValidStore,
        startImmediately,
    )

    internal constructor(scope: CoroutineScope, repositories: List<TaskRepository>) : this(
        null,
        scope,
        repositories.associateBy { it.scope },
        null,
        false,
    )

    internal constructor(
        scope: CoroutineScope,
        repositories: List<TaskRepository>,
        lastValidStore: TaskSnapshotCache,
    ) : this(null, scope, repositories.associateBy { it.scope }, lastValidStore, false)

    private val serviceJob = SupervisorJob(scope.coroutineContext[Job])
    private val serviceScope = CoroutineScope(scope.coroutineContext + serviceJob)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(TaskConfigurationState())
    val state: StateFlow<TaskConfigurationState> = mutableState.asStateFlow()
    private val fileListener = project?.let {
        TaskConfigFileListener(
            watchedPaths = repositories.mapValues { (_, repository) -> repository.path },
            scope = serviceScope,
            reload = { reload() },
        )
    }
    private val messageBusConnection = project?.messageBus?.connect(this)?.also { connection ->
        connection.subscribe(VirtualFileManager.VFS_CHANGES, checkNotNull(fileListener))
    }

    init {
        require(repositories.keys.containsAll(TaskScope.entries)) { "A repository is required for every task scope" }
        if (startImmediately) serviceScope.launch { reload() }
    }

    suspend fun reload(scope: TaskScope? = null) = mutex.withLock {
        val selected = scope?.let(::listOf) ?: TaskScope.entries
        var snapshots = mutableState.value.snapshots.toMutableMap()
        var errors = mutableState.value.errors.toMutableMap()
        selected.forEach { selectedScope ->
            when (val result = withContext(Dispatchers.IO) { repositories.getValue(selectedScope).load() }) {
                is TaskConfigLoadResult.Success -> {
                    val snapshot = immutableSnapshot(result.snapshot)
                    snapshots[selectedScope] = snapshot
                    errors.remove(selectedScope)
                    withContext(Dispatchers.IO) { saveLastValid(snapshot) }
                }
                is TaskConfigLoadResult.Failure -> {
                    errors[selectedScope] = result.errors.map(::sanitizeError)
                    if (snapshots[selectedScope] == null) {
                        loadLastValid(selectedScope)?.let { snapshots[selectedScope] = immutableSnapshot(it) }
                    }
                }
            }
        }
        publish(snapshots, errors)
    }

    suspend fun save(scope: TaskScope, tasks: List<DevTask>, expectedHash: String?) = mutex.withLock {
        val snapshot = withContext(Dispatchers.IO) {
            repositories.getValue(scope).save(tasks.toList(), expectedHash)
        }
        fileListener?.expectPluginWrite(path(scope), snapshot.contentHash)
        withContext(Dispatchers.IO) { saveLastValid(snapshot) }
        val snapshots = mutableState.value.snapshots.toMutableMap().apply { put(scope, immutableSnapshot(snapshot)) }
        val errors = mutableState.value.errors.toMutableMap().apply { remove(scope) }
        publish(snapshots, errors)
    }

    fun path(scope: TaskScope): Path = repositories.getValue(scope).path.toAbsolutePath().normalize()

    override fun dispose() {
        fileListener?.dispose()
        messageBusConnection?.disconnect()
        serviceScope.cancel()
    }

    private fun publish(
        snapshots: Map<TaskScope, TaskConfigSnapshot>,
        errors: Map<TaskScope, List<TaskConfigError>>,
    ) {
        val immutableSnapshots = immutableMap(snapshots)
        val tasksByScope = TaskScope.entries.associateWith { immutableSnapshots[it]?.tasks.orEmpty() }
        val resolvedTasks = TaskConfigResolver.resolve(
            global = tasksByScope.getValue(TaskScope.GLOBAL),
            shared = tasksByScope.getValue(TaskScope.PROJECT_SHARED),
            privateTasks = tasksByScope.getValue(TaskScope.PROJECT_PRIVATE),
        ).map { resolved ->
            resolved.copy(
                effective = immutableTask(resolved.effective),
                shadowed = immutableList(resolved.shadowed.map(::immutableTask)),
            )
        }
        mutableState.value = TaskConfigurationState(
            tasks = immutableList(resolvedTasks),
            errors = immutableMap(errors.mapValues { (_, value) -> immutableList(value) }),
            snapshots = immutableSnapshots,
        )
    }

    private fun immutableSnapshot(snapshot: TaskConfigSnapshot): TaskConfigSnapshot =
        snapshot.copy(
            sourceFile = snapshot.sourceFile.toAbsolutePath().normalize(),
            tasks = immutableList(snapshot.tasks.map(::immutableTask)),
        )

    private fun immutableTask(task: DevTask): DevTask = task.copy(
        environment = immutableMap(task.environment),
        source = when (val source = task.source) {
            is TaskSource.InlineCommand -> source.copy()
            is TaskSource.ScriptFile -> source.copy(arguments = immutableList(source.arguments))
        },
    )

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))

    private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
        Collections.unmodifiableMap(LinkedHashMap(values))

    private fun sanitizeError(error: TaskConfigError): TaskConfigError = error.copy(
        message = if (error.message in SAFE_ERROR_MESSAGES) error.message else "Invalid task configuration",
    )

    private fun saveLastValid(snapshot: TaskConfigSnapshot) {
        val store = lastValidStore ?: return
        try {
            val content = if (Files.exists(snapshot.sourceFile)) {
                Files.readString(snapshot.sourceFile, StandardCharsets.UTF_8)
            } else {
                "version = 1"
            }
            store.save(snapshot, content)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The validated live snapshot remains usable when the best-effort cache cannot be refreshed.
        }
    }

    private suspend fun loadLastValid(scope: TaskScope): TaskConfigSnapshot? = try {
        withContext(Dispatchers.IO) { lastValidStore?.load(path(scope), scope) }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    private companion object {
        val SAFE_ERROR_MESSAGES = setOf(
            "Invalid TOML syntax",
            "Unable to read task configuration",
            "Invalid task configuration field type",
            "Invalid task configuration structure",
        )

        data class ProductionBundle(
            val repositories: Map<TaskScope, TaskRepository>,
            val lastValidStore: LastValidSnapshotStore,
        )

        fun productionBundle(project: Project): ProductionBundle {
            val basePath = requireNotNull(project.basePath) { "Project base path is required for task configuration" }
            val paths = TaskRepositoryPaths(
                projectBase = Path.of(basePath),
                configDir = PathManager.getConfigDir(),
                systemDir = PathManager.getSystemDir(),
            )
            val repositories = TaskScope.entries.associateWith { taskScope ->
                FileTaskRepository(taskScope, paths.path(taskScope).toAbsolutePath().normalize())
            }
            return ProductionBundle(
                repositories = repositories,
                lastValidStore = LastValidSnapshotStore(
                    parser = TaskTomlParser(),
                    writer = AtomicFileWriter(),
                    cacheRoot = paths.lastValidCacheRoot,
                ),
            )
        }
    }
}

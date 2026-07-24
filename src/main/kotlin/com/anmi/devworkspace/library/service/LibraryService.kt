package com.anmi.devworkspace.library.service

import com.anmi.devworkspace.config.AtomicFileWriter
import com.anmi.devworkspace.library.domain.LibraryGroup
import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.storage.FileLibraryRepository
import com.anmi.devworkspace.library.storage.LibraryDocument
import com.anmi.devworkspace.library.storage.LibraryFileListener
import com.anmi.devworkspace.library.storage.LibraryJsonCodec
import com.anmi.devworkspace.library.storage.LibraryLastValidStore
import com.anmi.devworkspace.library.storage.LibraryLoadError
import com.anmi.devworkspace.library.storage.LibraryLoadResult
import com.anmi.devworkspace.library.storage.LibraryRepository
import com.anmi.devworkspace.library.storage.LibraryRepositoryPaths
import com.anmi.devworkspace.library.storage.LibrarySnapshot
import com.anmi.devworkspace.library.storage.LibraryWatchedPaths
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import java.nio.file.Path
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
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

/**
 * Project service that publishes the non-overriding union of all library scopes.
 *
 * Repository failures are isolated: a failed layer keeps its previous valid
 * snapshot while other layers continue to reload and publish normally.
 */
@Service(Service.Level.PROJECT)
class LibraryService private constructor(
    private val project: Project?,
    scope: CoroutineScope,
    private val repositories: Map<LibraryScope, LibraryRepository>,
    startImmediately: Boolean,
) : Disposable {
    constructor(project: Project, scope: CoroutineScope) : this(
        project,
        scope,
        productionRepositories(project),
        true,
    )

    internal constructor(scope: CoroutineScope, repositories: List<LibraryRepository>) : this(
        null,
        scope,
        repositories.associateBy(LibraryRepository::scope),
        false,
    )

    private val serviceJob = SupervisorJob(scope.coroutineContext[Job])
    private val serviceScope = CoroutineScope(scope.coroutineContext + serviceJob)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = mutableState.asStateFlow()
    private val fileListener = project?.let {
        LibraryFileListener(
            watchedPaths = repositories.mapValues { (_, repository) ->
                LibraryWatchedPaths(
                    index = repository.path,
                    contents = repository.root.resolve("contents"),
                )
            },
            scope = serviceScope,
            reload = { scopes -> reload(scopes) },
        )
    }
    private val messageBusConnection = project?.messageBus?.connect(this)?.also { connection ->
        connection.subscribe(VirtualFileManager.VFS_CHANGES, checkNotNull(fileListener))
    }

    init {
        require(repositories.keys.containsAll(LibraryScope.entries)) {
            "A repository is required for every library scope"
        }
        if (startImmediately) serviceScope.launch { reload() }
    }

    suspend fun reload(scope: LibraryScope? = null) {
        reload(scope?.let(::setOf) ?: LibraryScope.entries.toSet())
    }

    /** Explicit user refresh boundary that reloads every repository scope. */
    suspend fun refresh() {
        reload()
    }

    suspend fun save(scope: LibraryScope, document: LibraryDocument): LibrarySnapshot =
        mutex.withLock {
            val snapshot = withContext(Dispatchers.IO) {
                repositories.getValue(scope).save(document)
            }
            fileListener?.expectPluginWrite(path(scope), snapshot.contentHash)
            val snapshots = mutableState.value.snapshots.toMutableMap().apply {
                put(scope, immutableSnapshot(snapshot))
            }
            val errors = mutableState.value.errors.toMutableMap().apply { remove(scope) }
            publish(snapshots, errors)
            snapshot
        }

    fun path(scope: LibraryScope): Path =
        repositories.getValue(scope).path.toAbsolutePath().normalize()

    /** Adapter access for transactional import/export; callers must keep work off EDT. */
    internal fun repository(scope: LibraryScope): LibraryRepository = repositories.getValue(scope)

    override fun dispose() {
        fileListener?.dispose()
        messageBusConnection?.disconnect()
        serviceScope.cancel()
    }

    private suspend fun reload(scopes: Set<LibraryScope>) = mutex.withLock {
        val snapshots = mutableState.value.snapshots.toMutableMap()
        val errors = mutableState.value.errors.toMutableMap()
        LibraryScope.entries.filter(scopes::contains).forEach { selectedScope ->
            when (
                val result = withContext(Dispatchers.IO) {
                    repositories.getValue(selectedScope).load()
                }
            ) {
                is LibraryLoadResult.Success -> {
                    snapshots[selectedScope] = immutableSnapshot(result.snapshot)
                    errors.remove(selectedScope)
                }
                is LibraryLoadResult.Recovered -> {
                    snapshots[selectedScope] = immutableSnapshot(result.snapshot)
                    errors[selectedScope] = immutableError(result.error)
                }
                is LibraryLoadResult.Failure -> {
                    // Keep the last state snapshot for this scope while publishing its new error.
                    errors[selectedScope] = immutableError(result.error)
                }
            }
        }
        publish(snapshots, errors)
    }

    private fun publish(
        snapshots: Map<LibraryScope, LibrarySnapshot>,
        errors: Map<LibraryScope, LibraryLoadError>,
    ) {
        val orderedSnapshots = LibraryScope.entries.mapNotNull { scope ->
            snapshots[scope]?.let { scope to it }
        }.toMap(LinkedHashMap())
        val groups = orderedSnapshots.values.flatMap { it.document.groups }
        val items = orderedSnapshots.values.flatMap { it.document.items }
        mutableState.value = LibraryState(
            groups = immutableList(groups),
            items = immutableList(items),
            errors = immutableMap(errors),
            snapshots = immutableMap(orderedSnapshots),
        )
    }

    private fun immutableSnapshot(snapshot: LibrarySnapshot): LibrarySnapshot {
        val groups = snapshot.document.groups.map(::immutableGroup)
        val items = snapshot.document.items.map(::immutableItem)
        return snapshot.copy(
            sourceFile = snapshot.sourceFile.toAbsolutePath().normalize(),
            document = snapshot.document.copy(
                groups = immutableList(groups),
                items = immutableList(items),
            ),
        )
    }

    private fun immutableGroup(group: LibraryGroup): LibraryGroup = group.copy()

    private fun immutableItem(item: LibraryItem): LibraryItem = item.copy(
        tags = Collections.unmodifiableSet(LinkedHashSet(item.tags)),
    )

    private fun immutableError(error: LibraryLoadError): LibraryLoadError = error.copy(
        sourceFile = error.sourceFile.toAbsolutePath().normalize(),
    )

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))

    private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
        Collections.unmodifiableMap(LinkedHashMap(values))

    private companion object {
        fun productionRepositories(project: Project): Map<LibraryScope, LibraryRepository> {
            val basePath = requireNotNull(project.basePath) {
                "Project base path is required for library configuration"
            }
            val paths = LibraryRepositoryPaths(
                projectBase = Path.of(basePath),
                configDir = PathManager.getConfigDir(),
                systemDir = PathManager.getSystemDir(),
            )
            val codec = LibraryJsonCodec()
            val lastValidStore = LibraryLastValidStore(
                codec = codec,
                writer = AtomicFileWriter(),
                cacheRoot = paths.lastValidCacheRoot,
            )
            return LibraryScope.entries.associateWith { libraryScope ->
                FileLibraryRepository(
                    scope = libraryScope,
                    root = paths.root(libraryScope).toAbsolutePath().normalize(),
                    codec = codec,
                    lastValidStore = lastValidStore,
                )
            }
        }
    }
}

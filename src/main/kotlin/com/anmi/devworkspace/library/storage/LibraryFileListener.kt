package com.anmi.devworkspace.library.storage

import com.anmi.devworkspace.library.domain.LibraryScope
import com.intellij.openapi.Disposable
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryWatchedPaths(
    val index: Path,
    val contents: Path,
)

/** Controllable debounce boundary used to keep hot-reload tests free of sleeps. */
interface LibraryDebouncer : Disposable {
    fun submit(action: suspend () -> Unit)
}

class CoroutineLibraryDebouncer(
    scope: CoroutineScope,
    private val delayMillis: Long = 400,
) : LibraryDebouncer {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val debounceScope = CoroutineScope(scope.coroutineContext + job)
    private var pending: Job? = null

    @Synchronized
    override fun submit(action: suspend () -> Unit) {
        pending?.cancel()
        pending = debounceScope.launch {
            delay(delayMillis)
            action()
        }
    }

    @Synchronized
    override fun dispose() {
        pending?.cancel()
        pending = null
        debounceScope.cancel()
    }
}

/**
 * IntelliJ VFS adapter for the three indexes and their Markdown directories.
 *
 * Changed scopes accumulate across the debounce window. Plugin-originated
 * writes are identified by the final content hash; a mismatch is treated as
 * an external edit rather than suppressing a possibly important reload.
 */
class LibraryFileListener(
    watchedPaths: Map<LibraryScope, LibraryWatchedPaths>,
    scope: CoroutineScope,
    private val debouncer: LibraryDebouncer = CoroutineLibraryDebouncer(scope),
    private val contentHash: suspend (Path) -> String? = ::readContentHash,
    private val reload: suspend (Set<LibraryScope>) -> Unit,
) : BulkFileListener, Disposable {
    private val watched = watchedPaths.mapValues { (_, paths) ->
        LibraryWatchedPaths(normalize(paths.index), normalize(paths.contents))
    }
    private val expectedPluginWrites = ConcurrentHashMap<Path, String>()
    private val pendingLock = Any()
    private val pendingChanges = LinkedHashMap<Path, LibraryScope>()
    @Volatile
    private var disposed = false

    override fun after(events: List<VFileEvent>) {
        pathsChanged(events.mapNotNull { event ->
            try {
                Path.of(event.path)
            } catch (_: InvalidPathException) {
                null
            }
        })
    }

    internal fun pathsChanged(changedPaths: Iterable<Path>) {
        if (disposed) return
        val relevant = changedPaths.mapNotNull { rawPath ->
            val path = normalize(rawPath)
            scopeFor(path)?.let { path to it }
        }
        if (relevant.isEmpty()) return

        synchronized(pendingLock) {
            relevant.forEach { (path, changedScope) -> pendingChanges[path] = changedScope }
        }
        debouncer.submit { processPendingChanges() }
    }

    internal fun expectPluginWrite(path: Path, contentHash: String) {
        if (!disposed) expectedPluginWrites[normalize(path)] = contentHash
    }

    override fun dispose() {
        disposed = true
        synchronized(pendingLock) {
            pendingChanges.clear()
        }
        expectedPluginWrites.clear()
        debouncer.dispose()
    }

    private suspend fun processPendingChanges() {
        val changes = synchronized(pendingLock) {
            LinkedHashMap(pendingChanges).also { pendingChanges.clear() }
        }
        if (disposed || changes.isEmpty()) return

        val externalScopes = linkedSetOf<LibraryScope>()
        changes.forEach { (path, changedScope) ->
            val expected = expectedPluginWrites.remove(path)
            if (expected == null || hashSafely(path) != expected) {
                externalScopes += changedScope
            }
        }
        if (externalScopes.isNotEmpty() && !disposed) reload(externalScopes)
    }

    private suspend fun hashSafely(path: Path): String? =
        try {
            withContext(Dispatchers.IO) { contentHash(path) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    private fun scopeFor(path: Path): LibraryScope? =
        watched.entries.firstOrNull { (_, paths) ->
            path == paths.index || path.startsWith(paths.contents)
        }?.key

    private companion object {
        fun normalize(path: Path): Path = path.toAbsolutePath().normalize()

        suspend fun readContentHash(path: Path): String? =
            try {
                MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path))
                    .joinToString("") { "%02x".format(it) }
            } catch (_: IOException) {
                null
            }
    }
}

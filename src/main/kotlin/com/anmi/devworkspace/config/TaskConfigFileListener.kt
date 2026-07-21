package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.TaskScope
import com.intellij.openapi.Disposable
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal interface TaskConfigDebouncer : Disposable {
    fun submit(action: suspend () -> Unit)
}

internal class CoroutineTaskConfigDebouncer(
    scope: CoroutineScope,
    private val delayMillis: Long = 400,
) : TaskConfigDebouncer {
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

internal class TaskConfigFileListener(
    watchedPaths: Map<TaskScope, Path>,
    scope: CoroutineScope,
    private val debouncer: TaskConfigDebouncer = CoroutineTaskConfigDebouncer(scope),
    private val contentHash: suspend (Path) -> String? = ::readContentHash,
    private val reload: suspend () -> Unit,
) : BulkFileListener, Disposable {
    private val watched = watchedPaths.values.mapTo(mutableSetOf(), ::normalize)
    private val expectedPluginWrites = ConcurrentHashMap<Path, String>()

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
        changedPaths.asSequence()
            .map(::normalize)
            .filter(watched::contains)
            .forEach { changedPath ->
                debouncer.submit {
                    val expectedHash = expectedPluginWrites[changedPath]
                    if (expectedHash != null) {
                        val actualHash = try {
                            withContext(Dispatchers.IO) { contentHash(changedPath) }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            null
                        }
                        expectedPluginWrites.remove(changedPath, expectedHash)
                        if (actualHash == expectedHash) return@submit
                    }
                    reload()
                }
            }
    }

    internal fun expectPluginWrite(path: Path, contentHash: String) {
        expectedPluginWrites[normalize(path)] = contentHash
    }

    override fun dispose() {
        expectedPluginWrites.clear()
        debouncer.dispose()
    }

    private companion object {
        fun normalize(path: Path): Path = path.toAbsolutePath().normalize()

        suspend fun readContentHash(path: Path): String? = try {
            val content = Files.readString(path, StandardCharsets.UTF_8)
            MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        } catch (_: IOException) {
            null
        }
    }
}

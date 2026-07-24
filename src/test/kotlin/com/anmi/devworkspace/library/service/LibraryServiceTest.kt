package com.anmi.devworkspace.library.service

import com.anmi.devworkspace.library.domain.LibraryGroup
import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.storage.LibraryDebouncer
import com.anmi.devworkspace.library.storage.LibraryDocument
import com.anmi.devworkspace.library.storage.LibraryFileListener
import com.anmi.devworkspace.library.storage.LibraryLoadError
import com.anmi.devworkspace.library.storage.LibraryLoadResult
import com.anmi.devworkspace.library.storage.LibraryRepository
import com.anmi.devworkspace.library.storage.LibrarySnapshot
import com.anmi.devworkspace.library.storage.LibraryWatchedPaths
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LibraryServiceTest {
    @Test
    fun `manual refresh reloads every scope without saving`() = runBlocking {
        val repositories = repositories()
        val service = LibraryService(CoroutineScope(coroutineContext), repositories.values.toList())

        service.refresh()

        assertEquals(1, repositories.values.minOf { it.loadCount })
        assertEquals(1, repositories.values.maxOf { it.loadCount })
        assertEquals(0, repositories.values.sumOf { it.saveCount })
        service.dispose()
    }

    @Test
    fun `all scopes merge without overriding same IDs or titles`() = runBlocking {
        val repositories = LibraryScope.entries.map { scope ->
            FakeRepository(
                scope,
                success(
                    scope,
                    LibraryDocument(
                        groups = listOf(group(scope, "shared-group", " Docs ")),
                        items = listOf(item(scope, "same-id", "Same title")),
                    ),
                ),
            )
        }
        val service = LibraryService(CoroutineScope(coroutineContext), repositories)

        service.reload()

        assertEquals(3, service.state.value.items.size)
        assertEquals(LibraryScope.entries.toSet(), service.state.value.items.map { it.scope }.toSet())
        assertEquals(3, service.state.value.groups.size)
        assertEquals(3, service.state.value.snapshots.size)
        service.dispose()
    }

    @Test
    fun `normalized group names remain separate domain records`() = runBlocking {
        val repositories = repositories()
        repositories.getValue(LibraryScope.GLOBAL).result = success(
            LibraryScope.GLOBAL,
            LibraryDocument(groups = listOf(group(LibraryScope.GLOBAL, "global", "文档"))),
        )
        repositories.getValue(LibraryScope.PROJECT_SHARED).result = success(
            LibraryScope.PROJECT_SHARED,
            LibraryDocument(groups = listOf(group(LibraryScope.PROJECT_SHARED, "shared", "  文档  "))),
        )
        val service = LibraryService(CoroutineScope(coroutineContext), repositories.values.toList())

        service.reload()

        assertEquals(listOf("文档", "  文档  "), service.state.value.groups.map { it.name })
        service.dispose()
    }

    @Test
    fun `failed scope retains its snapshot and repaired scope clears error`() = runBlocking {
        val repositories = repositories()
        val shared = repositories.getValue(LibraryScope.PROJECT_SHARED)
        shared.result = success(
            shared.scope,
            LibraryDocument(items = listOf(item(shared.scope, "retained", "Retained"))),
        )
        val service = LibraryService(CoroutineScope(coroutineContext), repositories.values.toList())
        service.reload()

        shared.result = LibraryLoadResult.Failure(
            LibraryLoadError(shared.path, "资料库索引格式无效"),
        )
        service.reload(shared.scope)

        assertEquals("retained", service.state.value.items.single().id)
        assertTrue(shared.scope in service.state.value.errors)

        shared.result = success(
            shared.scope,
            LibraryDocument(items = listOf(item(shared.scope, "repaired", "Repaired"))),
        )
        service.reload(shared.scope)

        assertEquals("repaired", service.state.value.items.single().id)
        assertFalse(shared.scope in service.state.value.errors)
        service.dispose()
    }

    @Test
    fun `recovered scope publishes fallback and its current error`() = runBlocking {
        val repositories = repositories()
        val shared = repositories.getValue(LibraryScope.PROJECT_SHARED)
        val fallback = snapshot(
            shared.scope,
            LibraryDocument(items = listOf(item(shared.scope, "fallback", "Fallback"))),
        )
        shared.result = LibraryLoadResult.Recovered(
            fallback,
            LibraryLoadError(shared.path, "资料库索引格式无效"),
        )
        val service = LibraryService(CoroutineScope(coroutineContext), repositories.values.toList())

        service.reload()

        assertEquals("fallback", service.state.value.items.single().id)
        assertTrue(shared.scope in service.state.value.errors)
        service.dispose()
    }

    @Test
    fun `service requires one repository for every scope`() {
        assertFailsWith<IllegalArgumentException> {
            LibraryService(
                CoroutineScope(Job()),
                listOf(FakeRepository(LibraryScope.GLOBAL, success(LibraryScope.GLOBAL))),
            )
        }
    }

    @Test
    fun `external changes debounce into one reload without sleeping`() = runBlocking {
        val debouncer = FakeDebouncer()
        val watched = watchedPaths()
        val reloaded = mutableListOf<Set<LibraryScope>>()
        val listener = LibraryFileListener(
            watchedPaths = watched,
            scope = CoroutineScope(coroutineContext),
            debouncer = debouncer,
            contentHash = { "external" },
            reload = { reloaded += it },
        )

        listener.pathsChanged(
            listOf(
                watched.getValue(LibraryScope.GLOBAL).index,
                watched.getValue(LibraryScope.PROJECT_SHARED).contents.resolve("note.md"),
                Path.of("D:/unrelated/library.json"),
            ),
        )
        listener.pathsChanged(listOf(watched.getValue(LibraryScope.PROJECT_PRIVATE).index))

        assertEquals(2, debouncer.submissions)
        assertTrue(reloaded.isEmpty())
        debouncer.flush()
        assertEquals(
            setOf(LibraryScope.GLOBAL, LibraryScope.PROJECT_SHARED, LibraryScope.PROJECT_PRIVATE),
            reloaded.single(),
        )
        listener.dispose()
    }

    @Test
    fun `matching plugin write is consumed without reload loop`() = runBlocking {
        val debouncer = FakeDebouncer()
        val watched = watchedPaths()
        var reloads = 0
        val listener = LibraryFileListener(
            watchedPaths = watched,
            scope = CoroutineScope(coroutineContext),
            debouncer = debouncer,
            contentHash = { "expected-hash" },
            reload = { reloads++ },
        )
        val path = watched.getValue(LibraryScope.PROJECT_SHARED).index
        listener.expectPluginWrite(path, "expected-hash")

        listener.pathsChanged(listOf(path))
        debouncer.flush()

        assertEquals(0, reloads)
        listener.dispose()
    }

    @Test
    fun `mismatched plugin write is treated as external`() = runBlocking {
        val debouncer = FakeDebouncer()
        val watched = watchedPaths()
        var scopes = emptySet<LibraryScope>()
        val listener = LibraryFileListener(
            watchedPaths = watched,
            scope = CoroutineScope(coroutineContext),
            debouncer = debouncer,
            contentHash = { "different-hash" },
            reload = { scopes = it },
        )
        val path = watched.getValue(LibraryScope.GLOBAL).index
        listener.expectPluginWrite(path, "expected-hash")

        listener.pathsChanged(listOf(path))
        debouncer.flush()

        assertEquals(setOf(LibraryScope.GLOBAL), scopes)
        listener.dispose()
    }

    @Test
    fun `disposing listener cancels pending debounce`() {
        val debouncer = FakeDebouncer()
        val watched = watchedPaths()
        val listener = LibraryFileListener(
            watchedPaths = watched,
            scope = CoroutineScope(Job()),
            debouncer = debouncer,
            reload = {},
        )
        listener.pathsChanged(listOf(watched.getValue(LibraryScope.GLOBAL).index))

        listener.dispose()

        assertTrue(debouncer.disposed)
        assertFalse(debouncer.hasPending)
    }

    private fun repositories(): Map<LibraryScope, FakeRepository> =
        LibraryScope.entries.associateWith { scope ->
            FakeRepository(scope, success(scope))
        }

    private fun success(
        scope: LibraryScope,
        document: LibraryDocument = LibraryDocument(),
    ): LibraryLoadResult.Success = LibraryLoadResult.Success(snapshot(scope, document))

    private fun snapshot(scope: LibraryScope, document: LibraryDocument) = LibrarySnapshot(
        scope = scope,
        document = document,
        sourceFile = Path.of("D:/${scope.name.lowercase()}/library.json"),
        contentHash = "hash-${scope.name}-${document.items.size}-${document.groups.size}",
        loadedAt = Instant.EPOCH,
    )

    private fun group(scope: LibraryScope, id: String, name: String) = LibraryGroup(
        id = id,
        name = name,
        description = null,
        order = 0,
        scope = scope,
    )

    private fun item(scope: LibraryScope, id: String, title: String) = LibraryItem(
        id = id,
        title = title,
        type = LibraryItemType.LINK,
        scope = scope,
        groupId = null,
        tags = emptySet(),
        note = null,
        favorite = false,
        target = "https://example.com/$id",
        contentFile = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun watchedPaths(): Map<LibraryScope, LibraryWatchedPaths> =
        LibraryScope.entries.associateWith { scope ->
            val root = Path.of("D:/library/${scope.name.lowercase()}")
            LibraryWatchedPaths(root.resolve("library.json"), root.resolve("contents"))
        }

    private class FakeRepository(
        override val scope: LibraryScope,
        var result: LibraryLoadResult,
    ) : LibraryRepository {
        override val root: Path = Path.of("D:/${scope.name.lowercase()}")
        override val path: Path = root.resolve("library.json")
        var loadCount: Int = 0
            private set
        var saveCount: Int = 0
            private set

        override suspend fun load(): LibraryLoadResult {
            loadCount++
            return result
        }

        override suspend fun save(document: LibraryDocument): LibrarySnapshot {
            saveCount++
            val saved = LibrarySnapshot(
                scope = scope,
                document = document,
                sourceFile = path,
                contentHash = "saved-${document.items.size}-${document.groups.size}",
                loadedAt = Instant.EPOCH,
            )
            result = LibraryLoadResult.Success(saved)
            return saved
        }
    }

    private class FakeDebouncer : LibraryDebouncer {
        private var action: (suspend () -> Unit)? = null
        var submissions: Int = 0
            private set
        var disposed: Boolean = false
            private set
        val hasPending: Boolean get() = action != null

        override fun submit(action: suspend () -> Unit) {
            submissions++
            this.action = action
        }

        suspend fun flush() {
            val pending = action
            action = null
            pending?.invoke()
        }

        override fun dispose() {
            disposed = true
            action = null
        }
    }
}

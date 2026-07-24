package com.anmi.devworkspace.library.service

import com.anmi.devworkspace.config.AtomicFileWriter
import com.anmi.devworkspace.library.domain.LibraryGroup
import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.storage.LibraryDocument
import com.anmi.devworkspace.library.storage.MarkdownContentStore
import com.anmi.devworkspace.library.ui.LibraryEditorState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Transaction boundary for index metadata and plugin-owned Markdown bodies.
 *
 * External targets are references only. A scope move creates the destination
 * record before removing the source so an interruption cannot lose metadata.
 */
@Service(Service.Level.PROJECT)
class LibraryMutationService(
    private val project: Project,
) {
    private val library: LibraryService = project.service()
    private val mutex = Mutex()
    private val clock: Clock = Clock.systemUTC()

    suspend fun create(state: LibraryEditorState): LibraryItem = mutex.withLock {
        val now = Instant.now(clock)
        val id = state.id ?: UUID.randomUUID().toString()
        val item = state.toItem(id, now, now)
        writeMarkdown(item, state.markdown)
        library.save(item.scope, document(item.scope).withItem(item))
        item
    }

    suspend fun update(original: LibraryItem, state: LibraryEditorState): LibraryItem = mutex.withLock {
        val updated = state.toItem(original.id, original.createdAt, Instant.now(clock))
        if (original.scope == updated.scope) {
            writeMarkdown(updated, state.markdown)
            library.save(updated.scope, document(updated.scope).replaceItem(updated))
        } else {
            // Destination-first keeps at least one durable definition throughout the move.
            writeMarkdown(updated, state.markdown)
            library.save(updated.scope, document(updated.scope).withItem(updated))
            library.save(original.scope, document(original.scope).withoutItem(original.id))
            contentStore(original.scope).delete(original)
        }
        updated
    }

    suspend fun delete(item: LibraryItem) = mutex.withLock {
        library.save(item.scope, document(item.scope).withoutItem(item.id))
        contentStore(item.scope).delete(item)
    }

    suspend fun toggleFavorite(item: LibraryItem): LibraryItem = mutex.withLock {
        val updated = item.copy(favorite = !item.favorite, updatedAt = Instant.now(clock))
        library.save(item.scope, document(item.scope).replaceItem(updated))
        updated
    }

    suspend fun relocate(item: LibraryItem, target: String): LibraryItem = mutex.withLock {
        require(item.type != LibraryItemType.MARKDOWN && item.type != LibraryItemType.LINK) {
            "Only file-backed library entries can be relocated"
        }
        val updated = item.copy(target = target, updatedAt = Instant.now(clock))
        library.save(item.scope, document(item.scope).replaceItem(updated))
        updated
    }

    suspend fun saveGroup(group: LibraryGroup) = mutex.withLock {
        val document = document(group.scope)
        val groups = document.groups.filterNot { it.id == group.id } + group
        library.save(group.scope, document.copy(groups = groups))
    }

    suspend fun deleteGroup(group: LibraryGroup) = mutex.withLock {
        val document = document(group.scope)
        library.save(
            group.scope,
            document.copy(
                groups = document.groups.filterNot { it.id == group.id },
                items = document.items.map { item ->
                    if (item.groupId == group.id) item.copy(groupId = null) else item
                },
            ),
        )
    }

    private fun document(scope: LibraryScope): LibraryDocument =
        library.state.value.snapshots[scope]?.document ?: LibraryDocument()

    private suspend fun writeMarkdown(item: LibraryItem, body: String?) {
        if (item.type == LibraryItemType.MARKDOWN) {
            contentStore(item.scope).write(item.id, body.orEmpty())
        }
    }

    private fun contentStore(scope: LibraryScope): MarkdownContentStore =
        MarkdownContentStore(library.path(scope).parent, AtomicFileWriter())

    private fun LibraryEditorState.toItem(
        itemId: String,
        created: Instant,
        updated: Instant,
    ): LibraryItem =
        LibraryItem(
            id = itemId,
            title = title,
            type = type,
            scope = scope,
            groupId = groupId,
            tags = tags,
            note = note,
            favorite = favorite,
            target = target,
            contentFile = if (type == LibraryItemType.MARKDOWN) "contents/$itemId.md" else null,
            createdAt = created,
            updatedAt = updated,
        )

    private fun LibraryDocument.withItem(item: LibraryItem): LibraryDocument =
        copy(items = items.filterNot { it.id == item.id } + item)

    private fun LibraryDocument.replaceItem(item: LibraryItem): LibraryDocument =
        copy(items = items.map { existing -> if (existing.id == item.id) item else existing })

    private fun LibraryDocument.withoutItem(id: String): LibraryDocument =
        copy(items = items.filterNot { it.id == id })
}

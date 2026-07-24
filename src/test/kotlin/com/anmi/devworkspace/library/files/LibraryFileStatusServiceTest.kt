package com.anmi.devworkspace.library.files

import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.search.LibraryPathState
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryFileStatusServiceTest {
    private val project = Path.of("D:/workspace/project").toAbsolutePath().normalize()
    private val existing = project.resolve("exists.txt")
    private val service = LibraryFileStatusService(
        resolver = LibraryPathResolver(project),
        exists = { it == existing },
    )

    @Test
    fun `checks file states without changing missing item`() = runBlocking {
        val available = item("available", LibraryItemType.FILE, "\${PROJECT_DIR}/exists.txt")
        val missing = item("missing", LibraryItemType.IMAGE, "\${PROJECT_DIR}/missing.png")

        assertEquals(LibraryPathState.AVAILABLE, service.check(available))
        assertEquals(LibraryPathState.MISSING, service.check(missing))
        assertEquals("\${PROJECT_DIR}/missing.png", missing.target)
    }

    @Test
    fun `links and Markdown do not have filesystem state`() = runBlocking {
        assertEquals(
            LibraryPathState.NOT_APPLICABLE,
            service.check(item("link", LibraryItemType.LINK, "https://example.com")),
        )
        assertEquals(
            LibraryPathState.NOT_APPLICABLE,
            service.check(item("notes", LibraryItemType.MARKDOWN, null)),
        )
    }

    @Test
    fun `refresh keys duplicate IDs by scope`() = runBlocking {
        val global = item("same", LibraryItemType.FILE, "\${PROJECT_DIR}/exists.txt", LibraryScope.GLOBAL)
        val shared = item("same", LibraryItemType.FILE, "\${PROJECT_DIR}/missing.txt", LibraryScope.PROJECT_SHARED)

        val states = service.refresh(listOf(global, shared))

        assertEquals(LibraryPathState.AVAILABLE, states.getValue(LibraryItemKey(global.scope, global.id)))
        assertEquals(LibraryPathState.MISSING, states.getValue(LibraryItemKey(shared.scope, shared.id)))
    }

    private fun item(
        id: String,
        type: LibraryItemType,
        target: String?,
        scope: LibraryScope = LibraryScope.PROJECT_SHARED,
    ) = LibraryItem(
        id = id,
        title = id,
        type = type,
        scope = scope,
        groupId = null,
        tags = emptySet(),
        note = null,
        favorite = false,
        target = target,
        contentFile = if (type == LibraryItemType.MARKDOWN) "contents/$id.md" else null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}

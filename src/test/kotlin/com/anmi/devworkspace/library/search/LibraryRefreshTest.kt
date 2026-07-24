package com.anmi.devworkspace.library.search

import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryItemSource
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.domain.LibrarySourceKind
import com.anmi.devworkspace.library.service.LibraryState
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryRefreshTest {
    @Test
    fun `refresh rereads external Markdown body without index change`() = runBlocking {
        var body = "old"
        val loader = LibraryRecordLoader(
            readMarkdown = { _, _ -> body },
            pathState = { LibraryPathState.NOT_APPLICABLE },
        )
        val state = LibraryState(items = listOf(item("note", LibraryItemType.MARKDOWN)))

        assertEquals("old", loader.load(state).single().markdownBody)
        body = "externally changed"

        assertEquals("externally changed", loader.load(state).single().markdownBody)
    }

    @Test
    fun `refresh observes external target deletion without mutating item`() = runBlocking {
        val target = Files.createTempFile("dev-library-refresh", ".txt")
        try {
            val original = item("file", LibraryItemType.FILE, target.toString())
            val loader = LibraryRecordLoader(
                readMarkdown = { _, _ -> null },
                pathState = {
                    if (Files.exists(target)) LibraryPathState.AVAILABLE else LibraryPathState.MISSING
                },
            )
            val state = LibraryState(items = listOf(original))

            assertEquals(LibraryPathState.AVAILABLE, loader.load(state).single().pathState)
            Files.delete(target)
            assertEquals(LibraryPathState.MISSING, loader.load(state).single().pathState)
            assertEquals(original, state.items.single())
        } finally {
            Files.deleteIfExists(target)
        }
    }

    @Test
    fun `refresh observes external structured source deletion`() = runBlocking {
        val source = Files.createTempFile("dev-library-source-refresh", ".kt")
        try {
            val item = item("snippet", LibraryItemType.MARKDOWN).copy(
                source = LibraryItemSource(
                    kind = LibrarySourceKind.EDITOR_SELECTION,
                    path = source.toString(),
                    startLine = 1,
                    endLine = 1,
                ),
            )
            val loader = LibraryRecordLoader(
                readMarkdown = { _, _ -> "body" },
                pathState = { LibraryPathState.NOT_APPLICABLE },
                sourcePathState = {
                    if (Files.exists(source)) LibraryPathState.AVAILABLE else LibraryPathState.MISSING
                },
            )

            assertEquals(
                LibraryPathState.AVAILABLE,
                loader.load(LibraryState(items = listOf(item))).single().sourcePathState,
            )
            Files.delete(source)
            assertEquals(
                LibraryPathState.MISSING,
                loader.load(LibraryState(items = listOf(item))).single().sourcePathState,
            )
        } finally {
            Files.deleteIfExists(source)
        }
    }

    private fun item(
        id: String,
        type: LibraryItemType,
        target: String? = null,
    ) = LibraryItem(
        id = id,
        title = id,
        type = type,
        scope = LibraryScope.PROJECT_PRIVATE,
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

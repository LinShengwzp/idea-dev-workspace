package com.anmi.devworkspace.library.open

import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.search.LibraryPathState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryItemOpenerTest {
    private val decisions = LibraryOpenDecisionEngine()

    @Test
    fun `links use browser and Markdown uses preview`() {
        assertEquals(
            LibraryOpenAction.BROWSER,
            decisions.decide(item(LibraryItemType.LINK), LibraryPathState.NOT_APPLICABLE).action,
        )
        assertEquals(
            LibraryOpenAction.MARKDOWN_PREVIEW,
            decisions.decide(item(LibraryItemType.MARKDOWN), LibraryPathState.NOT_APPLICABLE).action,
        )
    }

    @Test
    fun `readable files use IDEA and unsupported files use system`() {
        assertEquals(
            LibraryOpenAction.IDEA_EDITOR,
            decisions.decide(
                item(LibraryItemType.FILE),
                LibraryPathState.AVAILABLE,
                ideaReadable = true,
            ).action,
        )
        assertEquals(
            LibraryOpenAction.SYSTEM,
            decisions.decide(
                item(LibraryItemType.FILE),
                LibraryPathState.AVAILABLE,
                ideaReadable = false,
            ).action,
        )
        assertEquals(
            LibraryOpenAction.SYSTEM,
            decisions.decide(item(LibraryItemType.MEDIA), LibraryPathState.AVAILABLE).action,
        )
    }

    @Test
    fun `image defaults to preview and supports explicit IDEA or system choice`() {
        val image = item(LibraryItemType.IMAGE)

        assertEquals(
            LibraryOpenAction.IMAGE_PREVIEW,
            decisions.decide(image, LibraryPathState.AVAILABLE).action,
        )
        assertEquals(
            LibraryOpenAction.IDEA_EDITOR,
            decisions.decide(
                image,
                LibraryPathState.AVAILABLE,
                preference = LibraryOpenPreference.IDEA,
            ).action,
        )
        assertEquals(
            LibraryOpenAction.SYSTEM,
            decisions.decide(
                image,
                LibraryPathState.AVAILABLE,
                preference = LibraryOpenPreference.SYSTEM,
            ).action,
        )
    }

    @Test
    fun `missing target is never opened`() {
        LibraryItemType.entries
            .filter { it == LibraryItemType.FILE || it == LibraryItemType.IMAGE || it == LibraryItemType.MEDIA }
            .forEach { type ->
                assertEquals(
                    LibraryOpenAction.MISSING,
                    decisions.decide(item(type), LibraryPathState.MISSING, ideaReadable = true).action,
                )
            }
    }

    private fun item(type: LibraryItemType) = LibraryItem(
        id = type.name.lowercase(),
        title = type.name,
        type = type,
        scope = LibraryScope.PROJECT_SHARED,
        groupId = null,
        tags = emptySet(),
        note = null,
        favorite = false,
        target = when (type) {
            LibraryItemType.LINK -> "https://example.com"
            LibraryItemType.MARKDOWN -> null
            else -> "D:/files/item.bin"
        },
        contentFile = if (type == LibraryItemType.MARKDOWN) "contents/markdown.md" else null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}

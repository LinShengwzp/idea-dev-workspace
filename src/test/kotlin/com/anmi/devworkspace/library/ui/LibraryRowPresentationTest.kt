package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.search.LibraryPathState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryRowPresentationTest {
    private val model = LibraryRowPresentationModel()

    @Test
    fun `each library type has a distinct icon kind`() {
        val mappings = LibraryItemType.entries.associateWith { type ->
            model.present(row(type = type)).iconKind
        }

        assertEquals(
            mapOf(
                LibraryItemType.MARKDOWN to LibraryIconKind.MARKDOWN,
                LibraryItemType.LINK to LibraryIconKind.LINK,
                LibraryItemType.FILE to LibraryIconKind.FILE,
                LibraryItemType.IMAGE to LibraryIconKind.IMAGE,
                LibraryItemType.MEDIA to LibraryIconKind.MEDIA,
            ),
            mappings,
        )
    }

    @Test
    fun `missing target and source produce independent warning state`() {
        assertEquals(
            LibraryRowWarning.TARGET_MISSING,
            model.present(row(pathState = LibraryPathState.MISSING)).warning,
        )
        assertEquals(
            LibraryRowWarning.SOURCE_MISSING,
            model.present(row(sourcePathState = LibraryPathState.MISSING)).warning,
        )
        assertEquals(
            LibraryRowWarning.TARGET_AND_SOURCE_MISSING,
            model.present(
                row(
                    pathState = LibraryPathState.MISSING,
                    sourcePathState = LibraryPathState.MISSING,
                ),
            ).warning,
        )
    }

    @Test
    fun `metadata retains scope group and tags while note wins summary`() {
        val presentation = model.present(
            row(
                groupName = "Docs",
                note = "  Read this first  ",
                markdownBody = "ignored body",
                tags = setOf("kotlin", "jvm"),
            ),
        )

        assertEquals(LibraryScope.PROJECT_PRIVATE, presentation.scope)
        assertEquals("Docs", presentation.groupName)
        assertEquals(listOf("jvm", "kotlin"), presentation.tags)
        assertEquals("Read this first", presentation.summary)
    }

    @Test
    fun `markdown body becomes compact bounded summary when note is absent`() {
        val presentation = model.present(
            row(
                markdownBody = "first\n\nsecond   third " + "x".repeat(200),
            ),
        )

        assertEquals("first second third " + "x".repeat(101) + "…", presentation.summary)
    }

    private fun row(
        type: LibraryItemType = LibraryItemType.MARKDOWN,
        groupName: String? = null,
        note: String? = null,
        markdownBody: String? = null,
        tags: Set<String> = emptySet(),
        pathState: LibraryPathState = LibraryPathState.NOT_APPLICABLE,
        sourcePathState: LibraryPathState = LibraryPathState.NOT_APPLICABLE,
    ): LibraryListItem = LibraryListItem(
        item = LibraryItem(
            id = "item",
            title = "Item",
            type = type,
            scope = LibraryScope.PROJECT_PRIVATE,
            groupId = groupName?.let { "group" },
            tags = tags,
            note = note,
            favorite = true,
            target = if (type == LibraryItemType.MARKDOWN) null else "D:/files/item.txt",
            contentFile = if (type == LibraryItemType.MARKDOWN) "contents/item.md" else null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        ),
        groupName = groupName,
        groupScope = groupName?.let { LibraryScope.PROJECT_PRIVATE },
        markdownBody = markdownBody,
        pathState = pathState,
        sourcePathState = sourcePathState,
    )
}

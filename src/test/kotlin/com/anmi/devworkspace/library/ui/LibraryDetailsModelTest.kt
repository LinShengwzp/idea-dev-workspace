package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemSource
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.domain.LibrarySourceKind
import com.anmi.devworkspace.library.search.LibraryPathState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryDetailsModelTest {
    private val model = LibraryDetailsModel()

    @Test
    fun `details retain every field and explicitly identify ungrouped item`() {
        val source = LibraryItemSource(
            LibrarySourceKind.EDITOR_SELECTION,
            "\${PROJECT_DIR}/src/Main.kt",
            4,
            8,
        )
        val row = row(
            type = LibraryItemType.MARKDOWN,
            groupName = null,
            tags = setOf("kotlin", "jvm"),
            note = "Useful note",
            source = source,
            sourcePathState = LibraryPathState.AVAILABLE,
        )

        val details = model.present(row)

        assertEquals("Title", details.title)
        assertEquals(LibraryItemType.MARKDOWN, details.type)
        assertEquals(LibraryScope.PROJECT_PRIVATE, details.scope)
        assertEquals(null, details.groupName)
        assertEquals(true, details.ungrouped)
        assertEquals(listOf("jvm", "kotlin"), details.tags)
        assertEquals("Useful note", details.note)
        assertEquals(source, details.source)
        assertEquals(Instant.parse("2026-07-24T08:00:00Z"), details.createdAt)
        assertEquals(Instant.parse("2026-07-24T09:00:00Z"), details.updatedAt)
        assertEquals(LibraryDetailsStatus.AVAILABLE, details.status)
        assertEquals(LibraryPreviewMode.MARKDOWN, details.previewMode)
    }

    @Test
    fun `all item types select the expected target and preview mode`() {
        val expectations = mapOf(
            LibraryItemType.MARKDOWN to LibraryPreviewMode.MARKDOWN,
            LibraryItemType.LINK to LibraryPreviewMode.NONE,
            LibraryItemType.FILE to LibraryPreviewMode.NONE,
            LibraryItemType.IMAGE to LibraryPreviewMode.IMAGE,
            LibraryItemType.MEDIA to LibraryPreviewMode.NONE,
        )

        expectations.forEach { (type, preview) ->
            val details = model.present(row(type = type))
            assertEquals(preview, details.previewMode, type.name)
            assertEquals(type != LibraryItemType.MARKDOWN, details.target != null, type.name)
        }
    }

    @Test
    fun `missing target and source are combined in details status`() {
        assertEquals(
            LibraryDetailsStatus.TARGET_AND_SOURCE_MISSING,
            model.present(
                row(
                    pathState = LibraryPathState.MISSING,
                    sourcePathState = LibraryPathState.MISSING,
                ),
            ).status,
        )
    }

    private fun row(
        type: LibraryItemType = LibraryItemType.FILE,
        groupName: String? = "Docs",
        tags: Set<String> = emptySet(),
        note: String? = null,
        source: LibraryItemSource? = null,
        pathState: LibraryPathState = LibraryPathState.AVAILABLE,
        sourcePathState: LibraryPathState = LibraryPathState.NOT_APPLICABLE,
    ): LibraryListItem = LibraryListItem(
        item = LibraryItem(
            id = "item",
            title = "Title",
            type = type,
            scope = LibraryScope.PROJECT_PRIVATE,
            groupId = groupName?.let { "docs" },
            tags = tags,
            note = note,
            favorite = false,
            target = if (type == LibraryItemType.MARKDOWN) null else "D:/docs/target.txt",
            contentFile = if (type == LibraryItemType.MARKDOWN) "contents/item.md" else null,
            createdAt = Instant.parse("2026-07-24T08:00:00Z"),
            updatedAt = Instant.parse("2026-07-24T09:00:00Z"),
            source = source,
        ),
        groupName = groupName,
        groupScope = groupName?.let { LibraryScope.PROJECT_PRIVATE },
        pathState = pathState,
        sourcePathState = sourcePathState,
    )
}

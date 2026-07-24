package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.library.domain.LibraryGroup
import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.search.LibraryQuery
import com.anmi.devworkspace.library.search.LibrarySearchEngine
import com.anmi.devworkspace.library.search.LibrarySearchRecord
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryListModelTest {
    private val model = LibraryListModel(LibrarySearchEngine())

    @Test
    fun `builds searchable rows without merging same named groups`() {
        val sharedGroup = group("shared-guides", "指南", LibraryScope.PROJECT_SHARED, 2)
        val privateGroup = group("private-guides", "指南", LibraryScope.PROJECT_PRIVATE, 1)
        val shared = item("shared", "共享说明", LibraryScope.PROJECT_SHARED, sharedGroup.id)
        val private = item("private", "私有说明", LibraryScope.PROJECT_PRIVATE, privateGroup.id)

        val rows = model.present(
            records = listOf(
                LibrarySearchRecord(shared, groupName = sharedGroup.name, groupOrder = sharedGroup.order),
                LibrarySearchRecord(private, groupName = privateGroup.name, groupOrder = privateGroup.order),
            ),
            query = LibraryQuery(text = "说明"),
        )

        assertEquals(listOf("private", "shared"), rows.map { it.item.id })
        assertEquals(
            listOf(LibraryScope.PROJECT_PRIVATE, LibraryScope.PROJECT_SHARED),
            rows.map { it.groupScope },
        )
    }

    @Test
    fun `restores selection by scope and id after filtering`() {
        val first = LibraryListItem(item("same", "全局", LibraryScope.GLOBAL, null), null, null)
        val second = LibraryListItem(item("same", "私有", LibraryScope.PROJECT_PRIVATE, null), null, null)

        assertEquals(1, model.selectionIndex(listOf(first, second), second.key))
        assertEquals(-1, model.selectionIndex(listOf(first), second.key))
    }

    private fun group(id: String, name: String, scope: LibraryScope, order: Int) =
        LibraryGroup(id, name, null, order, scope)

    private fun item(id: String, title: String, scope: LibraryScope, groupId: String?) =
        LibraryItem(
            id = id,
            title = title,
            type = LibraryItemType.MARKDOWN,
            scope = scope,
            groupId = groupId,
            tags = emptySet(),
            note = null,
            favorite = false,
            target = null,
            contentFile = "contents/$id.md",
            createdAt = Instant.parse("2026-07-24T00:00:00Z"),
            updatedAt = Instant.parse("2026-07-24T00:00:00Z"),
        )
}

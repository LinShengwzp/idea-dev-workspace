package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.search.LibraryPathState
import com.anmi.devworkspace.library.search.LibrarySearchRecord
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryFilterStateTest {
    private val populated = LibraryFilterState(
        text = "kotlin",
        scopes = setOf(LibraryScope.GLOBAL),
        types = setOf(LibraryItemType.FILE),
        groupNames = setOf("docs"),
        includeUngrouped = true,
        tags = setOf("jvm"),
        favoriteOnly = true,
        pathStates = setOf(LibraryPathState.MISSING),
    )

    @Test
    fun `all resources resets text and every dimension`() {
        assertEquals(LibraryFilterState(), populated.clearAll())
    }

    @Test
    fun `per-dimension all entries clear only their dimension`() {
        assertEquals(emptySet(), populated.clearGroups().groupNames)
        assertFalse(populated.clearGroups().includeUngrouped)
        assertEquals(populated.types, populated.clearGroups().types)
        assertEquals(emptySet(), populated.clearScopes().scopes)
        assertEquals(populated.tags, populated.clearScopes().tags)
        assertEquals(emptySet(), populated.clearTypes().types)
        assertEquals(populated.scopes, populated.clearTypes().scopes)
        assertEquals(emptySet(), populated.clearTags().tags)
        assertEquals(populated.pathStates, populated.clearTags().pathStates)
        assertEquals(emptySet(), populated.clearPathStates().pathStates)
        assertTrue(populated.clearPathStates().favoriteOnly)
    }

    @Test
    fun `query keeps dimensions independent`() {
        val query = populated.toQuery()

        assertEquals(populated.scopes, query.scopes)
        assertEquals(populated.types, query.types)
        assertEquals(populated.groupNames, query.groupNames)
        assertTrue(query.includeUngrouped)
        assertEquals(populated.tags, query.tags)
        assertEquals(populated.pathStates, query.pathStates)
    }

    @Test
    fun `same-name groups across scopes become one visual option`() {
        val options = LibraryFilterOptions.from(
            listOf(
                record("global", LibraryScope.GLOBAL, " Docs "),
                record("private", LibraryScope.PROJECT_PRIVATE, "docs"),
                record("ungrouped", LibraryScope.PROJECT_SHARED, null),
            ),
        )

        assertEquals(mapOf("docs" to "Docs"), options.groupNames)
        assertTrue(options.hasUngrouped)
    }

    private fun record(id: String, scope: LibraryScope, groupName: String?): LibrarySearchRecord {
        val groupId = groupName?.let { "$id-group" }
        return LibrarySearchRecord(
            LibraryItem(
                id = id,
                title = id,
                type = LibraryItemType.FILE,
                scope = scope,
                groupId = groupId,
                tags = emptySet(),
                note = null,
                favorite = false,
                target = "D:/$id.txt",
                contentFile = null,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
            groupName = groupName,
        )
    }
}

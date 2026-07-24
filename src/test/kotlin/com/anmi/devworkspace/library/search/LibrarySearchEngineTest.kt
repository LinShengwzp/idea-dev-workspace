package com.anmi.devworkspace.library.search

import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibrarySearchEngineTest {
    private val engine = LibrarySearchEngine()

    @Test
    fun `search covers title body target tags note and group ignoring case`() {
        val fields = listOf(
            record(item("title", title = "Needle in title")),
            record(item("body"), markdownBody = "NEEDLE in Markdown"),
            record(item("target", target = "D:/Docs/Needle-Guide.pdf")),
            record(item("tag", tags = setOf("Needle"))),
            record(item("note", note = "Read the NEEDLE note")),
            record(item("group"), groupName = "Needle References"),
        )

        val result = engine.search(fields, LibraryQuery(text = "needle"))

        assertEquals(fields.mapTo(linkedSetOf()) { it.item.id }, result.mapTo(linkedSetOf()) { it.item.id })
    }

    @Test
    fun `different dimensions use AND and selections within dimension use OR`() {
        val records = listOf(
            record(
                item(
                    "global-image",
                    scope = LibraryScope.GLOBAL,
                    type = LibraryItemType.IMAGE,
                    tags = setOf("docs"),
                    favorite = true,
                ),
                pathState = LibraryPathState.AVAILABLE,
            ),
            record(
                item(
                    "shared-file",
                    scope = LibraryScope.PROJECT_SHARED,
                    type = LibraryItemType.FILE,
                    tags = setOf("docs"),
                    favorite = true,
                ),
                pathState = LibraryPathState.AVAILABLE,
            ),
            record(
                item(
                    "private-image",
                    scope = LibraryScope.PROJECT_PRIVATE,
                    type = LibraryItemType.IMAGE,
                    tags = setOf("other"),
                    favorite = true,
                ),
                pathState = LibraryPathState.AVAILABLE,
            ),
            record(
                item(
                    "not-favorite",
                    scope = LibraryScope.GLOBAL,
                    type = LibraryItemType.FILE,
                    tags = setOf("docs"),
                    favorite = false,
                ),
                pathState = LibraryPathState.AVAILABLE,
            ),
        )
        val query = LibraryQuery(
            scopes = setOf(LibraryScope.GLOBAL, LibraryScope.PROJECT_SHARED),
            types = setOf(LibraryItemType.IMAGE, LibraryItemType.FILE),
            tags = setOf("docs", "reference"),
            favoriteOnly = true,
            pathStates = setOf(LibraryPathState.AVAILABLE),
        )

        assertEquals(
            setOf("global-image", "shared-file"),
            engine.search(records, query).mapTo(linkedSetOf()) { it.item.id },
        )
    }

    @Test
    fun `tag filter is exact and case insensitive`() {
        val records = listOf(
            record(item("exact", tags = setOf("Kotlin"))),
            record(item("substring", tags = setOf("Kotlin/JVM"))),
        )

        assertEquals(
            listOf("exact"),
            engine.search(records, LibraryQuery(tags = setOf("kotlin"))).map { it.item.id },
        )
    }

    @Test
    fun `group and path-state filters are applied`() {
        val records = listOf(
            record(item("available"), groupId = "docs", pathState = LibraryPathState.AVAILABLE),
            record(item("missing"), groupId = "docs", pathState = LibraryPathState.MISSING),
            record(item("other"), groupId = "other", pathState = LibraryPathState.AVAILABLE),
            record(item("markdown", type = LibraryItemType.MARKDOWN), groupId = "docs"),
        )

        val result = engine.search(
            records,
            LibraryQuery(
                groupIds = setOf("docs"),
                pathStates = setOf(LibraryPathState.MISSING),
            ),
        )

        assertEquals(listOf("missing"), result.map { it.item.id })
    }

    @Test
    fun `default sort is favorite group order recent update then deterministic identity`() {
        val records = listOf(
            record(item("z", updatedAt = instant(3)), groupOrder = 20),
            record(item("a", updatedAt = instant(1)), groupOrder = 10),
            record(item("b", updatedAt = instant(2)), groupOrder = 10),
            record(item("favorite", favorite = true, updatedAt = instant(0)), groupOrder = 99),
            record(
                item("same", scope = LibraryScope.PROJECT_SHARED, updatedAt = instant(2)),
                groupOrder = 10,
            ),
            record(
                item("same", scope = LibraryScope.GLOBAL, updatedAt = instant(2)),
                groupOrder = 10,
            ),
        )

        assertEquals(
            listOf("favorite", "b", "same", "same", "a", "z"),
            engine.search(records, LibraryQuery()).map { it.item.id },
        )
        val sameScopes = engine.search(records, LibraryQuery())
            .filter { it.item.id == "same" }
            .map { it.item.scope }
        assertEquals(listOf(LibraryScope.GLOBAL, LibraryScope.PROJECT_SHARED), sameScopes)
    }

    @Test
    fun `alternate sorts use deterministic tie breakers`() {
        val records = listOf(
            record(item("z", title = "beta", type = LibraryItemType.MEDIA, createdAt = instant(1), updatedAt = instant(3))),
            record(item("a", title = "Alpha", type = LibraryItemType.FILE, createdAt = instant(2), updatedAt = instant(2))),
            record(item("b", title = "alpha", type = LibraryItemType.IMAGE, createdAt = instant(2), updatedAt = instant(2))),
        )

        assertEquals(listOf("a", "b", "z"), ids(records, LibrarySort.TITLE))
        assertEquals(listOf("a", "b", "z"), ids(records, LibrarySort.CREATED_AT))
        assertEquals(listOf("z", "a", "b"), ids(records, LibrarySort.UPDATED_AT))
        assertEquals(listOf("a", "b", "z"), ids(records, LibrarySort.TYPE))
    }

    @Test
    fun `new Markdown content is searched without an index hash change`() {
        val original = record(item("notes", type = LibraryItemType.MARKDOWN), markdownBody = "old body")
        assertTrue(engine.search(listOf(original), LibraryQuery(text = "external")).isEmpty())

        val externallyEdited = original.copy(markdownBody = "externally edited body")

        assertEquals(
            listOf("notes"),
            engine.search(listOf(externallyEdited), LibraryQuery(text = "external")).map { it.item.id },
        )
    }

    private fun ids(records: List<LibrarySearchRecord>, sort: LibrarySort): List<String> =
        engine.search(records, LibraryQuery(sort = sort)).map { it.item.id }

    private fun record(
        item: LibraryItem,
        groupId: String? = item.groupId,
        groupName: String? = null,
        groupOrder: Int = Int.MAX_VALUE,
        markdownBody: String? = null,
        pathState: LibraryPathState = LibraryPathState.NOT_APPLICABLE,
    ) = LibrarySearchRecord(
        item = item.copy(groupId = groupId),
        groupName = groupName,
        groupOrder = groupOrder,
        markdownBody = markdownBody,
        pathState = pathState,
    )

    private fun item(
        id: String,
        title: String = id,
        scope: LibraryScope = LibraryScope.GLOBAL,
        type: LibraryItemType = LibraryItemType.FILE,
        tags: Set<String> = emptySet(),
        note: String? = null,
        favorite: Boolean = false,
        target: String? = if (type == LibraryItemType.MARKDOWN) null else "D:/files/$id.txt",
        createdAt: Instant = Instant.EPOCH,
        updatedAt: Instant = Instant.EPOCH,
    ) = LibraryItem(
        id = id,
        title = title,
        type = type,
        scope = scope,
        groupId = null,
        tags = tags,
        note = note,
        favorite = favorite,
        target = target,
        contentFile = if (type == LibraryItemType.MARKDOWN) "contents/$id.md" else null,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun instant(seconds: Long): Instant = Instant.ofEpochSecond(seconds)
}

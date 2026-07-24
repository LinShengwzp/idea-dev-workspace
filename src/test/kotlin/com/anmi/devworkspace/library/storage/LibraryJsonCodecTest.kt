package com.anmi.devworkspace.library.storage

import com.anmi.devworkspace.library.domain.LibraryGroup
import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LibraryJsonCodecTest {
    private val codec = LibraryJsonCodec()
    private val createdAt = Instant.parse("2026-07-24T08:00:00Z")
    private val updatedAt = Instant.parse("2026-07-24T09:00:00Z")

    @Test
    fun `empty document round trips`() {
        val document = LibraryDocument()

        assertEquals(
            document,
            codec.decode(codec.encode(document), LibraryScope.PROJECT_SHARED),
        )
    }

    @Test
    fun `all item types round trip`() {
        val document = LibraryDocument(
            groups = listOf(group("references", order = 10)),
            items = LibraryItemType.entries.mapIndexed { index, type ->
                item(
                    id = "item-$index",
                    type = type,
                    target = if (type == LibraryItemType.MARKDOWN) null else "D:/references/item-$index",
                    contentFile = if (type == LibraryItemType.MARKDOWN) "contents/item-$index.md" else null,
                )
            },
        )

        assertEquals(
            document,
            codec.decode(codec.encode(document), LibraryScope.PROJECT_SHARED),
        )
    }

    @Test
    fun `JSON ordering is stable`() {
        val document = LibraryDocument(
            groups = listOf(
                group("z-last", order = 20),
                group("b-second", order = 10),
                group("a-first", order = 10),
            ),
            items = listOf(
                item("z-item", LibraryItemType.LINK, target = "https://z.example"),
                item("a-item", LibraryItemType.LINK, target = "https://a.example"),
            ),
        )

        val first = codec.encode(document)
        val second = codec.encode(document.copy(
            groups = document.groups.reversed(),
            items = document.items.reversed(),
        ))

        assertEquals(first, second)
        assertTrue(first.indexOf("\"id\": \"a-first\"") < first.indexOf("\"id\": \"b-second\""))
        assertTrue(first.indexOf("\"id\": \"b-second\"") < first.indexOf("\"id\": \"z-last\""))
        assertTrue(first.indexOf("\"id\": \"a-item\"") < first.indexOf("\"id\": \"z-item\""))
    }

    @Test
    fun `duplicate group and item IDs are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            codec.encode(
                LibraryDocument(
                    groups = listOf(group("duplicate"), group("duplicate")),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            codec.encode(
                LibraryDocument(
                    items = listOf(
                        item("duplicate", LibraryItemType.LINK, target = "https://one.example"),
                        item("duplicate", LibraryItemType.LINK, target = "https://two.example"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `unknown version is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            codec.decode(
                """{"version":2,"groups":[],"items":[]}""",
                LibraryScope.GLOBAL,
            )
        }
    }

    @Test
    fun `tags are trimmed deduplicated and stably ordered`() {
        val document = LibraryDocument(
            items = listOf(
                item(
                    id = "tagged",
                    type = LibraryItemType.LINK,
                    target = "https://example.com",
                    tags = linkedSetOf(" Kotlin ", "IDEA", "Kotlin", " ", ""),
                ),
            ),
        )

        val encoded = codec.encode(document)
        val decoded = codec.decode(encoded, LibraryScope.PROJECT_SHARED)

        assertEquals(linkedSetOf("IDEA", "Kotlin"), decoded.items.single().tags)
        assertTrue(encoded.indexOf("\"IDEA\"") < encoded.indexOf("\"Kotlin\""))
    }

    @Test
    fun `Markdown item requires content file`() {
        assertFailsWith<IllegalArgumentException> {
            codec.encode(
                LibraryDocument(
                    items = listOf(item("notes", LibraryItemType.MARKDOWN)),
                ),
            )
        }
    }

    @Test
    fun `non-Markdown item requires target`() {
        LibraryItemType.entries
            .filterNot { it == LibraryItemType.MARKDOWN }
            .forEach { type ->
                assertFailsWith<IllegalArgumentException>(type.name) {
                    codec.encode(LibraryDocument(items = listOf(item(type.name, type))))
                }
            }
    }

    private fun group(id: String, order: Int = 0) = LibraryGroup(
        id = id,
        name = "Group $id",
        description = "Description",
        order = order,
        scope = LibraryScope.PROJECT_SHARED,
    )

    private fun item(
        id: String,
        type: LibraryItemType,
        target: String? = null,
        contentFile: String? = null,
        tags: Set<String> = setOf("reference"),
    ) = LibraryItem(
        id = id,
        title = "Item $id",
        type = type,
        scope = LibraryScope.PROJECT_SHARED,
        groupId = "references",
        tags = tags,
        note = "Note",
        favorite = true,
        target = target,
        contentFile = contentFile,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

package com.anmi.devworkspace.library.storage

import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemSource
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import com.anmi.devworkspace.library.domain.LibrarySourceKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryJsonV2MigrationTest {
    private val codec = LibraryJsonCodec()

    @Test
    fun `version 1 item loads with null source`() {
        val decoded = codec.decode(versionOneDocument(), LibraryScope.PROJECT_PRIVATE)

        assertEquals(2, decoded.version)
        assertNull(decoded.items.single().source)
        assertEquals("legacy", decoded.items.single().id)
        assertEquals("contents/legacy.md", decoded.items.single().contentFile)
    }

    @Test
    fun `version 2 source round trips`() {
        val source = LibraryItemSource(
            kind = LibrarySourceKind.EDITOR_SELECTION,
            path = "\${PROJECT_DIR}/src/Main.kt",
            startLine = 4,
            endLine = 7,
        )
        val document = LibraryDocument(items = listOf(markdownItem(source)))

        val decoded = codec.decode(codec.encode(document), LibraryScope.PROJECT_PRIVATE)

        assertEquals(source, decoded.items.single().source)
    }

    @Test
    fun `save always writes version 2`() {
        val encoded = codec.encode(LibraryDocument(version = 1))

        assertTrue(encoded.contains("\"version\": 2"))
    }

    @Test
    fun `unknown in-memory document version is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            codec.encode(LibraryDocument(version = 99))
        }
    }

    @Test
    fun `invalid partial line range is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LibraryItemSource(
                kind = LibrarySourceKind.EDITOR_SELECTION,
                path = "\${PROJECT_DIR}/src/Main.kt",
                startLine = 3,
                endLine = null,
            )
        }
    }

    @Test
    fun `invalid reversed line range is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LibraryItemSource(
                kind = LibrarySourceKind.EDITOR_SELECTION,
                path = "\${PROJECT_DIR}/src/Main.kt",
                startLine = 8,
                endLine = 2,
            )
        }
    }

    private fun markdownItem(source: LibraryItemSource?) = LibraryItem(
        id = "snippet",
        title = "Snippet",
        type = LibraryItemType.MARKDOWN,
        scope = LibraryScope.PROJECT_PRIVATE,
        groupId = null,
        tags = setOf("Kotlin"),
        note = null,
        favorite = false,
        target = null,
        contentFile = "contents/snippet.md",
        createdAt = Instant.parse("2026-07-24T08:00:00Z"),
        updatedAt = Instant.parse("2026-07-24T09:00:00Z"),
        source = source,
    )

    private fun versionOneDocument(): String =
        """
        {
          "version": 1,
          "groups": [],
          "items": [
            {
              "id": "legacy",
              "title": "Legacy",
              "type": "MARKDOWN",
              "groupId": null,
              "tags": [],
              "note": null,
              "favorite": false,
              "target": null,
              "contentFile": "contents/legacy.md",
              "createdAt": "2026-07-24T08:00:00Z",
              "updatedAt": "2026-07-24T09:00:00Z"
            }
          ]
        }
        """.trimIndent()
}

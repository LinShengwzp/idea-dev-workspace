package com.anmi.devworkspace.library.files

import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemType
import com.anmi.devworkspace.library.domain.LibraryScope
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LibraryPathResolverTest {
    private val project = Path.of("D:/workspace/project").toAbsolutePath().normalize()
    private val resolver = LibraryPathResolver(project)

    @Test
    fun `project file persists with token and forward slashes`() {
        val path = project.resolve("docs/reference.pdf")

        assertEquals("\${PROJECT_DIR}/docs/reference.pdf", resolver.persist(path))
        assertEquals(path, resolver.resolve("\${PROJECT_DIR}/docs/reference.pdf"))
    }

    @Test
    fun `external file persists as normalized absolute forward slash path`() {
        val external = Path.of("D:/documents/reference.pdf").toAbsolutePath().normalize()

        assertEquals(external.toString().replace('\\', '/'), resolver.persist(external))
        assertEquals(external, resolver.resolve(resolver.persist(external)))
    }

    @Test
    fun `project token cannot escape project root`() {
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve("\${PROJECT_DIR}/../outside.txt")
        }
    }

    @Test
    fun `relocation changes target only`() {
        val original = item(target = "D:/missing/old.pdf")
        val relocatedPath = project.resolve("docs/new.pdf")

        val relocated = resolver.relocate(original, relocatedPath)

        assertEquals(original.copy(target = "\${PROJECT_DIR}/docs/new.pdf"), relocated)
    }

    private fun item(target: String) = LibraryItem(
        id = "reference",
        title = "Reference",
        type = LibraryItemType.FILE,
        scope = LibraryScope.PROJECT_SHARED,
        groupId = "docs",
        tags = setOf("reference"),
        note = "Keep",
        favorite = true,
        target = target,
        contentFile = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}

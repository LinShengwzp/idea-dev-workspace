package com.anmi.devworkspace.library.storage

import com.anmi.devworkspace.library.domain.LibraryScope
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryRepositoryPathsTest {
    @Test
    fun `repository roots match all three scope locations`() {
        val project = Path.of("D:/work/demo")
        val config = Path.of("D:/idea/config")
        val system = Path.of("D:/idea/system")
        val paths = LibraryRepositoryPaths(project, config, system)

        assertEquals(
            project.resolve(".dev-workspace/library"),
            paths.root(LibraryScope.PROJECT_SHARED),
        )
        assertEquals(
            config.resolve("dev-workspace/library"),
            paths.root(LibraryScope.GLOBAL),
        )

        val privateRoot = paths.root(LibraryScope.PROJECT_PRIVATE)
        assertEquals(system.resolve("dev-workspace/projects"), privateRoot.parent.parent)
        assertEquals("library", privateRoot.fileName.toString())
        assertTrue(privateRoot.parent.fileName.toString().matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `index and contents are located below each repository root`() {
        val paths = LibraryRepositoryPaths(
            Path.of("project"),
            Path.of("config"),
            Path.of("system"),
        )

        LibraryScope.entries.forEach { scope ->
            val root = paths.root(scope)
            assertEquals(root.resolve("library.json"), paths.index(scope))
            assertEquals(root.resolve("contents"), paths.contents(scope))
        }
    }
}

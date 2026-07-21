package com.anmi.devworkspace.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtomicFileWriterTest {
    @Test
    fun `valid content replaces the file`() = withTemporaryDirectory { directory ->
        val path = directory.resolve("tasks.toml")
        Files.writeString(path, "old")

        AtomicFileWriter().write(path, "new") { content ->
            require(content == "new")
        }

        assertEquals("new", Files.readString(path))
    }

    @Test
    fun `validator failure leaves original file untouched`() = withTemporaryDirectory { directory ->
        val path = directory.resolve("tasks.toml")
        Files.writeString(path, "original")

        assertFailsWith<IllegalArgumentException> {
            AtomicFileWriter().write(path, "invalid") {
                throw IllegalArgumentException("invalid test content")
            }
        }

        assertEquals("original", Files.readString(path))
    }

    private fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("dev-workspace-atomic-writer-test")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}

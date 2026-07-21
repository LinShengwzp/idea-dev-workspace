package com.anmi.devworkspace.config

import com.anmi.devworkspace.domain.TaskScope
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LastValidSnapshotStoreTest {
    @Test
    fun `valid snapshot survives a new store instance`() = withTemporaryDirectory { directory ->
        val source = directory.resolve("source/tasks.toml")
        val content = inlineTask("first", "echo first")
        val snapshot = parseSnapshot(content, source)

        store(directory.resolve("cache")).save(snapshot, content)

        val loaded = store(directory.resolve("cache")).load(source, TaskScope.GLOBAL)
        assertEquals("first", assertNotNull(loaded).tasks.single().id)
    }

    @Test
    fun `corrupt cache content returns null`() = withTemporaryDirectory { directory ->
        val cacheRoot = directory.resolve("cache")
        val source = directory.resolve("source/tasks.toml")
        val content = inlineTask("first", "echo first")
        store(cacheRoot).save(parseSnapshot(content, source), content)
        val cacheFile = Files.list(cacheRoot).use { files -> files.findFirst().orElseThrow() }
        Files.writeString(cacheFile, "not valid TOML = [")

        assertNull(store(cacheRoot).load(source, TaskScope.GLOBAL))
    }

    @Test
    fun `different normalized source paths do not collide`() = withTemporaryDirectory { directory ->
        val cacheRoot = directory.resolve("cache")
        val firstSource = directory.resolve("first/../first/tasks.toml")
        val secondSource = directory.resolve("second/tasks.toml")
        val firstContent = inlineTask("first", "echo first")
        val secondContent = inlineTask("second", "echo second")
        val store = store(cacheRoot)

        store.save(parseSnapshot(firstContent, firstSource), firstContent)
        store.save(parseSnapshot(secondContent, secondSource), secondContent)

        assertEquals("first", store.load(firstSource.normalize(), TaskScope.GLOBAL)?.tasks?.single()?.id)
        assertEquals("second", store.load(secondSource, TaskScope.GLOBAL)?.tasks?.single()?.id)
        assertEquals(2, Files.list(cacheRoot).use { it.count() })
    }

    private fun store(cacheRoot: Path) = LastValidSnapshotStore(
        parser = TaskTomlParser(),
        writer = AtomicFileWriter(),
        cacheRoot = cacheRoot,
    )

    private fun parseSnapshot(content: String, source: Path): TaskConfigSnapshot {
        val result = TaskTomlParser().parse(content, TaskScope.GLOBAL, source)
        return (result as TaskConfigLoadResult.Success).snapshot
    }

    private fun inlineTask(id: String, command: String): String = """
        version = 1
        [[tasks]]
        id = "$id"
        sourceType = "inline"
        command = "$command"
    """.trimIndent()

    private fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("dev-workspace-snapshot-store-test")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}

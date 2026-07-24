package com.anmi.devworkspace.library.preview

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Comparator
import javax.imageio.ImageIO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ImagePreviewServiceTest {
    @Test
    fun `cache is bounded and newer modification invalidates stale path entry`() {
        val cache = ImagePreviewCache(maxEntries = 2, maxPixels = 10_000)
        val path = Path.of("D:/images/cover.png")
        val size = ImagePreviewSize(100, 100)
        val first = ImagePreviewKey(path, 1, size)
        val changed = ImagePreviewKey(path, 2, size)
        val other = ImagePreviewKey(Path.of("D:/images/other.png"), 1, size)

        cache.put(first, image(20, 20))
        cache.put(other, image(20, 20))
        cache.put(changed, image(20, 20))

        assertFalse(cache.contains(first))
        assertTrue(cache.contains(changed))
        assertTrue(cache.contains(other))
        assertEquals(2, cache.size)
    }

    @Test
    fun `same path modification and requested size reuses only exact cache key`() =
        withTemporaryDirectory { directory ->
            val path = directory.resolve("image.png")
            Files.write(path, byteArrayOf(1))
            var decodes = 0
            val decoder = ImageDecoder { _, size ->
                decodes++
                image(size.width, size.height)
            }
            val service = ImagePreviewService(decoder = decoder)

            val first = assertIs<ImagePreview.Available>(service.load(path, ImagePreviewSize(80, 60)))
            val repeated = assertIs<ImagePreview.Available>(service.load(path, ImagePreviewSize(80, 60)))
            val resized = assertIs<ImagePreview.Available>(service.load(path, ImagePreviewSize(40, 30)))
            val oldMtime = Files.getLastModifiedTime(path).toMillis()
            Files.setLastModifiedTime(path, FileTime.fromMillis(oldMtime + 2_000))
            val externallyChanged = assertIs<ImagePreview.Available>(
                service.load(path, ImagePreviewSize(80, 60)),
            )

            assertSame(first.image, repeated.image)
            assertEquals(3, decodes)
            assertEquals(40, resized.image.width)
            assertFalse(first.image === externallyChanged.image)
            assertEquals(1, service.cacheSize)
            service.dispose()
        }

    @Test
    fun `production decoder scales proportionally`() = withTemporaryDirectory { directory ->
        val path = directory.resolve("wide.png")
        ImageIO.write(image(400, 200), "png", path.toFile())
        val service = ImagePreviewService()

        val preview = assertIs<ImagePreview.Available>(
            service.load(path, ImagePreviewSize(100, 100)),
        )

        assertEquals(100, preview.image.width)
        assertEquals(50, preview.image.height)
        service.dispose()
    }

    @Test
    fun `missing and corrupt images return placeholders`() = withTemporaryDirectory { directory ->
        val service = ImagePreviewService()
        val missing = service.load(directory.resolve("missing.png"), ImagePreviewSize(100, 100))
        val corruptPath = directory.resolve("corrupt.png")
        Files.writeString(corruptPath, "not an image")
        val corrupt = service.load(corruptPath, ImagePreviewSize(100, 100))

        assertEquals(ImagePlaceholderReason.MISSING, assertIs<ImagePreview.Placeholder>(missing).reason)
        assertEquals(ImagePlaceholderReason.CORRUPT, assertIs<ImagePreview.Placeholder>(corrupt).reason)
        service.dispose()
    }

    @Test
    fun `cancellation propagates and does not populate cache`() = runBlocking {
        val path = Files.createTempFile("dev-library-cancel", ".png")
        val cache = ImagePreviewCache()
        try {
            val service = ImagePreviewService(
                cache = cache,
                decoder = ImageDecoder { _, _ -> awaitCancellation() },
            )
            val loading = async { service.load(path, ImagePreviewSize(100, 100)) }

            loading.cancelAndJoin()

            assertTrue(loading.isCancelled)
            assertEquals(0, cache.size)
            service.dispose()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `dispose releases cache and prevents later decoding`() = withTemporaryDirectory { directory ->
        val path = directory.resolve("image.png")
        Files.write(path, byteArrayOf(1))
        var decoded = false
        val cache = ImagePreviewCache()
        val service = ImagePreviewService(
            cache = cache,
            decoder = ImageDecoder { _, _ ->
                decoded = true
                image(10, 10)
            },
        )
        service.dispose()

        val result = service.load(path, ImagePreviewSize(10, 10))

        assertEquals(ImagePlaceholderReason.DISPOSED, assertIs<ImagePreview.Placeholder>(result).reason)
        assertFalse(decoded)
        assertEquals(0, cache.size)
    }

    private fun image(width: Int, height: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            val graphics = createGraphics()
            try {
                graphics.color = Color.BLUE
                graphics.fillRect(0, 0, width, height)
            } finally {
                graphics.dispose()
            }
        }

    private fun withTemporaryDirectory(block: suspend (Path) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("dev-library-image-preview")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}

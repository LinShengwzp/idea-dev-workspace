package com.anmi.devworkspace.library.preview

import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.LinkedHashMap

data class ImagePreviewSize(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "Image preview size must be positive" }
    }
}

data class ImagePreviewKey(
    val path: Path,
    val lastModifiedMillis: Long,
    val requestedSize: ImagePreviewSize,
) {
    fun normalized(): ImagePreviewKey = copy(path = path.toAbsolutePath().normalize())
}

/**
 * Access-ordered image cache bounded by both entry count and decoded pixels.
 *
 * Only final scaled previews enter the cache. When a path's modification time
 * changes, every older size for that path is removed immediately, preventing
 * stale previews from surviving until ordinary LRU eviction.
 */
class ImagePreviewCache(
    private val maxEntries: Int = 32,
    private val maxPixels: Long = 24L * 1024 * 1024,
) : AutoCloseable {
    private val entries = LinkedHashMap<ImagePreviewKey, BufferedImage>(16, 0.75f, true)
    private var pixels: Long = 0

    init {
        require(maxEntries > 0) { "Image cache entry limit must be positive" }
        require(maxPixels > 0) { "Image cache pixel limit must be positive" }
    }

    @Synchronized
    fun get(key: ImagePreviewKey): BufferedImage? = entries[key.normalized()]

    @Synchronized
    fun put(key: ImagePreviewKey, image: BufferedImage) {
        val normalized = key.normalized()
        entries.keys
            .filter { it.path == normalized.path && it.lastModifiedMillis != normalized.lastModifiedMillis }
            .toList()
            .forEach(::remove)

        remove(normalized)
        // Oversized previews remain owned by the caller but are never retained by the cache.
        if (image.pixelCount() > maxPixels) return
        entries[normalized] = image
        pixels += image.pixelCount()
        while (entries.size > maxEntries || pixels > maxPixels) {
            remove(entries.entries.first().key)
        }
    }

    @Synchronized
    fun contains(key: ImagePreviewKey): Boolean = entries.containsKey(key.normalized())

    val size: Int
        @Synchronized get() = entries.size

    @Synchronized
    fun clear() {
        entries.values.forEach(BufferedImage::flush)
        entries.clear()
        pixels = 0
    }

    override fun close() = clear()

    private fun remove(key: ImagePreviewKey) {
        entries.remove(key)?.let { image ->
            pixels -= image.pixelCount()
            image.flush()
        }
    }

    private fun BufferedImage.pixelCount(): Long = width.toLong() * height.toLong()
}

package com.anmi.devworkspace.library.preview

import com.intellij.openapi.Disposable
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class ImagePlaceholderReason {
    MISSING,
    CORRUPT,
    DISPOSED,
}

sealed interface ImagePreview {
    data class Available(val image: BufferedImage) : ImagePreview
    data class Placeholder(val reason: ImagePlaceholderReason) : ImagePreview
}

/** Decode boundary is suspendable so cancellation can propagate through preview loading. */
fun interface ImageDecoder {
    suspend fun decode(path: Path, requestedSize: ImagePreviewSize): BufferedImage?
}

/**
 * Asynchronous image loader with modification-aware scaled-image caching.
 *
 * File metadata, decoding, and scaling all run on the IO dispatcher. A
 * modification-time race during decode is retried once, so a genuine external
 * edit cannot publish a preview keyed only by stale metadata.
 */
class ImagePreviewService(
    private val cache: ImagePreviewCache = ImagePreviewCache(),
    private val decoder: ImageDecoder = BoundedImageDecoder(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Disposable {
    private val lifecycleLock = Any()
    @Volatile
    private var disposed = false

    val cacheSize: Int get() = cache.size

    /** Drops decoded previews so an explicit refresh cannot reuse stale pixels. */
    fun clear() {
        synchronized(lifecycleLock) {
            if (!disposed) cache.clear()
        }
    }

    suspend fun load(path: Path, requestedSize: ImagePreviewSize): ImagePreview =
        withContext(ioDispatcher) {
            if (disposed) return@withContext ImagePreview.Placeholder(ImagePlaceholderReason.DISPOSED)
            val normalized = path.toAbsolutePath().normalize()
            if (!Files.exists(normalized)) {
                return@withContext ImagePreview.Placeholder(ImagePlaceholderReason.MISSING)
            }

            repeat(2) { attempt ->
                coroutineContext.ensureActive()
                val modifiedBefore = lastModified(normalized)
                    ?: return@withContext ImagePreview.Placeholder(ImagePlaceholderReason.MISSING)
                val key = ImagePreviewKey(normalized, modifiedBefore, requestedSize)
                val cached = synchronized(lifecycleLock) {
                    if (disposed) null else cache.get(key)
                }
                cached?.let { return@withContext ImagePreview.Available(it) }
                if (disposed) return@withContext ImagePreview.Placeholder(ImagePlaceholderReason.DISPOSED)
                val decoded = decodeSafely(normalized, requestedSize)
                    ?: return@withContext ImagePreview.Placeholder(ImagePlaceholderReason.CORRUPT)
                try {
                    coroutineContext.ensureActive()
                    if (disposed) {
                        decoded.flush()
                        return@withContext ImagePreview.Placeholder(ImagePlaceholderReason.DISPOSED)
                    }
                    val modifiedAfter = lastModified(normalized)
                    if (modifiedAfter != modifiedBefore && attempt == 0) {
                        decoded.flush()
                        return@repeat
                    }
                    if (modifiedAfter != modifiedBefore) {
                        decoded.flush()
                        return@withContext ImagePreview.Placeholder(ImagePlaceholderReason.CORRUPT)
                    }
                    val cachedSuccessfully = synchronized(lifecycleLock) {
                        if (disposed) false else {
                            cache.put(key, decoded)
                            true
                        }
                    }
                    if (!cachedSuccessfully) {
                        decoded.flush()
                        return@withContext ImagePreview.Placeholder(ImagePlaceholderReason.DISPOSED)
                    }
                    return@withContext ImagePreview.Available(decoded)
                } catch (cancellation: CancellationException) {
                    decoded.flush()
                    throw cancellation
                }
            }
            ImagePreview.Placeholder(ImagePlaceholderReason.CORRUPT)
        }

    override fun dispose() {
        synchronized(lifecycleLock) {
            disposed = true
            cache.close()
        }
    }

    private suspend fun decodeSafely(path: Path, size: ImagePreviewSize): BufferedImage? =
        try {
            decoder.decode(path, size)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun lastModified(path: Path): Long? =
        try {
            Files.getLastModifiedTime(path).toMillis()
        } catch (_: IOException) {
            null
        }
}

/**
 * Reads only the first frame and requests source subsampling before scaling.
 *
 * Subsampling prevents the cache from retaining the original and reduces peak
 * decode size for large images; only the final viewport-sized image survives.
 */
class BoundedImageDecoder : ImageDecoder {
    override suspend fun decode(path: Path, requestedSize: ImagePreviewSize): BufferedImage? {
        val input = ImageIO.createImageInputStream(path.toFile()) ?: return null
        input.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.setInput(stream, true, true)
                val sourceWidth = reader.getWidth(0)
                val sourceHeight = reader.getHeight(0)
                val target = proportionalSize(sourceWidth, sourceHeight, requestedSize)
                val sample = max(
                    1,
                    min(sourceWidth / target.width, sourceHeight / target.height),
                )
                val parameters = reader.defaultReadParam.apply {
                    setSourceSubsampling(sample, sample, 0, 0)
                }
                val decoded = reader.read(0, parameters) ?: return null
                if (decoded.width == target.width && decoded.height == target.height) return decoded
                val scaled = BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB)
                val graphics = scaled.createGraphics()
                try {
                    graphics.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                    )
                    graphics.drawImage(decoded, 0, 0, target.width, target.height, null)
                } finally {
                    graphics.dispose()
                    decoded.flush()
                }
                return scaled
            } finally {
                reader.dispose()
            }
        }
    }

    private fun proportionalSize(
        width: Int,
        height: Int,
        requested: ImagePreviewSize,
    ): ImagePreviewSize {
        val scale = min(
            1.0,
            min(requested.width.toDouble() / width, requested.height.toDouble() / height),
        )
        return ImagePreviewSize(
            max(1, floor(width * scale).toInt()),
            max(1, floor(height * scale).toInt()),
        )
    }
}

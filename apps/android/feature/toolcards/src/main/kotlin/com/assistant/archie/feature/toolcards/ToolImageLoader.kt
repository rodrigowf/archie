package com.assistant.archie.feature.toolcards

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A decoded tool image: [bitmap] may be downsampled; [width]/[height] are the original pixel size. */
class LoadedToolImage(val bitmap: Bitmap, val width: Int, val height: Int)

/**
 * Loads the pictures of tool results (spec 12 R-10 / TC-5) without an image library: [fetch] gets the
 * bytes (the app passes its REST client, so the server's TLS pins and the HTTP disk cache apply; the
 * backend marks the files immutable), [BitmapFactory] decodes them in two passes (bounds, then
 * `inSampleSize` so the bitmap is no larger than a power of two above the box it is shown in), and the
 * decoded bitmaps stay in a small [LruCache] sized by bytes. All work runs on [io].
 *
 * One cache entry per (url, box): the card's small copy and the full-screen copy are separate.
 */
class ToolImageLoader(
    private val fetch: suspend (url: String) -> ByteArray?,
    maxCacheBytes: Int = defaultCacheBytes(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val cache = object : LruCache<String, LoadedToolImage>(maxCacheBytes) {
        override fun sizeOf(key: String, value: LoadedToolImage): Int = value.bitmap.byteCount
    }

    /** The decoded image if it is already in memory (no work). */
    fun cached(url: String, boxWidth: Int, boxHeight: Int): LoadedToolImage? = cache.get(key(url, boxWidth, boxHeight))

    /** Fetches and decodes [url] for a [boxWidth]×[boxHeight] px box; `null` when it cannot be shown. */
    suspend fun load(url: String, boxWidth: Int, boxHeight: Int): LoadedToolImage? {
        val k = key(url, boxWidth, boxHeight)
        cache.get(k)?.let { return it }
        return withContext(io) {
            val bytes = try {
                fetch(url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@withContext null
            decode(bytes, boxWidth, boxHeight)?.also { cache.put(k, it) }
        }
    }

    private fun key(url: String, w: Int, h: Int) = "$url@${w}x$h"

    companion object {
        /** An eighth of the heap, between 8 and 32 MB. */
        fun defaultCacheBytes(): Int {
            val heap = Runtime.getRuntime().maxMemory()
            return (heap / 8).coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024).toInt()
        }

        /** Two-pass decode: bounds first, then the downsampled bitmap. `null` for bytes that are not an image. */
        fun decode(bytes: ByteArray, boxWidth: Int, boxHeight: Int): LoadedToolImage? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = ToolImageSizing.sampleSize(bounds.outWidth, bounds.outHeight, boxWidth, boxHeight)
            }
            val bmp = try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            } catch (_: OutOfMemoryError) {
                null
            } ?: return null
            return LoadedToolImage(bmp, bounds.outWidth, bounds.outHeight)
        }
    }
}

/** Pure size math of tool images (JVM-tested). */
object ToolImageSizing {
    /**
     * The largest power of two `s` such that the image decoded at 1/s is still at least as large as it
     * is drawn when fitted into [boxW]×[boxH] (never upscaled). 1 for a box that is not known yet.
     */
    fun sampleSize(srcW: Int, srcH: Int, boxW: Int, boxH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || boxW <= 0 || boxH <= 0) return 1
        val ratio = max(srcW.toDouble() / boxW, srcH.toDouble() / boxH)
        var s = 1
        while (s * 2 <= ratio) s *= 2
        return s
    }

    /**
     * Drawn size of a [srcW]×[srcH] image in a [maxW]×[maxH] box: original aspect ratio, scaled down to
     * fit (the CSS `max-width: 100%; max-height: …` of the web card), never up.
     */
    fun fit(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return 0 to 0
        val scale = min(1.0, min(maxW.toDouble() / srcW, maxH.toDouble() / srcH))
        return max(1, (srcW * scale).roundToInt()) to max(1, (srcH * scale).roundToInt())
    }
}

/**
 * The app's [ToolImageLoader]. `null` (previews, goldens, tests without a server) shows every image as
 * "Image unavailable".
 */
val LocalToolImageLoader = staticCompositionLocalOf<ToolImageLoader?> { null }

package com.aidenir.riff.core

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import android.util.LruCache
import com.aidenir.riff.ffi.Priority
import com.aidenir.riff.ffi.RiffCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * Artwork decoded from the bytes the core hands out (cached on disk and in
 * memory there), kept in a bitmap cache sized from the memory class.
 */
class ImageLoader(context: Context, private val core: RiffCore) {
    private val cache: LruCache<String, Bitmap>
    private val inflight = mutableMapOf<String, Deferred<Bitmap?>>()

    init {
        val memoryClass = context.getSystemService(ActivityManager::class.java).memoryClass
        val bytes = memoryClass * 1024 * 1024 / 6
        cache = object : LruCache<String, Bitmap>(bytes) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    }

    /** Sizes are bucketed so a list and a header can share one decode. */
    private fun bucket(px: Int) = when {
        px <= 96 -> 96
        px <= 192 -> 192
        px <= 384 -> 384
        else -> 768
    }

    fun cached(url: String, sizePx: Int): Bitmap? = cache.get("${bucket(sizePx)}|$url")

    suspend fun load(url: String, sizePx: Int, priority: Priority = Priority.VISIBLE): Bitmap? {
        val size = bucket(sizePx)
        val key = "$size|$url"
        cache.get(key)?.let { return it }
        val pending = CompletableDeferred<Bitmap?>()
        val existing = synchronized(inflight) {
            inflight[key] ?: run {
                inflight[key] = pending
                null
            }
        }
        if (existing != null) return existing.await()
        try {
            val bitmap = fetch(url, size, priority)
            if (bitmap != null) cache.put(key, bitmap)
            pending.complete(bitmap)
            return bitmap
        } catch (e: Exception) {
            pending.complete(null)
            if (e is CancellationException) throw e
            return null
        } finally {
            synchronized(inflight) { inflight.remove(key) }
        }
    }

    /** Raw bytes, for the media session artwork. */
    suspend fun bytes(url: String, sizePx: Int): ByteArray? =
        core.image(url, sizePx.toUInt(), Priority.VISIBLE)

    private suspend fun fetch(url: String, size: Int, priority: Priority): Bitmap? {
        val bytes = core.image(url, size.toUInt(), priority) ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val scale = size.toFloat() / maxOf(info.size.width, info.size.height)
                    if (scale < 1f) {
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1),
                        )
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Exception) {
                Log.w("RiffImages", "Could not decode $url", e)
                null
            }
        }
    }
}

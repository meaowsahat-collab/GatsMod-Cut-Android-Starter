package com.gatsmod.cut

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

data class MediaInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val fps: Float
) {
    /** Size as shown to the user (rotation applied). */
    val displayW: Int get() = if (rotation % 180 == 0) width else height
    val displayH: Int get() = if (rotation % 180 == 0) height else width
}

object MediaProbe {
    fun probe(ctx: Context, uri: Uri): MediaInfo? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            fun s(k: Int) = r.extractMetadata(k)
            MediaInfo(
                durationMs = s(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                width = s(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = s(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                rotation = s(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                hasVideo = s(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes",
                hasAudio = s(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                fps = s(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.takeIf { it > 1f } ?: 30f
            )
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    fun displayName(ctx: Context, uri: Uri): String {
        return try {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: (uri.lastPathSegment ?: "Clip")
        } catch (_: Exception) {
            uri.lastPathSegment ?: "Clip"
        }
    }
}

/** Video frame thumbnails for the timeline, loaded off the main thread and cached. */
object Thumbs {
    private val cache = LruCache<String, Bitmap>(120)
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val retrievers = HashMap<String, MediaMetadataRetriever>()

    fun get(ctx: Context, uri: String, srcMs: Long, onReady: () -> Unit): Bitmap? {
        val bucket = (srcMs / 1000L) * 1000L
        val key = "$uri@$bucket"
        cache.get(key)?.let { return it }
        if (failed.contains(key) || !pending.add(key)) return null
        val app = ctx.applicationContext
        exec.execute {
            var bmp: Bitmap? = null
            try {
                val r = synchronized(retrievers) {
                    retrievers.getOrPut(uri) { MediaMetadataRetriever().also { it.setDataSource(app, Uri.parse(uri)) } }
                }
                val full = r.getFrameAtTime(bucket * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (full != null) {
                    val w = 180
                    val h = (w * full.height.toFloat() / full.width).toInt().coerceAtLeast(1)
                    bmp = Bitmap.createScaledBitmap(full, w, h, true)
                    if (bmp !== full) full.recycle()
                }
            } catch (_: Exception) {
            }
            main.post {
                pending.remove(key)
                if (bmp != null) { cache.put(key, bmp); onReady() } else failed.add(key)
            }
        }
        return null
    }

    fun clear() {
        synchronized(retrievers) {
            retrievers.values.forEach { try { it.release() } catch (_: Exception) {} }
            retrievers.clear()
        }
        cache.evictAll()
    }
}

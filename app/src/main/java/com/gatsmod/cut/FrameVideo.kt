package com.gatsmod.cut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Builds small MP4 files frame by frame: used for Reverse and Freeze frame. */
object FrameVideo {

    class Result(val file: File, val durationMs: Long, val width: Int, val height: Int)

    /** Grabs one picture from [uri] at [srcMs], scaled so neither side is longer than [maxSide]. */
    fun frameAt(r: MediaMetadataRetriever, srcMs: Long, maxSide: Int): Bitmap? {
        val full = r.getFrameAtTime(srcMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        val s = min(1f, maxSide.toFloat() / max(full.width, full.height))
        if (s >= 0.999f) return full
        val w = max(2, (full.width * s).roundToInt())
        val h = max(2, (full.height * s).roundToInt())
        val out = Bitmap.createScaledBitmap(full, w, h, true)
        if (out !== full) full.recycle()
        return out
    }

    private fun even(v: Int) = max(2, v / 2 * 2)

    /**
     * Writes [frameCount] frames at [fps]. [frame] returns the picture for frame i (null = repeat the previous one).
     * [progress] gets 0..100 and returns false to cancel.
     */
    fun write(
        out: File, fps: Int, frameCount: Int, bitrate: Int,
        frame: (Int) -> Bitmap?, progress: (Int) -> Boolean
    ): Result? {
        if (frameCount <= 0) return null
        out.parentFile?.mkdirs()
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var ok = false
        try {
            val first = frame(0) ?: return null
            val w = even(first.width)
            val h = even(first.height)
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = c
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            var track = -1
            var muxing = false
            val info = MediaCodec.BufferInfo()
            val px = IntArray(w * h)
            var last: Bitmap? = first

            fun drain(untilEos: Boolean) {
                while (true) {
                    val i = c.dequeueOutputBuffer(info, if (untilEos) 10000L else 0L)
                    when {
                        i == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!untilEos) return }
                        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = m.addTrack(c.outputFormat)
                            m.start()
                            muxing = true
                        }
                        i >= 0 -> {
                            val buf = c.getOutputBuffer(i)
                            if (buf != null && info.size > 0 && muxing && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                m.writeSampleData(track, buf, info)
                            }
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            c.releaseOutputBuffer(i, false)
                            if (eos) return
                        }
                    }
                }
            }

            for (n in 0 until frameCount) {
                if (!progress(n * 100 / frameCount)) return null
                val bmp = (if (n == 0) first else frame(n)) ?: last ?: return null
                last = bmp
                // fill the encoder's input picture
                var idx = c.dequeueInputBuffer(10000L)
                var guard = 0
                while (idx < 0) {
                    drain(false)
                    idx = c.dequeueInputBuffer(10000L)
                    if (++guard > 500) return null
                }
                val image = c.getInputImage(idx) ?: return null
                val canvasBmp = if (bmp.width == w && bmp.height == h) bmp else {
                    val tmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(tmp).drawBitmap(bmp, null, Rect(0, 0, w, h), null)
                    tmp
                }
                canvasBmp.getPixels(px, 0, w, 0, 0, w, h)
                if (canvasBmp !== bmp) canvasBmp.recycle()
                fillImage(image, px, w, h)
                image.close()
                c.queueInputBuffer(idx, 0, w * h * 3 / 2, n * 1_000_000L / fps, 0)
                drain(false)
            }
            var idx = c.dequeueInputBuffer(10000L)
            var guard = 0
            while (idx < 0 && guard++ < 500) { drain(false); idx = c.dequeueInputBuffer(10000L) }
            if (idx < 0) return null
            c.queueInputBuffer(idx, 0, 0, frameCount * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            ok = muxing
            return if (ok) Result(out, frameCount * 1000L / fps, w, h) else null
        } catch (e: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { if (ok) muxer?.stop() } catch (_: Exception) { ok = false }
            try { muxer?.release() } catch (_: Exception) {}
            if (!ok) out.delete()
        }
    }

    private fun fillImage(image: android.media.Image, px: IntArray, w: Int, h: Int) {
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yB = yP.buffer
        val uB = uP.buffer
        val vB = vP.buffer
        for (y in 0 until h) {
            val rowY = y * yP.rowStride
            for (x in 0 until w) {
                val p = px[y * w + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                yB.put(rowY + x * yP.pixelStride, ((66 * r + 129 * g + 25 * b + 128 shr 8) + 16).coerceIn(16, 235).toByte())
                if (y % 2 == 0 && x % 2 == 0) {
                    val u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                    val v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128
                    uB.put((y / 2) * uP.rowStride + (x / 2) * uP.pixelStride, u.coerceIn(16, 240).toByte())
                    vB.put((y / 2) * vP.rowStride + (x / 2) * vP.pixelStride, v.coerceIn(16, 240).toByte())
                }
            }
        }
    }

    fun openRetriever(ctx: Context, uri: String): MediaMetadataRetriever? = try {
        MediaMetadataRetriever().also { it.setDataSource(ctx, Uri.parse(uri)) }
    } catch (e: Exception) { null }
}

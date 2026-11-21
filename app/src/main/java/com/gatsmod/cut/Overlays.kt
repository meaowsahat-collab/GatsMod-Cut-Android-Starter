@file:OptIn(UnstableApi::class)

package com.gatsmod.cut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Size
import android.view.MotionEvent
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import kotlin.math.max
import kotlin.math.min

/** Draws text / emoji / image overlays into a bitmap. Shared by the live preview and the exporter. */
object OverlayRenderer {
    private val imageCache = HashMap<String, Bitmap?>()

    fun render(ctx: Context, c: Clip, videoW: Int, videoH: Int): Bitmap? {
        if (videoW <= 0 || videoH <= 0) return null
        return if (c.kind == LaneKind.OVERLAY) renderImage(ctx, c, videoW) else renderText(c, videoW, videoH)
    }

    private fun renderText(c: Clip, videoW: Int, videoH: Int): Bitmap? {
        if (c.text.isBlank()) return null
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c.color
            textSize = max(8f, c.sizeFrac * videoH)
            isFakeBoldText = c.bold
            typeface = when (c.font) {
                1 -> android.graphics.Typeface.SERIF
                2 -> android.graphics.Typeface.MONOSPACE
                3 -> android.graphics.Typeface.create("cursive", android.graphics.Typeface.NORMAL)
                4 -> android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
                5 -> android.graphics.Typeface.create("casual", android.graphics.Typeface.NORMAL)
                else -> android.graphics.Typeface.SANS_SERIF
            }
            setShadowLayer(textSize * 0.08f, 0f, textSize * 0.04f, Color.argb(200, 0, 0, 0))
        }
        val maxW = (videoW * 0.9f).toInt().coerceAtLeast(16)
        val layout = StaticLayout.Builder.obtain(c.text, 0, c.text.length, paint, maxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        val pad = (paint.textSize * 0.25f).toInt() + 2
        var textW = 1
        for (i in 0 until layout.lineCount) textW = max(textW, Math.ceil(layout.getLineWidth(i).toDouble()).toInt())
        val bmp = Bitmap.createBitmap(textW + pad * 2, layout.height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.translate(pad.toFloat() + (textW - layout.width) / 2f, pad.toFloat())
        layout.draw(canvas)
        return bmp
    }

    private fun renderImage(ctx: Context, c: Clip, videoW: Int): Bitmap? {
        val src = imageCache.getOrPut(c.uri) {
            try {
                ctx.contentResolver.openInputStream(Uri.parse(c.uri))?.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }
        } ?: return null
        val w = max(8, (c.sizeFrac * videoW).toInt())
        val h = max(8, (w * src.height.toFloat() / src.width).toInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}

/** Export-time overlay: shows [clip] between [startUs] and [endUs] of the output video. */
class TimedOverlay(private val ctx: Context, private val clip: Clip, private val startUs: Long, private val endUs: Long) : BitmapOverlay() {
    private val empty: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private var videoW = 0
    private var videoH = 0
    private var bmp: Bitmap? = null
    private val settings: OverlaySettings = OverlaySettings.Builder()
        .setBackgroundFrameAnchor(clip.posX * 2f - 1f, 1f - clip.posY * 2f)
        .build()

    override fun configure(videoSize: Size) {
        videoW = videoSize.width
        videoH = videoSize.height
        bmp = null
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (presentationTimeUs < startUs || presentationTimeUs >= endUs) return empty
        val b = bmp ?: OverlayRenderer.render(ctx, clip, videoW, videoH)?.also { bmp = it }
        return b ?: empty
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
}

/** Live preview of text / emoji / image overlays on top of the player. Selected overlays can be dragged. */
class OverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var project: Project? = null
    var playheadMs = 0L
    var onDragStart: (() -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private val cache = HashMap<String, Bitmap?>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
    private var dragId = -1L

    /** Aspect (w/h) of the exported canvas; the video is fitted inside this view like the player does. */
    var canvasAspect = 16f / 9f

    private fun videoRect(): RectF {
        val vw = width.toFloat()
        val vh = height.toFloat()
        var w = vw
        var h = vw / canvasAspect
        if (h > vh) { h = vh; w = vh * canvasAspect }
        val l = (vw - w) / 2f
        val t = (vh - h) / 2f
        return RectF(l, t, l + w, t + h)
    }

    private fun active(): List<Clip> {
        val p = project ?: return emptyList()
        return (p.overlay + p.text).filter { playheadMs >= it.startMs && playheadMs < it.endMs }
    }

    private fun bitmapFor(c: Clip, r: RectF): Bitmap? {
        val key = "${c.id}|${c.text}|${c.color}|${c.sizeFrac}|${c.bold}|${c.font}|${c.uri}|${r.width().toInt()}x${r.height().toInt()}"
        if (cache.size > 40) cache.clear()
        return cache.getOrPut(key) { OverlayRenderer.render(context, c, r.width().toInt(), r.height().toInt()) }
    }

    private fun boundsOf(c: Clip, r: RectF): RectF? {
        val b = bitmapFor(c, r) ?: return null
        val cx = r.left + c.posX * r.width()
        val cy = r.top + c.posY * r.height()
        return RectF(cx - b.width / 2f, cy - b.height / 2f, cx + b.width / 2f, cy + b.height / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        val r = videoRect()
        val sel = project?.selectedId ?: -1L
        for (c in active()) {
            val b = bitmapFor(c, r) ?: continue
            val bounds = boundsOf(c, r) ?: continue
            canvas.drawBitmap(b, bounds.left, bounds.top, paint)
            if (c.id == sel) canvas.drawRoundRect(bounds, 8f, 8f, box)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = project ?: return false
        val r = videoRect()
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                val s = p.selected() ?: return false
                if (s.kind != LaneKind.TEXT && s.kind != LaneKind.OVERLAY) return false
                if (s !in active()) return false
                val b = boundsOf(s, r) ?: return false
                RectF(b).apply { inset(-40f, -40f) }.let { if (!it.contains(e.x, e.y)) return false }
                dragId = s.id
                onDragStart?.invoke()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val c = p.byId(dragId) ?: return false
                c.posX = min(1f, max(0f, (e.x - r.left) / r.width()))
                c.posY = min(1f, max(0f, (e.y - r.top) / r.height()))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragId != -1L) { dragId = -1L; onDragEnd?.invoke() }
                return true
            }
        }
        return false
    }
}

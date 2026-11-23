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
import androidx.media3.common.util.Size
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

    private fun typefaceOf(c: Clip): android.graphics.Typeface {
        val fam = when (c.font) {
            1 -> "serif"; 2 -> "monospace"; 3 -> "cursive"; 4 -> "sans-serif-condensed"; 5 -> "casual"
            6 -> "sans-serif-black"; 7 -> "sans-serif-light"; 8 -> "sans-serif-medium"; 9 -> "sans-serif-thin"
            else -> "sans-serif"
        }
        val st = when { c.bold && c.italic -> android.graphics.Typeface.BOLD_ITALIC; c.bold -> android.graphics.Typeface.BOLD
            c.italic -> android.graphics.Typeface.ITALIC; else -> android.graphics.Typeface.NORMAL }
        return android.graphics.Typeface.create(fam, st)
    }

    private fun tilted(b: Bitmap, deg: Float): Bitmap {
        if (kotlin.math.abs(deg) < 0.5f) return b
        val m = android.graphics.Matrix()
        m.postRotate(deg)
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /** Text with stroke, shadow, background and look effects. [onlyText] skips nothing; used by the panel tiles too. */
    fun renderText(c: Clip, videoW: Int, videoH: Int): Bitmap? {
        if (c.text.isBlank()) return null
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c.color
            textSize = max(8f, c.sizeFrac * videoH)
            typeface = typefaceOf(c)
        }
        val ts = paint.textSize
        val maxW = (videoW * 0.9f).toInt().coerceAtLeast(16)
        val layout = StaticLayout.Builder.obtain(c.text, 0, c.text.length, paint, maxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        val strokeW = if (c.textFx == 4) ts * 0.07f else if (c.strokeColor != 0) ts * c.strokeW.coerceIn(0.01f, 0.3f) else 0f
        val pad = (ts * 0.25f + strokeW + (if (c.textFx == 1) ts * 0.5f else 0f) + (if (c.textFx == 2) ts * 0.3f else 0f) + (if (c.bgColor != 0) ts * 0.15f else 0f)).toInt() + 2
        var textW = 1
        for (i in 0 until layout.lineCount) textW = max(textW, Math.ceil(layout.getLineWidth(i).toDouble()).toInt())
        val bmp = Bitmap.createBitmap(textW + pad * 2, layout.height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (c.bgColor != 0) {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.bgColor }
            val m = ts * 0.12f
            canvas.drawRoundRect(RectF(pad - m, pad - m * 0.4f, pad + textW + m, pad + layout.height + m * 0.4f), ts * 0.2f, ts * 0.2f, bp)
        }
        canvas.translate(pad.toFloat() + (textW - layout.width) / 2f, pad.toFloat())
        fun draw(setup: () -> Unit) { paint.clearShadowLayer(); paint.style = Paint.Style.FILL; paint.shader = null; paint.color = c.color; setup(); layout.draw(canvas) }
        when (c.textFx) {
            2 -> { // long shadow
                for (i in 14 downTo 1) { val k = i * ts * 0.03f
                    canvas.save(); canvas.translate(k, k); draw { paint.color = Color.argb(150 - i * 6, 0, 0, 0) }; canvas.restore() }
            }
            3 -> { // glitch: cyan and magenta copies
                canvas.save(); canvas.translate(-ts * 0.05f, 0f); draw { paint.color = 0xCC00E5FF.toInt() }; canvas.restore()
                canvas.save(); canvas.translate(ts * 0.05f, 0f); draw { paint.color = 0xCCFF1744.toInt() }; canvas.restore()
            }
        }
        if (strokeW > 0f) {
            draw {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = strokeW * 2f; paint.strokeJoin = Paint.Join.ROUND
                paint.color = if (c.textFx == 4) c.color else c.strokeColor
            }
        }
        if (c.textFx != 4) {
            draw {
                when {
                    c.textFx == 1 -> { paint.setShadowLayer(ts * 0.45f, 0f, 0f, c.color) }
                    c.textFx == 5 -> paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, layout.height.toFloat(), 0xFFFFEB3B.toInt(), 0xFFFF4081.toInt(), android.graphics.Shader.TileMode.CLAMP)
                    c.shadow && c.textFx == 0 -> paint.setShadowLayer(ts * 0.08f, 0f, ts * 0.04f, Color.argb(200, 0, 0, 0))
                }
            }
            if (c.textFx == 1) draw { paint.setShadowLayer(ts * 0.2f, 0f, 0f, c.color) }
        }
        return tilted(bmp, c.tilt)
    }

    private fun renderImage(ctx: Context, c: Clip, videoW: Int): Bitmap? {
        val w = max(8, (c.sizeFrac * videoW).toInt())
        if (c.uri.startsWith("sticker:")) {
            val st = StickerArt.byId(c.uri.removePrefix("sticker:")) ?: return null
            return tilted(StickerArt.render(st, w), c.tilt)
        }
        val src = imageCache.getOrPut(c.uri) {
            try {
                ctx.contentResolver.openInputStream(Uri.parse(c.uri))?.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }
        } ?: return null
        val h = max(8, (w * src.height.toFloat() / src.width).toInt())
        return tilted(Bitmap.createScaledBitmap(src, w, h, true), c.tilt)
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

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        if (!clip.hasAnim) return settings
        val local = ((presentationTimeUs - startUs) / 1000L).coerceAtLeast(0L)
        val p = Anim.pose(clip.animIn, clip.animInMs, clip.animOut, clip.animOutMs, clip.animCombo, local, clip.lengthMs)
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(clip.posX * 2f - 1f + p.x, 1f - clip.posY * 2f + p.y)
            .setScale(p.sx, p.sy)
            .setRotationDegrees(-p.rot)
            .setAlphaScale(p.gain.coerceIn(0f, 1f))
            .build()
    }
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
    private var mode = 0   // 1 drag, 2 resize handle, 3 delete handle, 4 two-finger pinch
    private var cx0 = 0f
    private var cy0 = 0f
    private var startDist0 = 1f
    private var startAng0 = 0f
    private var size0 = 0f
    private var tilt0 = 0f
    private fun normDeg(d: Float): Float { var x = d; while (x > 180f) x -= 360f; while (x < -180f) x += 360f; return x }

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
        val key = "${c.id}|${c.text}|${c.color}|${c.sizeFrac}|${c.bold}|${c.font}|${c.italic}|${c.strokeColor}|${c.strokeW}|${c.bgColor}|${c.shadow}|${c.textFx}|${c.tilt}|${c.uri}|${r.width().toInt()}x${r.height().toInt()}"
        if (cache.size > 40) cache.clear()
        return cache.getOrPut(key) { OverlayRenderer.render(context, c, r.width().toInt(), r.height().toInt()) }
    }

    private fun boundsOf(c: Clip, r: RectF): RectF? {
        val b = bitmapFor(c, r) ?: return null
        val cx = r.left + c.posX * r.width()
        val cy = r.top + c.posY * r.height()
        return RectF(cx - b.width / 2f, cy - b.height / 2f, cx + b.width / 2f, cy + b.height / 2f)
    }

    private val dens = context.resources.displayMetrics.density
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt(); textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    var onDelete: (() -> Unit)? = null

    private fun handleR() = 13f * dens
    private fun deleteHandle(b: RectF) = floatArrayOf(b.left, b.top)
    private fun resizeHandle(b: RectF) = floatArrayOf(b.right, b.bottom)

    override fun onDraw(canvas: Canvas) {
        val r = videoRect()
        val sel = project?.selectedId ?: -1L
        for (c in active()) {
            val b = bitmapFor(c, r) ?: continue
            val bounds = boundsOf(c, r) ?: continue
            canvas.save()
            val pp = Paint(paint)
            if (c.hasAnim) {
                val p = Anim.pose(c.animIn, c.animInMs, c.animOut, c.animOutMs, c.animCombo, playheadMs - c.startMs, c.lengthMs)
                canvas.translate(p.x / 2f * r.width(), -p.y / 2f * r.height())
                canvas.rotate(p.rot, bounds.centerX(), bounds.centerY())
                canvas.scale(p.sx, p.sy, bounds.centerX(), bounds.centerY())
                pp.alpha = (p.gain.coerceIn(0f, 1f) * 255).toInt()
            }
            canvas.drawBitmap(b, bounds.left, bounds.top, pp)
            canvas.restore()
            if (c.id == sel) {
                canvas.drawRoundRect(bounds, 8f, 8f, box)
                val hr = handleR()
                handleText.textSize = hr * 1.2f
                val d = deleteHandle(bounds); val z = resizeHandle(bounds)
                canvas.drawCircle(d[0], d[1], hr, handleFill)
                canvas.drawText("✕", d[0], d[1] + hr * 0.42f, handleText)
                canvas.drawCircle(z[0], z[1], hr, handleFill)
                canvas.drawText("⤡", z[0], z[1] + hr * 0.42f, handleText)
            }
        }
    }

    /** Gesture on the selected video clip: (scale factor, dx px, dy px, rotation deg), all since the last call. */
    var onVideoGesture: ((Float, Float, Float, Float) -> Unit)? = null
    var onVideoGestureStart: (() -> Unit)? = null
    var onVideoGestureEnd: (() -> Unit)? = null
    private var gesturing = false
    private var lastDist = 1f
    private var lastAngle = 0f
    private var lastMx = 0f
    private var lastMy = 0f

    fun canvasRect(): RectF = videoRect()

    private fun dist(e: MotionEvent) = Math.hypot((e.getX(1) - e.getX(0)).toDouble(), (e.getY(1) - e.getY(0)).toDouble()).toFloat().coerceAtLeast(1f)
    private fun angle(e: MotionEvent) = Math.toDegrees(Math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    /** One finger moves the video, two fingers pinch to resize and twist to rotate it. */
    private fun videoTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { gesturing = true; lastMx = e.x; lastMy = e.y; onVideoGestureStart?.invoke() }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount >= 2) {
                lastDist = dist(e); lastAngle = angle(e)
                lastMx = (e.getX(0) + e.getX(1)) / 2f; lastMy = (e.getY(0) + e.getY(1)) / 2f
            }
            MotionEvent.ACTION_MOVE -> if (gesturing) {
                if (e.pointerCount >= 2) {
                    val d = dist(e); val a = angle(e)
                    val mx = (e.getX(0) + e.getX(1)) / 2f; val my = (e.getY(0) + e.getY(1)) / 2f
                    var da = a - lastAngle
                    while (da > 180f) da -= 360f
                    while (da < -180f) da += 360f
                    onVideoGesture?.invoke(d / lastDist, mx - lastMx, my - lastMy, da)
                    lastDist = d; lastAngle = a; lastMx = mx; lastMy = my
                } else {
                    onVideoGesture?.invoke(1f, e.x - lastMx, e.y - lastMy, 0f)
                    lastMx = e.x; lastMy = e.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val keep = if (e.actionIndex == 0) 1 else 0
                if (keep < e.pointerCount) { lastMx = e.getX(keep); lastMy = e.getY(keep) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (gesturing) { gesturing = false; onVideoGestureEnd?.invoke() }
        }
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = project ?: return false
        val r = videoRect()
        val vs = p.selected()
        if (vs != null && vs.kind == LaneKind.VIDEO && p.mainClipAt(playheadMs)?.id == vs.id) return videoTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val s = p.selected() ?: return false
                if (s.kind != LaneKind.TEXT && s.kind != LaneKind.OVERLAY) return false
                if (s !in active()) return false
                val b = boundsOf(s, r) ?: return false
                val hr = handleR() * 1.8f
                val d = deleteHandle(b); val z = resizeHandle(b)
                if (Math.hypot((e.x - d[0]).toDouble(), (e.y - d[1]).toDouble()) < hr) { mode = 3; dragId = s.id; return true }
                if (Math.hypot((e.x - z[0]).toDouble(), (e.y - z[1]).toDouble()) < hr) {
                    mode = 2; dragId = s.id
                    cx0 = b.centerX(); cy0 = b.centerY()
                    startDist0 = Math.hypot((e.x - cx0).toDouble(), (e.y - cy0).toDouble()).toFloat().coerceAtLeast(1f)
                    startAng0 = Math.toDegrees(Math.atan2((e.y - cy0).toDouble(), (e.x - cx0).toDouble())).toFloat()
                    size0 = s.sizeFrac; tilt0 = s.tilt
                    onDragStart?.invoke()
                    return true
                }
                RectF(b).apply { inset(-40f, -40f) }.let { if (!it.contains(e.x, e.y)) return false }
                mode = 1; dragId = s.id
                onDragStart?.invoke()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (dragId != -1L && mode != 3 && e.pointerCount >= 2) {
                val c = p.byId(dragId) ?: return true
                mode = 4
                startDist0 = dist(e); startAng0 = angle(e); size0 = c.sizeFrac; tilt0 = c.tilt
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val c = p.byId(dragId) ?: return false
                val maxSize = if (c.kind == LaneKind.TEXT) 0.6f else 1.5f
                val minSize = if (c.kind == LaneKind.TEXT) 0.02f else 0.05f
                when (mode) {
                    1 -> {
                        c.posX = min(1f, max(0f, (e.x - r.left) / r.width()))
                        c.posY = min(1f, max(0f, (e.y - r.top) / r.height()))
                    }
                    2 -> {
                        val dd = Math.hypot((e.x - cx0).toDouble(), (e.y - cy0).toDouble()).toFloat().coerceAtLeast(1f)
                        val aa = Math.toDegrees(Math.atan2((e.y - cy0).toDouble(), (e.x - cx0).toDouble())).toFloat()
                        c.sizeFrac = (size0 * dd / startDist0).coerceIn(minSize, maxSize)
                        c.tilt = normDeg(tilt0 + aa - startAng0)
                    }
                    4 -> if (e.pointerCount >= 2) {
                        c.sizeFrac = (size0 * dist(e) / startDist0).coerceIn(minSize, maxSize)
                        c.tilt = normDeg(tilt0 + angle(e) - startAng0)
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = dragId
                val m = mode
                dragId = -1L; mode = 0
                if (was != -1L) {
                    if (m == 3 && e.actionMasked == MotionEvent.ACTION_UP) {
                        val c = p.byId(was)
                        val b = c?.let { boundsOf(it, r) }
                        val d = b?.let { deleteHandle(it) }
                        if (d != null && Math.hypot((e.x - d[0]).toDouble(), (e.y - d[1]).toDouble()) < handleR() * 1.8f) onDelete?.invoke()
                    } else onDragEnd?.invoke()
                }
                return true
            }
        }
        return false
    }
}

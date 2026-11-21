package com.gatsmod.cut

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * CapCut-style timeline: the white playhead stays in the middle and the tracks scroll under it.
 * Tap a clip to select it, drag its white handles to trim, long-press and drag to move/reorder.
 */
class TimelineView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var project: Project? = null
    var playheadMs = 0L
        private set

    /** The user scrubbed the timeline to this position. */
    var onScrub: ((Long) -> Unit)? = null
    var onSelect: ((Clip?) -> Unit)? = null

    /** A drag (trim / move) finished and changed the project. */
    var onEdited: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var pxPerSec = dp(56f)
    private val rulerH = dp(24f)
    private val mainH = dp(56f)
    private val smallH = dp(28f)
    private val gap = dp(3f)

    private val bg = Paint().apply { color = 0xFF0D0D10.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val playLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = dp(2f) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = dp(11f); setShadowLayer(3f, 0f, 1f, 0xAA000000.toInt()) }
    private val rulerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA0A8.toInt(); textSize = dp(10f) }
    private val tick = Paint().apply { color = 0xFF4A4A54.toInt(); strokeWidth = dp(1f) }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E949C.toInt(); textSize = dp(12f); textAlign = Paint.Align.CENTER }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A44.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
    }
    private val kfPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD166.toInt() }
    private val dim = Paint().apply { color = 0x66000000 }

    private val laneColor = mapOf(
        LaneKind.VIDEO to 0xFF2B2B38.toInt(),
        LaneKind.AUDIO to 0xFF12A3AE.toInt(),
        LaneKind.TEXT to 0xFFC2549D.toInt(),
        LaneKind.OVERLAY to 0xFFD9822B.toInt()
    )

    private enum class Mode { NONE, SCRUB, TRIM_L, TRIM_R, MOVE }

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var moved = false
    private var dragClip: Clip? = null
    private var orig: Clip? = null
    private var origMids: List<Pair<Long, Long>> = emptyList()
    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable { startMove() }
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            pxPerSec = (pxPerSec * d.scaleFactor).coerceIn(dp(12f), dp(260f))
            invalidate()
            return true
        }
    })

    private val ppm get() = pxPerSec / 1000f
    private fun xOf(ms: Long) = width / 2f + (ms - playheadMs) * ppm
    private fun msAt(x: Float) = playheadMs + ((x - width / 2f) / ppm).toLong()
    private fun total() = project?.totalMs() ?: 0L
    private fun fmt(ms: Long): String { val s = ms / 1000; return "%02d:%02d".format(s / 60, s % 60) }

    fun setPlayhead(ms: Long) {
        playheadMs = ms.coerceIn(0L, max(0L, total()))
        invalidate()
    }

    fun zoomIn() { pxPerSec = min(dp(260f), pxPerSec * 1.4f); invalidate() }
    fun zoomOut() { pxPerSec = max(dp(12f), pxPerSec / 1.4f); invalidate() }

    // ---------------- layout of rows ----------------

    private class Row(val kind: LaneKind, val top: Float, val h: Float)

    private fun rows(): List<Row> {
        val p = project
        val out = ArrayList<Row>()
        var y = rulerH + gap
        fun add(k: LaneKind, h: Float, always: Boolean) {
            if (always || (p != null && p.lane(k).isNotEmpty())) { out.add(Row(k, y, h)); y += h + gap }
        }
        add(LaneKind.OVERLAY, smallH, false)
        add(LaneKind.TEXT, smallH, false)
        add(LaneKind.VIDEO, mainH, true)
        add(LaneKind.AUDIO, smallH, false)
        return out
    }

    private fun rectOf(c: Clip): RectF? {
        val row = rows().firstOrNull { it.kind == c.kind } ?: return null
        return RectF(xOf(c.startMs), row.top, max(xOf(c.endMs), xOf(c.startMs) + dp(6f)), row.top + row.h)
    }

    private fun clipAt(x: Float, y: Float): Clip? {
        val p = project ?: return null
        val row = rows().firstOrNull { y >= it.top && y <= it.top + it.h } ?: return null
        return p.lane(row.kind).asReversed().firstOrNull { c -> rectOf(c)?.let { x >= it.left && x <= it.right } == true }
    }

    // ---------------- drawing ----------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        drawRuler(canvas)
        val p = project
        val rows = rows()
        for (row in rows) {
            val clips = p?.lane(row.kind) ?: emptyList()
            if (row.kind == LaneKind.VIDEO && clips.isEmpty()) {
                val r = RectF(dp(14f), row.top, width - dp(14f), row.top + row.h)
                canvas.drawRoundRect(r, dp(8f), dp(8f), dashed)
                canvas.drawText("Tap + to add a video", width / 2f, row.top + row.h / 2f + dp(4f), hint)
            }
            for (c in clips) drawClip(canvas, c, row, c.id == p?.selectedId)
        }
        // dim everything after the end of the main track
        val endX = xOf(total())
        if (endX < width) canvas.drawRect(max(0f, endX), rulerH, width.toFloat(), height.toFloat(), dim)
        val cx = width / 2f
        canvas.drawLine(cx, 0f, cx, height.toFloat(), playLine)
        canvas.drawCircle(cx, rulerH - dp(2f), dp(4f), white)
    }

    private fun drawRuler(canvas: Canvas) {
        val steps = longArrayOf(1000, 2000, 5000, 10000, 30000, 60000, 120000, 300000)
        val stepMs = steps.firstOrNull { it * ppm >= dp(60f) } ?: steps.last()
        val firstMs = max(0L, (msAt(0f) / stepMs - 1) * stepMs)
        var t = firstMs
        while (true) {
            val x = xOf(t)
            if (x > width + dp(40f)) break
            if (x >= -dp(40f)) {
                canvas.drawLine(x, rulerH - dp(7f), x, rulerH, tick)
                canvas.drawText(fmt(t), x + dp(3f), rulerH - dp(10f), rulerText)
                val mid = xOf(t + stepMs / 2)
                canvas.drawLine(mid, rulerH - dp(3f), mid, rulerH, tick)
            }
            t += stepMs
        }
    }

    private fun drawClip(canvas: Canvas, c: Clip, row: Row, selected: Boolean) {
        val r = rectOf(c) ?: return
        if (r.right < -dp(20f) || r.left > width + dp(20f)) return
        val rad = dp(7f)
        fill.color = laneColor[c.kind] ?: 0xFF2B2B38.toInt()
        canvas.drawRoundRect(r, rad, rad, fill)
        canvas.save()
        val clip = Path().apply { addRoundRect(r, rad, rad, Path.Direction.CW) }
        canvas.clipPath(clip)
        if (c.kind == LaneKind.VIDEO) drawThumbs(canvas, c, r)
        val name = when (c.kind) {
            LaneKind.AUDIO -> "♪ " + c.label
            LaneKind.VIDEO -> if (c.speed != 1f) "${trimNum(c.speed)}x" else ""
            else -> c.text.ifBlank { c.label }
        }
        if (name.isNotEmpty()) {
            val ty = if (c.kind == LaneKind.VIDEO) r.top + dp(14f) else r.centerY() + dp(4f)
            canvas.drawText(name, max(r.left, 0f) + dp(8f), ty, label)
        }
        canvas.restore()
        if (selected) {
            canvas.drawRoundRect(r, rad, rad, outline)
            val hw = dp(7f)
            val hh = min(row.h * 0.6f, dp(26f))
            canvas.drawRoundRect(RectF(r.left - hw / 2f, r.centerY() - hh / 2f, r.left + hw / 2f, r.centerY() + hh / 2f), hw / 2f, hw / 2f, white)
            canvas.drawRoundRect(RectF(r.right - hw / 2f, r.centerY() - hh / 2f, r.right + hw / 2f, r.centerY() + hh / 2f), hw / 2f, hw / 2f, white)
            for (k in c.keyframes) {
                val kx = xOf(c.startMs + k.tMs)
                val ky = r.bottom - dp(7f)
                val d = dp(4f)
                val path = Path().apply { moveTo(kx, ky - d); lineTo(kx + d, ky); lineTo(kx, ky + d); lineTo(kx - d, ky); close() }
                canvas.drawPath(path, kfPaint)
            }
        }
    }

    private fun trimNum(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else "%.2f".format(f).trimEnd('0')

    private fun drawThumbs(canvas: Canvas, c: Clip, r: RectF) {
        val aspect = if (c.width > 0 && c.height > 0) c.width.toFloat() / c.height else 16f / 9f
        val tileW = (r.height() * aspect).coerceIn(dp(30f), dp(120f))
        val firstIdx = max(0, ((max(r.left, -tileW) - r.left) / tileW).toInt())
        var i = firstIdx
        while (true) {
            val l = r.left + i * tileW
            if (l > width || l > r.right) break
            val centerX = l + tileW / 2f
            val timelineOffset = ((min(centerX, r.right) - r.left) / ppm).toLong()
            val srcMs = c.srcInMs + (timelineOffset * c.speed).toLong()
            val bmp = Thumbs.get(context, c.uri, srcMs) { invalidate() }
            val dst = RectF(l, r.top, min(l + tileW, r.right), r.bottom)
            if (bmp != null) {
                // centre-crop the thumbnail into the tile
                val tileAspect = dst.width() / dst.height()
                val bAspect = bmp.width.toFloat() / bmp.height
                val src = if (bAspect > tileAspect) {
                    val w = (bmp.height * tileAspect).toInt()
                    android.graphics.Rect((bmp.width - w) / 2, 0, (bmp.width + w) / 2, bmp.height)
                } else {
                    val h = (bmp.width / tileAspect).toInt()
                    android.graphics.Rect(0, (bmp.height - h) / 2, bmp.width, (bmp.height + h) / 2)
                }
                canvas.drawBitmap(bmp, src, dst, thumbPaint)
            }
            i++
        }
    }

    // ---------------- touch ----------------

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            setPlayhead((scroller.currX / ppm).toLong())
            onScrub?.invoke(playheadMs)
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        if (scale.isInProgress) { removeCallbacks(longPress); mode = Mode.NONE; return true }
        val p = project ?: return true
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.abortAnimation()
                downX = e.x; downY = e.y; lastX = e.x
                mode = Mode.NONE; moved = false
                val sel = p.selected()
                val r = sel?.let { rectOf(it) }
                if (sel != null && r != null && downY >= r.top - dp(6f) && downY <= r.bottom + dp(6f)) {
                    if (abs(downX - r.left) < dp(20f)) beginTrim(p, sel, true)
                    else if (abs(downX - r.right) < dp(20f)) beginTrim(p, sel, false)
                }
                if (mode == Mode.NONE && clipAt(downX, downY) != null) postDelayed(longPress, 380)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (mode == Mode.NONE && abs(dx) > slop) { removeCallbacks(longPress); mode = Mode.SCRUB }
                val clip = dragClip
                val o = orig
                when (mode) {
                    Mode.SCRUB -> {
                        setPlayhead(playheadMs - ((e.x - lastX) / ppm).toLong())
                        onScrub?.invoke(playheadMs)
                    }
                    Mode.TRIM_L, Mode.TRIM_R -> if (clip != null && o != null) {
                        moved = true
                        p.trimBy(clip, o, mode == Mode.TRIM_L, (dx / ppm).toLong())
                        invalidate()
                    }
                    Mode.MOVE -> if (clip != null && o != null) {
                        moved = true
                        val deltaMs = (dx / ppm).toLong()
                        if (clip.kind == LaneKind.VIDEO) {
                            val center = o.startMs + o.lengthMs / 2 + deltaMs
                            p.moveMain(clip, origMids.count { it.first != clip.id && it.second < center })
                        } else {
                            p.moveFree(clip, o.startMs + deltaMs)
                        }
                        invalidate()
                    }
                    Mode.NONE -> {}
                }
                lastX = e.x
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val m = mode
                if (m == Mode.TRIM_L || m == Mode.TRIM_R || m == Mode.MOVE) {
                    if (moved) onEdited?.invoke() else p.discardCheckpoint()
                } else if (m == Mode.SCRUB && e.actionMasked == MotionEvent.ACTION_UP) {
                    velocity?.computeCurrentVelocity(1000)
                    val vx = velocity?.xVelocity ?: 0f
                    if (abs(vx) > 600f) {
                        scroller.fling((playheadMs * ppm).toInt(), 0, (-vx).toInt(), 0, 0, (total() * ppm).toInt(), 0, 0)
                        postInvalidateOnAnimation()
                    }
                } else if (m == Mode.NONE && e.actionMasked == MotionEvent.ACTION_UP) {
                    tap(p, e.x, e.y)
                }
                velocity?.recycle(); velocity = null
                dragClip = null; orig = null; mode = Mode.NONE
                return true
            }
        }
        return true
    }

    private fun tap(p: Project, x: Float, y: Float) {
        val hit = clipAt(x, y)
        if (hit != null) {
            p.selectedId = hit.id
            onSelect?.invoke(hit)
        } else if (y <= rulerH) {
            setPlayhead(msAt(x))
            onScrub?.invoke(playheadMs)
        } else {
            p.selectedId = -1L
            onSelect?.invoke(null)
        }
        invalidate()
    }

    private fun beginTrim(p: Project, c: Clip, left: Boolean) {
        removeCallbacks(longPress)
        p.checkpoint()
        dragClip = c
        orig = c.deepCopy()
        mode = if (left) Mode.TRIM_L else Mode.TRIM_R
    }

    private fun startMove() {
        val p = project ?: return
        val hit = clipAt(downX, downY) ?: return
        if (mode != Mode.NONE) return
        p.selectedId = hit.id
        onSelect?.invoke(hit)
        p.checkpoint()
        dragClip = hit
        orig = hit.deepCopy()
        origMids = p.main.map { it.id to (it.startMs + it.lengthMs / 2) }
        mode = Mode.MOVE
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        velocity?.recycle()
        velocity = null
        super.onDetachedFromWindow()
    }
}

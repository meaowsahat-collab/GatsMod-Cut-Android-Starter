package com.gatsmod.cut

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

enum class LaneKind { VIDEO, AUDIO, TEXT, OVERLAY }
data class TimelineClip(var startMs: Long, var endMs: Long, var label: String = "Clip", var kind: LaneKind = LaneKind.VIDEO, var source: String = "")
data class TimelineLane(val kind: LaneKind, var name: String, var clips: MutableList<TimelineClip> = mutableListOf())

class TimelineView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val rulerH = dp(28f)
    private val laneH = dp(52f)
    private val clipH = dp(42f)
    private val gutter = dp(14f)

    private val bg = Paint().apply { color = 0xFF0D0D10.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = dp(2f) }
    private val clipText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = dp(12f) }
    private val rulerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA0A8.toInt(); textSize = dp(11f) }
    private val tick = Paint().apply { color = 0xFF4A4A54.toInt(); strokeWidth = dp(1f) }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8E949C.toInt(); textSize = dp(13f) }

    private val colors = mapOf(
        LaneKind.VIDEO to 0xFF5B4BC4.toInt(),
        LaneKind.AUDIO to 0xFF12A3AE.toInt(),
        LaneKind.TEXT to 0xFFC2549D.toInt(),
        LaneKind.OVERLAY to 0xFFD9822B.toInt()
    )

    var durationMs = 0L
    var playheadMs = 0L
    var zoom = 1f
    var selectedLane = 0
    var selectedClip = 0
    var lanes = mutableListOf<TimelineLane>()
    var onSeek: ((Long) -> Unit)? = null
    var onSelection: ((TimelineClip?) -> Unit)? = null

    private val undoStack = mutableListOf<List<TimelineLane>>()
    private val redoStack = mutableListOf<List<TimelineLane>>()
    private var dragging = false
    private var dragStartX = 0f
    private var originalStart = 0L
    private var originalEnd = 0L
    private var dragSnapshot: List<TimelineLane>? = null
    private val frameMs = 33L

    init { lanes.add(TimelineLane(LaneKind.VIDEO, "Video 1")) }

    // ---- model operations (unchanged behaviour) ----
    fun setDuration(d: Long) { durationMs = max(1L, d); invalidate() }
    fun setPlayhead(ms: Long) { playheadMs = ms.coerceIn(0L, max(durationMs, 0L)); invalidate() }

    fun addClip(kind: LaneKind, label: String, source: String = "", duration: Long = durationMs.coerceAtLeast(1000L)) {
        saveUndo()
        val lane = lanes.indexOfFirst { it.kind == kind }.takeIf { it >= 0 }
            ?: run { lanes.add(TimelineLane(kind, "$kind ${lanes.count { it.kind == kind } + 1}")); lanes.lastIndex }
        val start = playheadMs
        val len = max(1000L, duration)
        lanes[lane].clips.add(TimelineClip(start, start + len, label, kind, source))
        selectedLane = lane
        selectedClip = lanes[lane].clips.lastIndex
        durationMs = max(durationMs, start + len)
        invalidate()
    }

    fun addLane(kind: LaneKind) { saveUndo(); lanes.add(TimelineLane(kind, "${kind.name} ${lanes.count { it.kind == kind } + 1}")); invalidate() }
    fun selected(): TimelineClip? = lanes.getOrNull(selectedLane)?.clips?.getOrNull(selectedClip)

    fun split() {
        val c = selected() ?: return
        if (playheadMs <= c.startMs + frameMs || playheadMs >= c.endMs - frameMs) return
        saveUndo()
        val tail = c.copy(startMs = playheadMs)
        c.endMs = playheadMs
        lanes[selectedLane].clips.add(selectedClip + 1, tail)
        selectedClip++
        invalidate()
    }

    fun trimStart() { val c = selected() ?: return; if (playheadMs >= c.endMs - frameMs) return; saveUndo(); c.startMs = playheadMs; invalidate() }
    fun trimEnd() { val c = selected() ?: return; if (playheadMs <= c.startMs + frameMs) return; saveUndo(); c.endMs = playheadMs; invalidate() }

    fun duplicate() {
        val c = selected() ?: return
        saveUndo()
        val copy = c.copy(startMs = c.endMs, endMs = c.endMs + (c.endMs - c.startMs))
        lanes[selectedLane].clips.add(selectedClip + 1, copy)
        selectedClip++
        durationMs = max(durationMs, copy.endMs)
        invalidate()
    }

    fun delete() {
        if (selected() == null) return
        saveUndo()
        lanes[selectedLane].clips.removeAt(selectedClip)
        selectedClip = (selectedClip - 1).coerceAtLeast(0)
        invalidate()
    }

    fun merge() {
        val list = lanes.getOrNull(selectedLane)?.clips ?: return
        if (selectedClip + 1 >= list.size) return
        val a = list[selectedClip]
        val b = list[selectedClip + 1]
        if (a.kind != b.kind || b.startMs > a.endMs + frameMs * 2) return
        saveUndo()
        a.endMs = max(a.endMs, b.endMs)
        list.removeAt(selectedClip + 1)
        invalidate()
    }

    fun nudgeFrames(frames: Int) {
        val c = selected() ?: return
        saveUndo()
        val d = frames * frameMs
        c.startMs = max(0L, c.startMs + d)
        c.endMs = max(c.startMs + frameMs, c.endMs + d)
        invalidate()
    }

    fun zoomIn() { zoom = min(8f, zoom * 1.25f); invalidate() }
    fun zoomOut() { zoom = max(.5f, zoom / 1.25f); invalidate() }

    fun undo() { if (undoStack.isEmpty()) return; redoStack.add(snapshot()); restore(undoStack.removeAt(undoStack.lastIndex)) }
    fun redo() { if (redoStack.isEmpty()) return; undoStack.add(snapshot()); restore(redoStack.removeAt(redoStack.lastIndex)) }

    private fun saveUndo() { undoStack.add(snapshot()); if (undoStack.size > 50) undoStack.removeAt(0); redoStack.clear() }
    private fun snapshot(): List<TimelineLane> = lanes.map { TimelineLane(it.kind, it.name, it.clips.map { c -> c.copy() }.toMutableList()) }
    private fun restore(s: List<TimelineLane>) {
        lanes = s.map { TimelineLane(it.kind, it.name, it.clips.map { c -> c.copy() }.toMutableList()) }.toMutableList()
        selectedLane = selectedLane.coerceIn(0, lanes.lastIndex)
        selectedClip = selectedClip.coerceAtLeast(0)
        invalidate()
    }

    // ---- geometry ----
    private fun trackWidth() = max(1f, width - gutter * 2)
    private fun pxPerMs() = trackWidth() / (max(1000L, durationMs) / zoom)
    private fun xOf(ms: Long) = gutter + ms * pxPerMs()
    private fun msAt(x: Float) = ((x - gutter) / pxPerMs()).toLong()
    private fun fmt(ms: Long): String { val s = ms / 1000; return "%02d:%02d".format(s / 60, s % 60) }

    // ---- drawing ----
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        drawRuler(canvas)
        if (durationMs <= 0) {
            canvas.drawText("Import media to start", gutter, rulerH + dp(34f), hint)
        } else {
            lanes.forEachIndexed { li, lane -> drawLane(canvas, li, lane) }
        }
        val px = xOf(playheadMs).coerceIn(gutter, width - gutter)
        canvas.drawLine(px, 0f, px, height.toFloat(), playPaint)
        canvas.drawCircle(px, rulerH, dp(4f), handle)
    }

    private fun drawRuler(canvas: Canvas) {
        val ppm = pxPerMs()
        val steps = longArrayOf(500, 1000, 2000, 5000, 10000, 30000, 60000, 120000, 300000)
        val stepMs = steps.firstOrNull { it * ppm >= dp(64f) } ?: steps.last()
        var t = 0L
        while (true) {
            val x = xOf(t)
            if (x > width - gutter / 2) break
            canvas.drawLine(x, rulerH - dp(8f), x, rulerH, tick)
            canvas.drawText(fmt(t), x + dp(3f), rulerH - dp(12f), rulerText)
            val mid = xOf(t + stepMs / 2)
            if (mid < width - gutter / 2) canvas.drawLine(mid, rulerH - dp(4f), mid, rulerH, tick)
            t += stepMs
        }
    }

    private fun drawLane(canvas: Canvas, li: Int, lane: TimelineLane) {
        val top = rulerH + li * laneH + (laneH - clipH) / 2
        lane.clips.forEachIndexed { ci, c ->
            val r = RectF(max(gutter, xOf(c.startMs)), top, min(width - gutter, max(xOf(c.startMs) + dp(14f), xOf(c.endMs))), top + clipH)
            if (r.right <= r.left) return@forEachIndexed
            fill.color = colors[c.kind] ?: 0xFF5B4BC4.toInt()
            canvas.drawRoundRect(r, dp(8f), dp(8f), fill)
            canvas.save()
            canvas.clipRect(r)
            val prefix = if (c.kind == LaneKind.AUDIO) "♪ " else ""
            canvas.drawText(prefix + c.label, r.left + dp(10f), r.centerY() + dp(4f), clipText)
            canvas.restore()
            if (li == selectedLane && ci == selectedClip) {
                canvas.drawRoundRect(r, dp(8f), dp(8f), outline)
                val hw = dp(4f)
                canvas.drawRoundRect(RectF(r.left, r.centerY() - dp(10f), r.left + hw, r.centerY() + dp(10f)), hw, hw, handle)
                canvas.drawRoundRect(RectF(r.right - hw, r.centerY() - dp(10f), r.right, r.centerY() + dp(10f)), hw, hw, handle)
            }
        }
    }

    // ---- touch ----
    private fun laneAt(y: Float): Int = if (y < rulerH) -1 else ((y - rulerH) / laneH).toInt()

    private fun seekTo(ms: Long) {
        playheadMs = ms.coerceIn(0L, durationMs)
        onSeek?.invoke(playheadMs)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (durationMs <= 0) return false
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = e.x
                val li = laneAt(e.y)
                val t = msAt(e.x).coerceAtLeast(0L)
                var hit = -1
                if (li in lanes.indices) {
                    selectedLane = li
                    hit = lanes[li].clips.indexOfFirst { t in it.startMs..it.endMs }
                }
                if (hit >= 0) {
                    val clip = lanes[li].clips[hit]
                    selectedClip = hit
                    dragging = true
                    dragSnapshot = snapshot()
                    originalStart = clip.startMs
                    originalEnd = clip.endMs
                    onSelection?.invoke(clip)
                } else {
                    seekTo(t)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    val c = selected() ?: return true
                    val delta = ((e.x - dragStartX) / pxPerMs()).toLong()
                    val len = originalEnd - originalStart
                    c.startMs = (originalStart + delta).coerceIn(0L, max(0L, durationMs - len))
                    c.endMs = c.startMs + len
                } else {
                    seekTo(msAt(e.x))
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val moved = selected()?.let { it.startMs != originalStart } ?: false
                    if (moved) { dragSnapshot?.let { undoStack.add(it) }; redoStack.clear() }
                    dragSnapshot = null
                    dragging = false
                } else if (e.action == MotionEvent.ACTION_UP) {
                    seekTo(msAt(e.x))
                }
                invalidate()
                return true
            }
        }
        return true
    }
}

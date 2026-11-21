package com.gatsmod.cut

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class LaneKind { VIDEO, AUDIO, TEXT, OVERLAY }

/** Transform keyframe. [tMs] is relative to the start of the clip on the timeline. */
data class Keyframe(var tMs: Long, var scale: Float = 1f, var x: Float = 0f, var y: Float = 0f, var rot: Float = 0f)

data class Chroma(
    var enabled: Boolean = false,
    var key: Int = 0xFF00B140.toInt(),
    var similarity: Float = 0.35f,
    var smooth: Float = 0.12f,
    var bg: Int = Color.BLACK
)

/**
 * One piece on the timeline.
 * VIDEO / AUDIO clips point at a source range [srcInMs, srcOutMs]; their timeline length is that range divided by [speed].
 * TEXT / OVERLAY clips are generated (text, emoji or image) and last [durMs].
 */
data class Clip(
    val id: Long,
    var kind: LaneKind,
    var startMs: Long = 0,
    var uri: String = "",
    var srcInMs: Long = 0,
    var srcOutMs: Long = 0,
    var srcTotalMs: Long = 0,
    var label: String = "",
    var speed: Float = 1f,
    var volume: Float = 1f,
    // look
    var filter: Int = 0,
    var brightness: Float = 0f,
    var contrast: Float = 1f,
    var saturation: Float = 1f,
    var blur: Float = 0f,
    var cropRatio: Float = 0f, // 0 = none, otherwise width/height
    var rotation: Int = 0, // clockwise degrees: 0, 90, 180, 270
    var flipH: Boolean = false,
    var flipV: Boolean = false,
    var temp: Float = 0f, // -1 cool .. +1 warm
    var hue: Float = 0f, // 0..360 shift
    var lum: Float = 0f, // -100..100 lightness
    var chroma: Chroma = Chroma(),
    var keyframes: MutableList<Keyframe> = mutableListOf(),
    // source info (video)
    var width: Int = 0,
    var height: Int = 0,
    var hasAudio: Boolean = true,
    var fps: Float = 30f,
    // text / sticker / image overlay
    var text: String = "",
    var color: Int = Color.WHITE,
    var sizeFrac: Float = 0.06f,
    var posX: Float = 0.5f,
    var posY: Float = 0.85f,
    var bold: Boolean = true,
    var font: Int = 0,
    var durMs: Long = 3000
) {
    val isMedia: Boolean get() = kind == LaneKind.VIDEO || kind == LaneKind.AUDIO
    val lengthMs: Long
        get() = if (isMedia) max(1L, ((srcOutMs - srcInMs) / speed).toLong()) else durMs
    val endMs: Long get() = startMs + lengthMs

    fun deepCopy(newId: Long = id): Clip =
        copy(id = newId, chroma = chroma.copy(), keyframes = keyframes.map { it.copy() }.toMutableList())

    /** Everything that changes how the clip looks/sounds in the preview (not its position). */
    fun paramSig(): String =
        "$filter|$brightness|$contrast|$saturation|$blur|$cropRatio|$speed|$volume|$rotation|$flipH|$flipV|$temp|$hue|$lum|" +
            "${chroma.enabled}|${chroma.key}|${chroma.similarity}|${chroma.smooth}|${chroma.bg}|" +
            keyframes.joinToString(",") { "${it.tMs}:${it.scale}:${it.x}:${it.y}:${it.rot}" }
}

object Keyframes {
    fun valueAt(list: List<Keyframe>, tMs: Long): Keyframe {
        if (list.isEmpty()) return Keyframe(tMs)
        val s = list.sortedBy { it.tMs }
        if (tMs <= s.first().tMs) return s.first().copy(tMs = tMs)
        if (tMs >= s.last().tMs) return s.last().copy(tMs = tMs)
        for (i in 0 until s.size - 1) {
            val a = s[i]
            val b = s[i + 1]
            if (tMs in a.tMs..b.tMs) {
                val f = if (b.tMs == a.tMs) 0f else (tMs - a.tMs).toFloat() / (b.tMs - a.tMs)
                fun l(x: Float, y: Float) = x + (y - x) * f
                return Keyframe(tMs, l(a.scale, b.scale), l(a.x, b.x), l(a.y, b.y), l(a.rot, b.rot))
            }
        }
        return s.last().copy(tMs = tMs)
    }
}

class Project {
    companion object { const val MIN_MS = 100L }

    var nextId = 1L
    var main = mutableListOf<Clip>()
    var audio = mutableListOf<Clip>()
    var text = mutableListOf<Clip>()
    var overlay = mutableListOf<Clip>()
    var selectedId = -1L

    /** Output picture shape (width/height). 0 = follow the first clip. */
    var canvasAspect = 0f

    /** Shape of the exported picture. */
    fun effectiveAspect(): Float {
        if (canvasAspect > 0f) return canvasAspect
        val f = main.firstOrNull() ?: return 16f / 9f
        if (f.cropRatio > 0f) return f.cropRatio
        if (f.width <= 0 || f.height <= 0) return 16f / 9f
        val a = f.width.toFloat() / f.height
        return if (f.rotation % 180 != 0) 1f / a else a
    }

    private class Snap(val main: List<Clip>, val audio: List<Clip>, val text: List<Clip>, val overlay: List<Clip>, val sel: Long)
    private val undoStack = ArrayDeque<Snap>()
    private val redoStack = ArrayDeque<Snap>()

    fun newId() = nextId++
    fun lane(k: LaneKind): MutableList<Clip> = when (k) {
        LaneKind.VIDEO -> main
        LaneKind.AUDIO -> audio
        LaneKind.TEXT -> text
        LaneKind.OVERLAY -> overlay
    }

    fun all(): List<Clip> = main + audio + text + overlay
    fun byId(id: Long): Clip? = all().firstOrNull { it.id == id }
    fun selected(): Clip? = byId(selectedId)
    fun totalMs(): Long = main.sumOf { it.lengthMs }
    fun isEmpty() = main.isEmpty()

    fun relayout() {
        var t = 0L
        main.forEach { it.startMs = t; t += it.lengthMs }
    }

    /** Playlist signature: changes when the main video sequence itself changes (not just its look). */
    fun mainSignature(): String = main.joinToString(";") { "${it.id}|${it.uri}|${it.srcInMs}|${it.srcOutMs}" }

    fun mainClipAt(ms: Long): Clip? = main.firstOrNull { ms >= it.startMs && ms < it.endMs } ?: main.lastOrNull()?.takeIf { ms >= it.endMs }

    // ---------------- history ----------------

    private fun snap() = Snap(main.map { it.deepCopy() }, audio.map { it.deepCopy() }, text.map { it.deepCopy() }, overlay.map { it.deepCopy() }, selectedId)

    fun checkpoint() {
        undoStack.addLast(snap())
        if (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Drops the last checkpoint (used when a drag gesture ended without changing anything). */
    fun discardCheckpoint() { undoStack.removeLastOrNull() }

    private fun restore(s: Snap) {
        main = s.main.toMutableList(); audio = s.audio.toMutableList(); text = s.text.toMutableList(); overlay = s.overlay.toMutableList()
        selectedId = if (byId(s.sel) != null) s.sel else -1L
    }

    fun undo(): Boolean {
        val s = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(snap()); restore(s); return true
    }

    fun redo(): Boolean {
        val s = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(snap()); restore(s); return true
    }

    // ---------------- adding ----------------

    fun addMain(c: Clip) { checkpoint(); main.add(c); relayout(); selectedId = c.id }

    fun addFree(c: Clip) { checkpoint(); lane(c.kind).add(c); selectedId = c.id }

    // ---------------- editing (each returns an error message or null) ----------------

    private fun target(atMs: Long): Clip? = selected() ?: main.firstOrNull { atMs > it.startMs && atMs < it.endMs }

    fun split(atMs: Long): String? {
        val c = target(atMs) ?: return "Select a clip first"
        if (atMs <= c.startMs + MIN_MS || atMs >= c.endMs - MIN_MS) return "Move the playhead inside the clip to cut"
        checkpoint()
        val lane = lane(c.kind)
        val tail = c.deepCopy(newId())
        val first = atMs - c.startMs
        if (c.isMedia) {
            val cut = c.srcInMs + (first * c.speed).toLong()
            c.srcOutMs = cut
            tail.srcInMs = cut
            tail.startMs = atMs
        } else {
            tail.startMs = atMs
            tail.durMs = c.durMs - first
            c.durMs = first
        }
        // keep keyframe animation continuous across the cut
        if (c.keyframes.isNotEmpty()) {
            val atCut = Keyframes.valueAt(c.keyframes, first)
            val head = c.keyframes.filter { it.tMs < first }.toMutableList()
            head.add(atCut.copy(tMs = first))
            val rest = c.keyframes.filter { it.tMs > first }.map { it.copy(tMs = it.tMs - first) }.toMutableList()
            rest.add(0, atCut.copy(tMs = 0))
            c.keyframes = head
            tail.keyframes = rest
        }
        lane.add(lane.indexOf(c) + 1, tail)
        if (c.kind == LaneKind.VIDEO) relayout()
        selectedId = c.id
        return null
    }

    fun trimStartTo(atMs: Long): String? {
        val c = selected() ?: return "Select a clip first"
        if (atMs <= c.startMs + MIN_MS || atMs >= c.endMs - MIN_MS) return "Move the playhead inside the clip to trim"
        checkpoint()
        trimBy(c, c.deepCopy(), true, atMs - c.startMs)
        return null
    }

    fun trimEndTo(atMs: Long): String? {
        val c = selected() ?: return "Select a clip first"
        if (atMs <= c.startMs + MIN_MS || atMs >= c.endMs - MIN_MS) return "Move the playhead inside the clip to trim"
        checkpoint()
        trimBy(c, c.deepCopy(), false, atMs - c.endMs)
        return null
    }

    /**
     * Trim from the original state [orig] by [deltaMs] timeline milliseconds (used by handle dragging).
     * Start side: positive delta cuts material off the start. End side: negative delta cuts material off the end.
     */
    fun trimBy(c: Clip, orig: Clip, startSide: Boolean, deltaMs: Long) {
        if (c.isMedia) {
            if (startSide) {
                val maxIn = orig.srcOutMs - (MIN_MS * orig.speed).toLong()
                val newIn = (orig.srcInMs + (deltaMs * orig.speed).toLong()).coerceIn(0L, max(0L, maxIn))
                c.srcInMs = newIn
                if (c.kind != LaneKind.VIDEO) c.startMs = orig.startMs + ((newIn - orig.srcInMs) / orig.speed).toLong()
                val shift = ((newIn - orig.srcInMs) / orig.speed).toLong()
                c.keyframes = orig.keyframes.map { it.copy(tMs = max(0L, it.tMs - shift)) }.toMutableList()
            } else {
                val minOut = orig.srcInMs + (MIN_MS * orig.speed).toLong()
                val limit = if (orig.srcTotalMs > 0) orig.srcTotalMs else Long.MAX_VALUE
                c.srcOutMs = (orig.srcOutMs + (deltaMs * orig.speed).toLong()).coerceIn(minOut, limit)
            }
            if (c.kind == LaneKind.VIDEO) relayout()
        } else {
            if (startSide) {
                val d = deltaMs.coerceIn(-orig.startMs, orig.durMs - MIN_MS)
                c.startMs = orig.startMs + d
                c.durMs = orig.durMs - d
            } else {
                c.durMs = max(MIN_MS, orig.durMs + deltaMs)
            }
        }
    }

    fun delete(): String? {
        val c = selected() ?: return "Select a clip first"
        checkpoint()
        lane(c.kind).remove(c)
        if (c.kind == LaneKind.VIDEO) relayout()
        selectedId = -1L
        return null
    }

    fun duplicate(): String? {
        val c = selected() ?: return "Select a clip first"
        checkpoint()
        val copy = c.deepCopy(newId())
        val lane = lane(c.kind)
        if (c.kind == LaneKind.VIDEO) {
            lane.add(lane.indexOf(c) + 1, copy)
            relayout()
        } else {
            copy.startMs = c.endMs
            lane.add(copy)
        }
        selectedId = copy.id
        return null
    }

    /** Joins a main-track clip with the next one if they are consecutive pieces of the same source. */
    fun merge(): String? {
        val c = selected() ?: return "Select a clip first"
        if (c.kind != LaneKind.VIDEO) return "Merge works on video clips"
        val i = main.indexOf(c)
        val n = main.getOrNull(i + 1) ?: return "There is no clip after this one"
        val sameSource = n.uri == c.uri && abs(n.srcInMs - c.srcOutMs) <= 80 && n.speed == c.speed
        if (!sameSource) return "Only pieces that were cut from the same video (same speed) can be merged"
        checkpoint()
        c.srcOutMs = n.srcOutMs
        c.keyframes.addAll(n.keyframes.map { it.copy(tMs = it.tMs + c.lengthMs) })
        main.remove(n)
        relayout()
        return null
    }

    fun moveFree(c: Clip, newStart: Long) {
        val limit = max(0L, totalMs() - c.lengthMs)
        c.startMs = newStart.coerceIn(0L, if (totalMs() > 0) limit else Long.MAX_VALUE / 4)
    }

    fun moveMain(c: Clip, toIndex: Int) {
        val from = main.indexOf(c)
        if (from < 0) return
        val to = toIndex.coerceIn(0, main.lastIndex)
        if (from == to) return
        main.removeAt(from)
        main.add(to, c)
        relayout()
    }

    fun setSpeed(c: Clip, speed: Float) {
        c.speed = speed.coerceIn(0.25f, 4f)
        if (c.kind == LaneKind.VIDEO) relayout()
    }

    // ---------------- keyframes ----------------

    fun setKeyframe(c: Clip, k: Keyframe) {
        c.keyframes.removeAll { abs(it.tMs - k.tMs) < 60 }
        c.keyframes.add(k)
        c.keyframes.sortBy { it.tMs }
    }

    fun clampToTimeline() {
        val total = totalMs()
        listOf(audio, text, overlay).forEach { l -> l.forEach { if (it.startMs > total && total > 0) it.startMs = max(0L, total - min(it.lengthMs, total)) } }
    }
}

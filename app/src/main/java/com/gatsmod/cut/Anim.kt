package com.gatsmod.cut

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** How a clip looks at one instant of an animation: size, shift (picture units, 2 = one full width), rotation (degrees) and brightness. */
data class Pose(val sx: Float = 1f, val sy: Float = 1f, val x: Float = 0f, val y: Float = 0f, val rot: Float = 0f, val gain: Float = 1f) {
    operator fun times(o: Pose) = Pose(sx * o.sx, sy * o.sy, x + o.x, y + o.y, rot + o.rot, gain * o.gain)
}

class AnimDef(val id: Int, val name: String, val glyph: String, val cats: String)

/** In / Out / Combo animations like CapCut's panel. Everything is a plain function of time, so preview and export match. */
object Anim {
    val CATS = listOf("Trending", "Basic", "Light", "Glitch", "Mask", "3D", "Vibration", "Zoom", "Slide", "Blur")

    val IN = listOf(
        AnimDef(1, "Zoom In", "⤢", "Trending,Basic,Zoom"), AnimDef(2, "Pop", "✦", "Trending,Basic"),
        AnimDef(3, "Bounce In", "◉", "Trending,Basic"), AnimDef(4, "Spin In", "↻", "Trending,Basic"),
        AnimDef(5, "Flash In", "☀", "Trending,Light"), AnimDef(6, "Fade In", "◐", "Basic,Light,Blur"),
        AnimDef(7, "Slide Left", "⇦", "Basic"), AnimDef(8, "Slide Right", "⇨", "Basic"),
        AnimDef(9, "Slide Up", "⇧", "Basic"), AnimDef(10, "Slide Down", "⇩", "Basic"),
        AnimDef(11, "Shrink In", "⤡", "Basic,Zoom"), AnimDef(12, "Blink In", "◌", "Light,Blur"),
        AnimDef(13, "Burn In", "✺", "Trending,Light"), AnimDef(14, "Glitch In", "▚", "Trending,Glitch"),
        AnimDef(16, "Stutter In", "▤", "Glitch"), AnimDef(22, "Jitter In", "▦", "Glitch"),
        AnimDef(17, "Flip X", "⇋", "3D"), AnimDef(18, "Flip Y", "⇅", "3D"),
        AnimDef(19, "Swing In", "⌒", "Trending,3D"), AnimDef(20, "Tilt In", "◢", "3D"),
        AnimDef(15, "Shake In", "≋", "Vibrate"), AnimDef(21, "Vibrate In", "∿", "Vibrate"), AnimDef(23, "Rumble In", "≈", "Vibrate"),
        AnimDef(24, "Circle Reveal", "●", "Trending,Mask"), AnimDef(25, "Square Reveal", "■", "Mask"), AnimDef(26, "Diamond Reveal", "◆", "Mask")
    )

    // Out animations replay an In animation backwards; [src] is the In id used.
    class OutDef(val def: AnimDef, val src: Int)
    val OUT = listOf(
        OutDef(AnimDef(1001, "Zoom Out", "⤡", "Trending,Basic,Zoom"), 1), OutDef(AnimDef(1002, "Pop Out", "✦", "Trending,Basic"), 2),
        OutDef(AnimDef(1003, "Bounce Out", "◉", "Trending,Basic"), 3), OutDef(AnimDef(1004, "Spin Out", "↺", "Trending,Basic"), 4),
        OutDef(AnimDef(1005, "Flash Out", "☀", "Trending,Light"), 5), OutDef(AnimDef(1006, "Fade Out", "◐", "Basic,Light,Blur"), 6),
        OutDef(AnimDef(1007, "Slide Left", "⇦", "Basic"), 8), OutDef(AnimDef(1008, "Slide Right", "⇨", "Basic"), 7),
        OutDef(AnimDef(1009, "Slide Up", "⇧", "Basic"), 10), OutDef(AnimDef(1010, "Slide Down", "⇩", "Basic"), 9),
        OutDef(AnimDef(1011, "Grow Out", "⤢", "Basic,Zoom"), 11), OutDef(AnimDef(1012, "Blink Out", "◌", "Light,Blur"), 12),
        OutDef(AnimDef(1013, "Burn Out", "✺", "Trending,Light"), 13), OutDef(AnimDef(1014, "Glitch Out", "▚", "Trending,Glitch"), 14),
        OutDef(AnimDef(1016, "Stutter Out", "▤", "Glitch"), 16), OutDef(AnimDef(1022, "Jitter Out", "▦", "Glitch"), 22),
        OutDef(AnimDef(1017, "Flip X Out", "⇋", "3D"), 17), OutDef(AnimDef(1018, "Flip Y Out", "⇅", "3D"), 18),
        OutDef(AnimDef(1019, "Swing Out", "⌒", "Trending,3D"), 19), OutDef(AnimDef(1020, "Tilt Out", "◣", "3D"), 20),
        OutDef(AnimDef(1015, "Shake Out", "≋", "Vibrate"), 15), OutDef(AnimDef(1021, "Vibrate Out", "∿", "Vibrate"), 21),
        OutDef(AnimDef(1023, "Rumble Out", "≈", "Vibrate"), 23),
        OutDef(AnimDef(1024, "Circle Close", "●", "Trending,Mask"), 24), OutDef(AnimDef(1025, "Square Close", "■", "Mask"), 25), OutDef(AnimDef(1026, "Diamond Close", "◆", "Mask"), 26)
    )

    val COMBO = listOf(
        AnimDef(101, "Pulse", "◎", "Trending,Basic"), AnimDef(102, "Heartbeat", "♥", "Trending,Basic"),
        AnimDef(107, "Zoom Drift", "⤢", "Trending,Basic,Zoom"), AnimDef(108, "Breathe", "◍", "Basic,Light,Zoom"),
        AnimDef(113, "Pan Right", "⇨", "Basic,Slide"), AnimDef(114, "Pan Left", "⇦", "Basic,Slide"), AnimDef(105, "Float", "☁", "Basic,Blur"),
        AnimDef(111, "Strobe", "☀", "Light"), AnimDef(112, "Glitch Loop", "▚", "Trending,Glitch"),
        AnimDef(110, "Wobble", "≀", "3D"), AnimDef(109, "Spin Loop", "↻", "3D"), AnimDef(115, "Pendulum", "⌒", "3D"), AnimDef(103, "Swing", "⌓", "3D"),
        AnimDef(106, "Shake", "≋", "Trending,Vibrate"), AnimDef(104, "Rock", "∿", "Vibrate")
    )

    private fun easeOut(p: Float): Float { val q = 1f - p; return 1f - q * q * q }
    private fun back(p: Float): Float { val q = p - 1f; return 1f + 2.70158f * q * q * q + 1.70158f * q * q }
    private fun hash(n: Int): Float { var h = n * 374761393 + 668265263; h = (h xor (h ushr 13)) * 1274126177; return ((h xor (h ushr 16)) and 0xFFFF) / 65535f }

    /** Pose of In animation [id] at progress [p] (0 = just started, 1 = finished and at rest). */
    fun inPose(id: Int, p0: Float): Pose {
        val p = p0.coerceIn(0f, 1f)
        val e = easeOut(p)
        val r = 1f - p
        return when (id) {
            1 -> Pose(sx = 0.4f + 0.6f * e, sy = 0.4f + 0.6f * e, gain = e)
            2 -> Pose(sx = 1.35f - 0.35f * e, sy = 1.35f - 0.35f * e, gain = min(1f, p * 3f))
            3 -> { val s = max(0.01f, back(p)); Pose(sx = s, sy = s) }
            4 -> Pose(sx = 0.3f + 0.7f * e, sy = 0.3f + 0.7f * e, rot = 270f * (1f - e), gain = e)
            5 -> Pose(sx = 1.05f - 0.05f * e, sy = 1.05f - 0.05f * e, gain = 1f + 3f * (1f - e) * (1f - e))
            6 -> Pose(gain = p)
            7 -> Pose(x = 2f * (1f - e))
            8 -> Pose(x = -2f * (1f - e))
            9 -> Pose(y = -2f * (1f - e))
            10 -> Pose(y = 2f * (1f - e))
            11 -> Pose(sx = 1.7f - 0.7f * e, sy = 1.7f - 0.7f * e, gain = e)
            12 -> Pose(gain = if (p < 0.8f && floor(p * 10f).toInt() % 2 == 0) 0.2f else 1f)
            13 -> Pose(sx = 1.1f - 0.1f * e, sy = 1.1f - 0.1f * e, gain = e * (1f + 1.5f * sin(PI.toFloat() * p)))
            14 -> { val a = r; val n = hash(floor(p * 24f).toInt()); val n2 = hash(floor(p * 24f).toInt() + 99)
                Pose(sx = 1f + (n2 - 0.5f) * 0.3f * a, x = (n - 0.5f) * 0.5f * a, gain = 1f - (if (n > 0.7f) 0.5f * a else 0f)) }
            15 -> Pose(x = sin(p * 60f) * 0.08f * r, rot = sin(p * 50f) * 4f * r)
            16 -> Pose(gain = if (p < 0.7f && floor(p * 16f).toInt() % 2 == 0) 0f else 1f)
            17 -> Pose(sx = max(0.01f, sin(e * PI.toFloat() / 2f)))
            18 -> Pose(sy = max(0.01f, sin(e * PI.toFloat() / 2f)))
            19 -> Pose(rot = 40f * r * r * cos(p * 9f), gain = min(1f, p * 4f))
            20 -> Pose(rot = -30f * (1f - e), x = -0.6f * (1f - e), gain = e)
            21 -> Pose(x = sin(p * 120f) * 0.04f * r, y = cos(p * 140f) * 0.04f * r)
            22 -> { val n = hash(floor(p * 30f).toInt() + 7); Pose(x = (n - 0.5f) * 0.12f * r, y = (hash(floor(p * 30f).toInt() + 31) - 0.5f) * 0.06f * r, gain = 1f - 0.4f * r * (if (n > 0.6f) 1f else 0f)) }
            23 -> { val s = 1f + 0.05f * sin(p * 80f) * r; Pose(sx = s, sy = s, rot = sin(p * 70f) * 2f * r) }
            else -> Pose()
        }
    }

    /** Pose of a looping Combo animation [id] at [tMs] into a clip that lasts [lenMs]. */
    fun comboPose(id: Int, tMs: Long, lenMs: Long): Pose {
        val t = tMs.toFloat()
        val tau = (2.0 * PI).toFloat()
        fun w(period: Float) = tau * t / period
        return when (id) {
            101 -> { val s = 1f + 0.1f * (0.5f - 0.5f * cos(w(800f))); Pose(sx = s, sy = s) }
            102 -> { val pw = (t % 1000f) / 1000f
                val s = 1f + 0.1f * (exp(-((pw - 0.1f) / 0.06f).pow(2)) + 0.7f * exp(-((pw - 0.3f) / 0.06f).pow(2))); Pose(sx = s, sy = s) }
            103 -> Pose(sx = 1.08f, sy = 1.08f, rot = 6f * sin(w(1600f)))
            104 -> Pose(sx = 1.06f, sy = 1.06f, rot = 3f * sin(w(500f)))
            105 -> Pose(sx = 1.08f, sy = 1.08f, x = 0.02f * cos(w(3000f)), y = 0.04f * sin(w(2000f)))
            106 -> Pose(sx = 1.08f, sy = 1.08f, x = 0.025f * sin(w(90f)), y = 0.025f * cos(w(110f)))
            107 -> { val s = 1f + 0.25f * (t / max(1f, lenMs.toFloat())).coerceIn(0f, 1f); Pose(sx = s, sy = s) }
            108 -> { val s = 1.08f + 0.07f * sin(w(2400f)); Pose(sx = s, sy = s) }
            109 -> Pose(rot = 360f * ((t % 3000f) / 3000f))
            110 -> Pose(sx = 1f + 0.06f * sin(w(700f)), sy = 1f - 0.06f * sin(w(700f)))
            111 -> Pose(gain = if (floor(t / 150f).toInt() % 2 == 0) 1f else 0.55f)
            112 -> { val ph = t % 1200f
                if (ph > 200f) Pose() else { val k = floor(t / 40f).toInt(); val n = hash(k)
                    Pose(sx = 1f + (hash(k + 99) - 0.5f) * 0.2f, x = (n - 0.5f) * 0.3f, gain = if (n > 0.7f) 0.6f else 1f) } }
            113 -> { val f = (t / max(1f, lenMs.toFloat())).coerceIn(0f, 1f); Pose(sx = 1.2f, sy = 1.2f, x = -0.1f + 0.2f * f) }
            114 -> { val f = (t / max(1f, lenMs.toFloat())).coerceIn(0f, 1f); Pose(sx = 1.2f, sy = 1.2f, x = 0.1f - 0.2f * f) }
            115 -> Pose(sx = 1.12f, sy = 1.12f, rot = 12f * sin(w(2000f)), x = 0.05f * sin(w(2000f)))
            else -> Pose()
        }
    }

    fun isMask(id: Int) = id in 24..26 || id in 1024..1026

    private fun durs(animIn: Int, inMs: Long, animOut: Int, outMs: Long, lenMs: Long): LongArray {
        var a = if (animIn != 0) inMs else 0L
        var b = if (animOut != 0) outMs else 0L
        if (a + b > lenMs && a + b > 0) { val k = lenMs.toFloat() / (a + b); a = (a * k).toLong(); b = (b * k).toLong() }
        return longArrayOf(a, b)
    }

    /** Shape (0 = none, 1 circle, 2 rectangle, 3 diamond) and size of the reveal mask for the shader at [tMs]. */
    fun maskState(animIn: Int, inMs: Long, animOut: Int, outMs: Long, tMs: Long, lenMs: Long): FloatArray {
        val d = durs(animIn, inMs, animOut, outMs, lenMs)
        var id = 0
        var p = 1f
        if (isMask(animIn) && d[0] > 0 && tMs < d[0]) { id = animIn; p = tMs.toFloat() / d[0] }
        else if (isMask(animOut) && d[1] > 0 && tMs > lenMs - d[1]) { id = outSrc(animOut); p = (lenMs - tMs).toFloat() / d[1] }
        if (id == 0) return floatArrayOf(0f, 1f)
        val shape = id - 23                       // 24,25,26 -> 1,2,3
        val big = if (shape == 2) 1.9f else 2.8f
        return floatArrayOf(shape.toFloat(), max(0.02f, easeOut(p.coerceIn(0f, 1f)) * big))
    }

    fun outSrc(id: Int): Int = OUT.firstOrNull { it.def.id == id }?.src ?: 0

    /**
     * The combined pose at [tMs] (ms since the clip start on the timeline) for a clip [lenMs] long.
     * In and Out lengths are shortened if together they would be longer than the clip.
     */
    fun pose(animIn: Int, inMs: Long, animOut: Int, outMs: Long, combo: Int, tMs: Long, lenMs: Long): Pose {
        val d = durs(animIn, inMs, animOut, outMs, lenMs)
        val a = d[0]
        val b = d[1]
        var p = Pose()
        if (animIn != 0 && a > 0 && tMs < a) p *= inPose(animIn, tMs.toFloat() / a)
        if (animOut != 0 && b > 0 && tMs > lenMs - b) p *= inPose(outSrc(animOut), (lenMs - tMs).toFloat() / b)
        if (combo != 0) p *= comboPose(combo, tMs, lenMs)
        return p
    }
}

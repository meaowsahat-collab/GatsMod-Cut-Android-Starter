package com.gatsmod.cut

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Speed curves for the movement between two keyframes (CapCut calls them Graphs). */
object Easing {
    val NAMES = listOf(
        "None", "Linear", "Ease In", "Ease Out", "Ease In-Out", "Quad In", "Quad Out", "Cubic In", "Cubic Out",
        "Quart In", "Quart Out", "Expo In", "Expo Out", "Back In", "Back Out", "Elastic", "Bounce",
        "Hero", "Montage", "Pendulum", "Flash", "Hold",
        "Circ In", "Circ Out", "Quint In", "Quint Out", "Expo In-Out", "Back In-Out", "Elastic In", "Bounce In",
        "Bounce In-Out", "Snap", "Whip", "Rubber", "Heartbeat", "Steps", "Wave", "Slingshot", "Speed Ramp",
        "Smooth", "Punch", "Swing", "Cinematic", "Dramatic", "Launch"
    )

    /** Groups shown as tabs in the Graphs panel (ids index into NAMES). */
    val CATEGORIES: List<Pair<String, List<Int>>> = listOf(
        "Basic" to listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
        "Smooth" to listOf(39, 17, 4, 42, 43, 11, 12, 26, 22, 23, 24, 25),
        "Dynamic" to listOf(31, 32, 40, 41, 44, 13, 14, 27, 37, 20, 21, 35),
        "Bounce" to listOf(16, 29, 30, 15, 28, 33, 19, 36),
        "Pro" to listOf(18, 38, 34, 42, 43, 44, 37)
    )

    /** CSS-style cubic bezier presets: x1, y1, x2, y2. */
    private val BEZ = mapOf(
        39 to floatArrayOf(0.4f, 0f, 0.2f, 1f), 40 to floatArrayOf(0.2f, 1.6f, 0.4f, 1f), 41 to floatArrayOf(0.5f, -0.6f, 0.5f, 1.6f),
        42 to floatArrayOf(0.77f, 0f, 0.175f, 1f), 43 to floatArrayOf(0.9f, 0f, 0.1f, 1f), 44 to floatArrayOf(0.6f, -0.3f, 0.2f, 1f)
    )

    private fun bezier(b: FloatArray, x: Float): Float {
        var t = x
        for (i in 0 until 8) {
            val cx = 3 * (1 - t) * (1 - t) * t * b[0] + 3 * (1 - t) * t * t * b[2] + t * t * t - x
            val d = 3 * (1 - t) * (1 - t) * b[0] + 6 * (1 - t) * t * (b[2] - b[0]) + 3 * t * t * (1 - b[2])
            if (kotlin.math.abs(d) < 1e-5f) break
            t = (t - cx / d).coerceIn(0f, 1f)
        }
        return 3 * (1 - t) * (1 - t) * t * b[1] + 3 * (1 - t) * t * t * b[3] + t * t * t
    }

    const val CUSTOM_BIT = 1 shl 30
    fun isCustom(id: Int) = id and CUSTOM_BIT != 0
    fun pack(x1: Float, y1: Float, x2: Float, y2: Float): Int {
        fun qx(v: Float) = (v.coerceIn(0f, 1f) * 127f).toInt()
        fun qy(v: Float) = ((v.coerceIn(-1f, 2f) + 1f) / 3f * 127f).toInt()
        return CUSTOM_BIT or (qx(x1) shl 21) or (qy(y1) shl 14) or (qx(x2) shl 7) or qy(y2)
    }
    fun unpack(id: Int): FloatArray {
        fun dx(q: Int) = q / 127f
        fun dy(q: Int) = q / 127f * 3f - 1f
        return floatArrayOf(dx((id shr 21) and 127), dy((id shr 14) and 127), dx((id shr 7) and 127), dy(id and 127))
    }

    private fun smooth(x: Float) = x * x * (3f - 2f * x)
    private fun lerpPts(pts: Array<FloatArray>, x: Float): Float {
        for (i in 0 until pts.size - 1) if (x <= pts[i + 1][0]) {
            val f = (x - pts[i][0]) / (pts[i + 1][0] - pts[i][0])
            return pts[i][1] + (pts[i + 1][1] - pts[i][1]) * smooth(f.coerceIn(0f, 1f))
        }
        return 1f
    }

    private fun bounce(x: Float): Float {
        val n = 7.5625f; val d = 2.75f
        return when {
            x < 1 / d -> n * x * x
            x < 2 / d -> { val t = x - 1.5f / d; n * t * t + 0.75f }
            x < 2.5f / d -> { val t = x - 2.25f / d; n * t * t + 0.9375f }
            else -> { val t = x - 2.625f / d; n * t * t + 0.984375f }
        }
    }

    /** t in 0..1 -> eased progress (may overshoot for Back, Elastic, Pendulum). */
    fun apply(id: Int, t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        if (isCustom(id)) return bezier(unpack(id), x)
        BEZ[id]?.let { return bezier(it, x) }
        return when (id) {
            0, 1 -> x
            2 -> 1f - cos(x * PI.toFloat() / 2f)
            3 -> sin(x * PI.toFloat() / 2f)
            4 -> -(cos(PI.toFloat() * x) - 1f) / 2f
            5 -> x * x
            6 -> 1f - (1f - x) * (1f - x)
            7 -> x * x * x
            8 -> 1f - (1f - x).pow(3)
            9 -> x.pow(4)
            10 -> 1f - (1f - x).pow(4)
            11 -> if (x <= 0f) 0f else 2f.pow(10f * x - 10f)
            12 -> if (x >= 1f) 1f else 1f - 2f.pow(-10f * x)
            13 -> { val c1 = 1.70158f; (c1 + 1f) * x * x * x - c1 * x * x }
            14 -> { val c1 = 1.70158f; val y = x - 1f; 1f + (c1 + 1f) * y * y * y + c1 * y * y }
            15 -> if (x <= 0f) 0f else if (x >= 1f) 1f else (2f.pow(-10f * x) * sin((x * 10f - 0.75f) * (2f * PI.toFloat() / 3f)) + 1f)
            16 -> bounce(x)
            17 -> x * x * x * (x * (x * 6f - 15f) + 10f)
            18 -> if (x < 0.7f) 0.15f * (x / 0.7f) * (x / 0.7f) else { val y = (x - 0.7f) / 0.3f; 0.15f + 0.85f * (1f - (1f - y).pow(3)) }
            19 -> { val d = exp(-5f * x); val v = 1f - d * cos(6f * PI.toFloat() * x); if (x >= 1f) 1f else v }
            20 -> if (x < 0.85f) 0f else { val y = (x - 0.85f) / 0.15f; y * y }
            21 -> if (x >= 1f) 1f else 0f
            22 -> 1f - kotlin.math.sqrt(1f - x * x)
            23 -> kotlin.math.sqrt(1f - (x - 1f) * (x - 1f))
            24 -> x.pow(5)
            25 -> 1f - (1f - x).pow(5)
            26 -> if (x <= 0f) 0f else if (x >= 1f) 1f else if (x < 0.5f) 2f.pow(20f * x - 10f) / 2f else (2f - 2f.pow(-20f * x + 10f)) / 2f
            27 -> { val c = 1.70158f * 1.525f; if (x < 0.5f) ((2f * x).pow(2) * ((c + 1f) * 2f * x - c)) / 2f else ((2f * x - 2f).pow(2) * ((c + 1f) * (x * 2f - 2f) + c) + 2f) / 2f }
            28 -> if (x <= 0f) 0f else if (x >= 1f) 1f else -(2f.pow(10f * x - 10f) * sin((x * 10f - 10.75f) * (2f * PI.toFloat() / 3f)))
            29 -> 1f - bounce(1f - x)
            30 -> if (x < 0.5f) (1f - bounce(1f - 2f * x)) / 2f else (1f + bounce(2f * x - 1f)) / 2f
            31 -> 1f - (1f - x).pow(6)
            32 -> { val c1 = 3.5f; val y = x - 1f; 1f + (c1 + 1f) * y * y * y + c1 * y * y }
            33 -> if (x >= 1f) 1f else 1f - exp(-7f * x) * cos(4f * PI.toFloat() * x)
            34 -> lerpPts(arrayOf(floatArrayOf(0f, 0f), floatArrayOf(0.2f, 0.6f), floatArrayOf(0.35f, 0.3f), floatArrayOf(0.6f, 1f), floatArrayOf(0.8f, 0.9f), floatArrayOf(1f, 1f)), x)
            35 -> { if (x >= 1f) 1f else { val sx = x * 5f; val i = sx.toInt(); (i + smooth(((sx - i) * 3f).coerceAtMost(1f))) / 5f } }
            36 -> x + 0.1f * sin(4f * PI.toFloat() * x)
            37 -> if (x < 0.3f) -0.2f * smooth(x / 0.3f) else -0.2f + 1.2f * (1f - (1f - (x - 0.3f) / 0.7f).pow(4))
            38 -> if (x < 0.35f) 0.1f * (x / 0.35f) * (x / 0.35f) else if (x < 0.65f) 0.1f + 0.8f * (x - 0.35f) / 0.3f else 0.9f + 0.1f * (1f - (1f - (x - 0.65f) / 0.35f).pow(2))
            else -> x
        }
    }
}

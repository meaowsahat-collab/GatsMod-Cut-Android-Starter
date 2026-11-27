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
        "Hero", "Montage", "Pendulum", "Flash", "Hold"
    )

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
            else -> x
        }
    }
}

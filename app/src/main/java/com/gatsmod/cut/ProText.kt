package com.gatsmod.cut

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 100 pro text animations = 20 looping motions x 5 looks. Looks use textFx 10..14, motions live in Clip.textMotion (1..20). */
object ProText {
    val LOOKS = listOf("Inferno", "Cyber Neon", "RGB Glitch", "Comic Stroke", "Holo Gradient")
    class Motion(val name: String, val speedMs: Int, val alt: Boolean)
    val MOTIONS = listOf(
        Motion("Fire Flame", 700, true), Motion("Neon Pulse", 1200, true), Motion("Cyber Glitch", 450, false),
        Motion("Cinema Track", 2000, true), Motion("Ocean Wave", 1400, false), Motion("Power Pop", 1000, false),
        Motion("Elastic Jelly", 1300, false), Motion("Pendulum", 1500, false), Motion("3D Y-Turn", 1800, false),
        Motion("3D X-Tilt", 1800, false), Motion("Side Bounce", 1100, false), Motion("Gravity Drop", 1200, false),
        Motion("Speed Skew", 1100, false), Motion("Strobe Flash", 900, false), Motion("Zoom Breath", 1500, false),
        Motion("Beat Drop", 1100, false), Motion("Smooth Float", 2000, false), Motion("Bass Shake", 350, false),
        Motion("Impact Stamp", 1200, false), Motion("RGB Flow", 2200, true)
    )
    const val COUNT = 100

    fun title(i: Int) = "${i + 1}. ${LOOKS[i % 5]} • ${MOTIONS[i / 5].name}"

    fun apply(c: Clip, i: Int) {
        val look = i % 5
        c.textMotion = i / 5 + 1
        c.textFx = 10 + look
        c.bgColor = 0
        c.bold = true
        c.italic = look == 2
        when (look) {
            0 -> { c.color = 0xFFFFF3B0.toInt(); c.strokeColor = 0 }
            1 -> { c.color = 0xFFFFFFFF.toInt(); c.strokeColor = 0 }
            2 -> { c.color = 0xFFFFFFFF.toInt(); c.strokeColor = 0 }
            3 -> { c.color = 0xFFFDE047.toInt(); c.strokeColor = 0xFF0F172A.toInt(); c.strokeW = 0.04f }
            else -> { c.color = 0xFFFFFFFF.toInt(); c.strokeColor = 0 }
        }
    }

    private const val K = 2f / 440f   // one CSS pixel of the original studio, in picture units

    private fun ss(x: Float) = x * x * (3f - 2f * x)
    /** Keyframe interpolation with ease-in-out between keys. */
    private fun kf(u: Float, vararg k: Float): Float {
        var i = 0
        while (i + 2 < k.size && u > k[i + 2]) i += 2
        val p0 = k[i]; val v0 = k[i + 1]
        if (i + 3 >= k.size) return v0
        val p1 = k[i + 2]; val v1 = k[i + 3]
        if (p1 <= p0) return v1
        val f = ((u - p0) / (p1 - p0)).coerceIn(0f, 1f)
        return v0 + (v1 - v0) * ss(f)
    }

    /** Looping pose of motion [m] (1..20) at local time [tMs]. */
    fun pose(m: Int, tMs: Long): Pose {
        if (m !in 1..20) return Pose()
        val d = MOTIONS[m - 1]
        val t = tMs.coerceAtLeast(0L).toFloat()
        val u = (t % d.speedMs) / d.speedMs
        val s = (0.5f - 0.5f * cos(PI.toFloat() * t / d.speedMs))   // 0..1..0, period 2*speed (CSS alternate)
        return when (m) {
            1 -> Pose(sx = 1f + 0.06f * s, sy = 1f + 0.06f * s, y = 3f * K * s, gain = 0.85f + 0.15f * s)
            2 -> Pose(sx = 0.97f + 0.07f * s, sy = 0.97f + 0.07f * s, gain = 0.7f + 0.3f * s)
            3 -> Pose(x = kf(u, 0f, 0f, .2f, -3f, .4f, 3f, .6f, -2f, .8f, 2f, 1f, 0f) * K, y = -kf(u, 0f, 0f, .2f, 1f, .4f, -1f, .6f, -2f, .8f, 2f, 1f, 0f) * K)
            4 -> Pose(sx = 1f + 0.12f * s, gain = 0.75f + 0.25f * s)
            5 -> Pose(y = -kf(u, 0f, 0f, .25f, -6f, .75f, 4f, 1f, 0f) * K, rot = kf(u, 0f, 0f, .25f, -3f, .75f, 3f, 1f, 0f))
            6 -> { val v = kf(u, 0f, 1f, .5f, 1.18f, 1f, 1f); Pose(sx = v, sy = v) }
            7 -> Pose(sx = kf(u, 0f, 1f, .3f, 1.25f, .5f, .85f, .7f, 1.08f, 1f, 1f), sy = kf(u, 0f, 1f, .3f, .75f, .5f, 1.15f, .7f, .92f, 1f, 1f))
            8 -> Pose(rot = kf(u, 0f, -8f, .5f, 8f, 1f, -8f))
            9 -> Pose(sx = cos(kf(u, 0f, -18f, .5f, 18f, 1f, -18f) * PI.toFloat() / 180f))
            10 -> Pose(sy = cos(kf(u, 0f, -18f, .5f, 18f, 1f, -18f) * PI.toFloat() / 180f))
            11 -> Pose(x = kf(u, 0f, -7f, .5f, 7f, 1f, -7f) * K)
            12 -> Pose(y = -kf(u, 0f, 0f, .4f, -11f, .7f, 3f, 1f, 0f) * K)
            13 -> { val a = kf(u, 0f, 0f, .35f, -16f, .7f, 10f, 1f, 0f); Pose(sx = cos(a * PI.toFloat() / 180f), rot = a * 0.35f) }   // approximated skew
            14 -> Pose(gain = kf(u, 0f, 1f, .49f, 1f, .5f, .25f, .8f, .25f, 1f, 1f))
            15 -> { val v = kf(u, 0f, .88f, .5f, 1.16f, 1f, .88f); Pose(sx = v, sy = v) }
            16 -> { val v = kf(u, 0f, 1f, .2f, 1.26f, .4f, .94f, .6f, 1.16f, 1f, 1f); Pose(sx = v, sy = v) }
            17 -> Pose(y = -kf(u, 0f, 0f, .5f, -8f, 1f, 0f) * K)
            18 -> Pose(x = kf(u, 0f, 0f, .25f, -2f, .5f, 2f, .75f, -2f, 1f, 0f) * K, y = -kf(u, 0f, 0f, .25f, -2f, .5f, 2f, .75f, 2f, 1f, 0f) * K)
            19 -> { val v = kf(u, 0f, 1f, .5f, 1.15f, 1f, 1f); Pose(sx = v, sy = v, rot = kf(u, 0f, -2f, .5f, 2f, 1f, -2f)) }
            else -> Pose(sx = 1f + 0.03f * s, sy = 1f + 0.03f * s)   // RGB Flow (colour drift not animated)
        }
    }
}

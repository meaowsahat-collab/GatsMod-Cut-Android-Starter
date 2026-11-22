package com.gatsmod.cut

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Camera-shake estimation and smoothing. Pure maths, no Android classes. */
object StabMath {
    /**
     * How far the picture content moved from frame [a] to frame [b] (both grayscale w*h, 0..255) as (dx, dy) in pixels,
     * found by block matching over +-[range] pixels with a sub-pixel refinement.
     */
    fun shift(a: IntArray, b: IntArray, w: Int, h: Int, range: Int): FloatArray {
        val m = range + 1
        if (w - 2 * m < 8 || h - 2 * m < 8) return floatArrayOf(0f, 0f)
        val n = 2 * range + 1
        val sad = LongArray(n * n)
        for (dy in -range..range) for (dx in -range..range) {
            var s = 0L
            var y = m
            while (y < h - m) {
                val ra = y * w
                val rb = (y + dy) * w + dx
                var x = m
                while (x < w - m) {
                    s += abs(a[ra + x] - b[rb + x])
                    x += 2
                }
                y += 2
            }
            sad[(dy + range) * n + dx + range] = s
        }
        var best = (range) * n + range
        for (i in sad.indices) if (sad[i] < sad[best]) best = i
        val bx = best % n
        val by = best / n
        fun refine(lo: Long, c: Long, hi: Long): Float {
            val d = (lo - 2 * c + hi).toFloat()
            return if (d <= 0f) 0f else (0.5f * (lo - hi) / d).coerceIn(-0.5f, 0.5f)
        }
        var fx = 0f
        var fy = 0f
        if (bx in 1 until n - 1) fx = refine(sad[best - 1], sad[best], sad[best + 1])
        if (by in 1 until n - 1) fy = refine(sad[best - n], sad[best], sad[best + n])
        return floatArrayOf(bx - range + fx, by - range + fy)
    }

    /**
     * [moves] are per-step content motions as fractions of the frame (dx/w, dy/h); step [stepMs] apart.
     * Returns keyframes (tMs = ms from the first sample) that cancel the shake: x/y are in picture units (-1..1 across the
     * frame, y up) and scale is the zoom that hides the borders. [window] is the smoothing length in samples.
     */
    fun plan(moves: List<FloatArray>, stepMs: Long, window: Int, maxZoom: Float = 1.25f): List<Keyframe> {
        val n = moves.size + 1
        val cx = FloatArray(n)
        val cy = FloatArray(n)
        for (i in 1 until n) { cx[i] = cx[i - 1] + moves[i - 1][0]; cy[i] = cy[i - 1] + moves[i - 1][1] }
        val tx = FloatArray(n)
        val ty = FloatArray(n)
        val r = max(1, window / 2)
        for (i in 0 until n) {
            var sx = 0f; var sy = 0f; var k = 0
            for (j in max(0, i - r)..min(n - 1, i + r)) { sx += cx[j]; sy += cy[j]; k++ }
            // content should sit at the smoothed spot: shift by (smoothed - actual); 2x to go from fraction to NDC, y flips
            tx[i] = 2f * (sx / k - cx[i])
            ty[i] = -2f * (sy / k - cy[i])
        }
        var need = 0f
        for (i in 0 until n) need = max(need, max(abs(tx[i]), abs(ty[i])))
        val zoom = (1f + need).coerceAtMost(maxZoom)
        val lim = zoom - 1f
        return (0 until n).map { Keyframe(it * stepMs, zoom, tx[it].coerceIn(-lim, lim), ty[it].coerceIn(-lim, lim), 0f) }
    }
}

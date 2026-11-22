package com.gatsmod.cut

import kotlin.math.max
import kotlin.math.min

/** Mask helpers for background removal. Pure maths, no Android classes. */
object MaskMath {
    /** Turns a soft 0..1 "is this the person" value into a clean edge: below [lo] = background, above [hi] = person. */
    fun edge(v: Float, lo: Float = 0.3f, hi: Float = 0.7f): Float {
        val t = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Blends this frame's mask with the previous one to reduce flicker. */
    fun smooth(cur: FloatArray, prev: FloatArray?, keepPrev: Float = 0.35f) {
        if (prev == null || prev.size != cur.size) return
        for (i in cur.indices) cur[i] = cur[i] * (1f - keepPrev) + prev[i] * keepPrev
    }

    /** Bilinear lookup of a mask (mw x mh) at pixel (x, y) of a picture of size w x h. */
    fun sample(mask: FloatArray, mw: Int, mh: Int, x: Int, y: Int, w: Int, h: Int): Float {
        val fx = ((x + 0.5f) * mw / w - 0.5f).coerceIn(0f, (mw - 1).toFloat())
        val fy = ((y + 0.5f) * mh / h - 0.5f).coerceIn(0f, (mh - 1).toFloat())
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(mw - 1, x0 + 1); val y1 = min(mh - 1, y0 + 1)
        val ax = fx - x0; val ay = fy - y0
        val top = mask[y0 * mw + x0] * (1 - ax) + mask[y0 * mw + x1] * ax
        val bot = mask[y1 * mw + x0] * (1 - ax) + mask[y1 * mw + x1] * ax
        return top * (1 - ay) + bot * ay
    }

    /** Mixes two ARGB pixels: [a] weighted by [alpha], [b] by the rest. */
    fun mix(a: Int, b: Int, alpha: Float): Int {
        val ia = (alpha * 256f).toInt().coerceIn(0, 256)
        val ib = 256 - ia
        val r = (((a shr 16) and 255) * ia + ((b shr 16) and 255) * ib) shr 8
        val g = (((a shr 8) and 255) * ia + ((b shr 8) and 255) * ib) shr 8
        val bl = ((a and 255) * ia + (b and 255) * ib) shr 8
        return (0xFF shl 24) or (min(255, r) shl 16) or (min(255, g) shl 8) or min(255, bl)
    }
}

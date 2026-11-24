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

    /** Mean over a (2r+1)^2 window, using an integral image. */
    fun boxMean(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val iw = w + 1
        val integ = DoubleArray(iw * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += src[y * w + x]
                integ[(y + 1) * iw + (x + 1)] = integ[y * iw + (x + 1)] + row
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
                val sum = integ[y1 * iw + x1] - integ[y0 * iw + x1] - integ[y1 * iw + x0] + integ[y0 * iw + x0]
                out[y * w + x] = (sum / ((x1 - x0) * (y1 - y0))).toFloat()
            }
        }
        return out
    }

    /** Guided filter (He et al.): makes [p] follow the edges of the picture [guide]. */
    fun guided(guide: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val meanI = boxMean(guide, w, h, r)
        val meanP = boxMean(p, w, h, r)
        val ii = FloatArray(n) { guide[it] * guide[it] }
        val ip = FloatArray(n) { guide[it] * p[it] }
        val corrI = boxMean(ii, w, h, r)
        val corrIp = boxMean(ip, w, h, r)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val v = corrI[i] - meanI[i] * meanI[i]
            val c = corrIp[i] - meanI[i] * meanP[i]
            a[i] = c / (v + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val ma = boxMean(a, w, h, r)
        val mb = boxMean(b, w, h, r)
        return FloatArray(n) { (ma[it] * guide[it] + mb[it]).coerceIn(0f, 1f) }
    }

    /** Stretches the mask to the picture size and snaps its edge to the picture's own edges. Returns a w x h alpha. */
    fun refine(mask: FloatArray, mw: Int, mh: Int, lum: FloatArray, w: Int, h: Int, radius: Int, eps: Float): FloatArray {
        val up = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) up[y * w + x] = sample(mask, mw, mh, x, y, w, h)
        return guided(lum, up, w, h, radius, eps)
    }
}

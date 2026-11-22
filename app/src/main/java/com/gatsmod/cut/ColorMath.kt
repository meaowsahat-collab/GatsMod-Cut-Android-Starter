package com.gatsmod.cut

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Picture statistics and the maths that turns them into Adjust values (Auto adjust, Color correction, Color match). */
object ColorMath {
    class Stats(val r: Float, val g: Float, val b: Float, val luma: Float, val std: Float, val lo: Float, val hi: Float, val sat: Float)

    fun stats(bmp: Bitmap): Stats {
        val w = bmp.width
        val h = bmp.height
        val step = max(1, min(w, h) / 80)
        var n = 0
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var sl = 0.0; var sl2 = 0.0; var ss = 0.0
        val hist = IntArray(64)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bmp.getPixel(x, y)
                val r = Color.red(p) / 255f
                val g = Color.green(p) / 255f
                val b = Color.blue(p) / 255f
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                sr += r; sg += g; sb += b; sl += l; sl2 += l * l
                ss += if (mx > 0.001f) (mx - mn) / mx else 0f
                hist[(l * 63f).toInt().coerceIn(0, 63)]++
                n++
                x += step
            }
            y += step
        }
        if (n == 0) return Stats(0.5f, 0.5f, 0.5f, 0.5f, 0.2f, 0f, 1f, 0.3f)
        val mean = (sl / n).toFloat()
        val std = sqrt(max(0.0, sl2 / n - (sl / n) * (sl / n))).toFloat()
        var acc = 0
        var lo = 0f
        var hi = 1f
        var gotLo = false
        for (i in 0 until 64) {
            acc += hist[i]
            if (!gotLo && acc >= n * 0.02) { lo = i / 63f; gotLo = true }
            if (acc >= n * 0.98) { hi = i / 63f; break }
        }
        return Stats((sr / n).toFloat(), (sg / n).toFloat(), (sb / n).toFloat(), mean, std, lo, hi, (ss / n).toFloat())
    }

    private fun contrastParam(f: Float): Float = 1f + ((f * 1.0001f - 1f) / (f + 1f)).coerceIn(-0.5f, 0.5f)

    private fun tempFor(s: Stats): Float = (4f * (s.b - s.r) / (s.b + s.r + 1e-4f)).coerceIn(-0.6f, 0.6f)

    /** Auto adjust ([tone] = true) or Color correction only (white balance + exposure). */
    fun auto(c: Clip, s: Stats, tone: Boolean) {
        c.temp = tempFor(s) * 0.7f
        if (tone) {
            val f = (0.94f / (s.hi - s.lo).coerceAtLeast(0.2f)).coerceIn(0.85f, 1.5f)
            c.contrast = contrastParam(f)
            val m2 = 0.5f + (s.luma - 0.5f) * f
            c.brightness = (0.45f - m2).coerceIn(-0.3f, 0.3f)
            c.saturation = if (s.sat < 0.35f) 1.15f else 1.05f
        } else {
            c.brightness = (0.45f - s.luma).coerceIn(-0.3f, 0.3f)
        }
    }

    /** Makes [c] look like the reference clip. */
    fun match(c: Clip, s: Stats, ref: Stats) {
        c.temp = (tempFor(s) - tempFor(ref)).coerceIn(-0.6f, 0.6f)
        val f = (ref.std / max(0.03f, s.std)).coerceIn(0.7f, 1.5f)
        c.contrast = contrastParam(f)
        val m2 = 0.5f + (s.luma - 0.5f) * f
        c.brightness = (ref.luma - m2).coerceIn(-0.4f, 0.4f)
        c.saturation = (ref.sat / max(0.05f, s.sat)).coerceIn(0.5f, 1.8f)
    }
}

package com.gatsmod.cut

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.util.concurrent.TimeUnit

/** Replaces the background behind a person using ML Kit's on-device selfie segmentation (the model ships inside the app). */
class RemoveBg(private val blurBackground: Boolean, private val color: Int, private val photo: Bitmap? = null) {
    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).enableRawSizeMask().build()
    )
    private var prev: FloatArray? = null

    /** Returns [src] with the background replaced, or null if the model failed on this frame. */
    fun process(src: Bitmap): Bitmap? {
        return try {
            val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(src, 0)), 10, TimeUnit.SECONDS)
            val mw = mask.width; val mh = mask.height
            val buf = mask.buffer; buf.rewind()
            val m1 = FloatArray(mw * mh) { buf.float }
            val (m, fw, fh) = secondPass(src, m1, mw, mh)
            MaskMath.smooth(m, prev)
            prev = m.copyOf()
            composite(src, m, fw, fh)
        } catch (e: Throwable) { null }
    }

    /** Looks at the person again from close up (crop around them) so the mask has more detail, then returns the merged mask. */
    private fun secondPass(src: Bitmap, m1: FloatArray, mw: Int, mh: Int): Triple<FloatArray, Int, Int> {
        val w = src.width; val h = src.height
        val fw = maxOf(2, w / 2); val fh = maxOf(2, h / 2)
        var x0 = mw; var y0 = mh; var x1 = -1; var y1 = -1
        for (y in 0 until mh) for (x in 0 until mw) if (m1[y * mw + x] > 0.5f) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        var m2: FloatArray? = null; var m2w = 0; var m2h = 0
        var cx0 = 0; var cy0 = 0; var cw = w; var ch = h
        if (x1 >= x0 && y1 >= y0) {
            val mx = ((x1 - x0 + 1) * 0.12f).toInt() + 2; val my = ((y1 - y0 + 1) * 0.12f).toInt() + 2
            cx0 = (maxOf(0, x0 - mx).toFloat() * w / mw).toInt()
            cy0 = (maxOf(0, y0 - my).toFloat() * h / mh).toInt()
            cw = (minOf(mw, x1 + mx + 1).toFloat() * w / mw).toInt() - cx0
            ch = (minOf(mh, y1 + my + 1).toFloat() * h / mh).toInt() - cy0
            cw = cw.coerceIn(1, w - cx0); ch = ch.coerceIn(1, h - cy0)
            if (cw >= 64 && ch >= 64 && cw.toLong() * ch < 0.75 * w * h) {
                try {
                    val crop = Bitmap.createBitmap(src, cx0, cy0, cw, ch)
                    val mk = Tasks.await(segmenter.process(InputImage.fromBitmap(crop, 0)), 10, TimeUnit.SECONDS)
                    val b = mk.buffer; b.rewind()
                    m2w = mk.width; m2h = mk.height
                    m2 = FloatArray(m2w * m2h) { b.float }
                    crop.recycle()
                } catch (_: Throwable) { m2 = null }
            }
        }
        val out = FloatArray(fw * fh)
        for (y in 0 until fh) for (x in 0 until fw) {
            val px = (x + 0.5f) * w / fw; val py = (y + 0.5f) * h / fh
            val inCrop = m2 != null && px >= cx0 && px < cx0 + cw && py >= cy0 && py < cy0 + ch
            out[y * fw + x] = if (inCrop) MaskMath.sample(m2!!, m2w, m2h, (px - cx0).toInt(), (py - cy0).toInt(), cw, ch)
            else MaskMath.sample(m1, mw, mh, (px).toInt().coerceIn(0, w - 1), (py).toInt().coerceIn(0, h - 1), w, h)
        }
        if (x1 >= x0 && y1 >= y0) headPass(src, m1, mw, mh, x0, y0, x1, y1, out, fw, fh)
        return Triple(out, fw, fh)
    }

    /** Third look: only the head, from very close, so hair gets far more detail than in the whole-person pass. */
    private fun headPass(src: Bitmap, m1: FloatArray, mw: Int, mh: Int, x0: Int, y0: Int, x1: Int, y1: Int, out: FloatArray, fw: Int, fh: Int) {
        try {
            val w = src.width; val h = src.height
            val hy1 = (y0 + (y1 - y0 + 1) * 0.38f).toInt().coerceIn(y0 + 1, y1)
            var hx0 = mw; var hx1 = -1
            for (y in y0..hy1) for (x in 0 until mw) if (m1[y * mw + x] > 0.5f) { if (x < hx0) hx0 = x; if (x > hx1) hx1 = x }
            if (hx1 < hx0) return
            val mx = ((hx1 - hx0 + 1) * 0.3f).toInt() + 2; val my = ((hy1 - y0 + 1) * 0.3f).toInt() + 2
            val cx0 = (maxOf(0, hx0 - mx).toFloat() * w / mw).toInt()
            val cy0 = (maxOf(0, y0 - my).toFloat() * h / mh).toInt()
            val cw = ((minOf(mw, hx1 + mx + 1).toFloat() * w / mw).toInt() - cx0).coerceIn(1, w - cx0)
            val ch = ((minOf(mh, hy1 + my + 1).toFloat() * h / mh).toInt() - cy0).coerceIn(1, h - cy0)
            if (cw < 64 || ch < 64 || cw.toLong() * ch > 0.5 * w * h) return
            val crop = Bitmap.createBitmap(src, cx0, cy0, cw, ch)
            val mk = Tasks.await(segmenter.process(InputImage.fromBitmap(crop, 0)), 10, TimeUnit.SECONDS)
            crop.recycle()
            val b = mk.buffer; b.rewind()
            val m3 = FloatArray(mk.width * mk.height) { b.float }
            for (y in 0 until fh) for (x in 0 until fw) {
                val px = (x + 0.5f) * w / fw; val py = (y + 0.5f) * h / fh
                if (px < cx0 || px >= cx0 + cw || py < cy0 || py >= cy0 + ch) continue
                val u = (px - cx0) / cw; val v = (py - cy0) / ch
                // trust the close-up fully at the top, fade it out towards the cut edges of the crop
                val wy = if (v < 0.7f) 1f else ((1f - v) / 0.3f).coerceIn(0f, 1f)
                val wx = minOf(u, 1f - u).let { (it / 0.12f).coerceIn(0f, 1f) }
                val k = wy * wx
                if (k <= 0f) continue
                val v3 = MaskMath.sample(m3, mk.width, mk.height, (px - cx0).toInt(), (py - cy0).toInt(), cw, ch)
                out[y * fw + x] = out[y * fw + x] * (1f - k) + v3 * k
            }
        } catch (_: Throwable) {}
    }

    private fun composite(src: Bitmap, m: FloatArray, mw: Int, mh: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var bg: IntArray? = null
        if (blurBackground) {
            // cheap strong blur: shrink a lot, then stretch back
            val small = Bitmap.createScaledBitmap(src, maxOf(2, w / 14), maxOf(2, h / 14), true)
            val big = Bitmap.createScaledBitmap(small, w, h, true)
            bg = IntArray(w * h); big.getPixels(bg, 0, w, 0, 0, w, h)
            small.recycle(); big.recycle()
        }
        if (photo != null) {
            // centre-crop the photo to the frame
            val k = maxOf(w.toFloat() / photo.width, h.toFloat() / photo.height)
            val sw = (w / k).toInt().coerceIn(1, photo.width); val sh = (h / k).toInt().coerceIn(1, photo.height)
            val crop = Bitmap.createBitmap(photo, (photo.width - sw) / 2, (photo.height - sh) / 2, sw, sh)
            val scaled = Bitmap.createScaledBitmap(crop, w, h, true)
            bg = IntArray(w * h); scaled.getPixels(bg, 0, w, 0, 0, w, h)
        }
        // snap the soft mask to the real edges of the picture (guided filter on brightness)
        val lum = FloatArray(w * h) { val c = px[it]; (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f }
        val alpha = MaskMath.refine(m, mw, mh, lum, w, h, maxOf(4, maxOf(w, h) / 110), 0.0008f)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val a = MaskMath.edge(alpha[i], 0.3f, 0.7f)
            if (a < 0.995f) px[i] = MaskMath.mix(px[i], bg?.get(i) ?: color, a)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    fun close() { try { segmenter.close() } catch (_: Throwable) {} }
}

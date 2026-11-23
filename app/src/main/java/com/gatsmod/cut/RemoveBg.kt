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
            val m = FloatArray(mw * mh) { buf.float }
            MaskMath.smooth(m, prev)
            prev = m.copyOf()
            composite(src, m, mw, mh)
        } catch (e: Throwable) { null }
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
        for (y in 0 until h) for (x in 0 until w) {
            val a = MaskMath.edge(MaskMath.sample(m, mw, mh, x, y, w, h))
            val i = y * w + x
            if (a < 0.995f) px[i] = MaskMath.mix(px[i], bg?.get(i) ?: color, a)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    fun close() { try { segmenter.close() } catch (_: Throwable) {} }
}

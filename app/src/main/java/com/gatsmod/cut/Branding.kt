package com.gatsmod.cut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Saved choices for the ending card and the export watermark. */
object Branding {
    private fun p(c: Context) = c.getSharedPreferences("branding", Context.MODE_PRIVATE)
    fun endingOn(c: Context) = p(c).getBoolean("endingOn", true)
    fun setEndingOn(c: Context, v: Boolean) { p(c).edit().putBoolean("endingOn", v).apply() }
    fun endingText(c: Context) = p(c).getString("endingText", "Made with Get Cut") ?: "Made with Get Cut"
    fun setEndingText(c: Context, t: String) { p(c).edit().putString("endingText", t).apply() }

    /** 0 none, 1 text, 2 image */
    fun wmMode(c: Context) = p(c).getInt("wmMode", 0)
    fun wmText(c: Context) = p(c).getString("wmText", "Get Cut") ?: "Get Cut"
    fun wmImage(c: Context) = p(c).getString("wmImage", "") ?: ""
    /** 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right */
    fun wmPos(c: Context) = p(c).getInt("wmPos", 3)
    fun wmAlpha(c: Context) = p(c).getInt("wmAlpha", 80)
    fun setWm(c: Context, mode: Int = wmMode(c), text: String = wmText(c), image: String = wmImage(c), pos: Int = wmPos(c), alpha: Int = wmAlpha(c)) {
        p(c).edit().putInt("wmMode", mode).putString("wmText", text).putString("wmImage", image).putInt("wmPos", pos).putInt("wmAlpha", alpha).apply()
    }

    /** The watermark picture for a video that is [videoW] pixels wide, or null when there is none. */
    fun watermarkBitmap(c: Context, videoW: Int): Bitmap? {
        val target = max(64, (videoW * 0.2f).toInt())
        return when (wmMode(c)) {
            1 -> {
                val t = wmText(c).ifBlank { return null }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                paint.textSize = 100f
                val scale = target / max(1f, paint.measureText(t))
                paint.textSize = (100f * scale).coerceIn(14f, videoW * 0.06f)
                val w = paint.measureText(t).toInt() + 8
                val h = (paint.fontMetrics.bottom - paint.fontMetrics.top).toInt() + 8
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val cv = Canvas(bmp)
                paint.color = Color.BLACK; paint.alpha = 90
                paint.setShadowLayer(paint.textSize * 0.08f, 0f, 0f, 0x99000000.toInt())
                cv.drawText(t, 4f, 4f - paint.fontMetrics.top, paint)
                paint.clearShadowLayer(); paint.color = Color.WHITE; paint.alpha = 255
                cv.drawText(t, 4f, 4f - paint.fontMetrics.top, paint)
                bmp
            }
            2 -> {
                val f = wmImage(c)
                if (f.isEmpty() || !File(f).exists()) return null
                val src = android.graphics.BitmapFactory.decodeFile(f) ?: return null
                val k = target.toFloat() / src.width
                Bitmap.createScaledBitmap(src, target, max(1, (src.height * k).toInt()), true)
            }
            else -> null
        }
    }
}

/** A corner watermark that stays on the whole exported video. */
@UnstableApi
class WatermarkOverlay(private val bmp: Bitmap, pos: Int, alphaPercent: Int) : BitmapOverlay() {
    private val settings: OverlaySettings
    init {
        val m = 0.06f
        val ax = if (pos % 2 == 0) -1f + m else 1f - m
        val ay = if (pos < 2) 1f - m else -1f + m
        settings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(ax, ay)
            .setOverlayFrameAnchor(if (pos % 2 == 0) -1f else 1f, if (pos < 2) 1f else -1f)
            .setAlphaScale(alphaPercent / 100f)
            .build()
    }
    override fun getBitmap(presentationTimeUs: Long): Bitmap = bmp
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
}

/** Makes the short "Ending" clip: black card, the app logo and a line of text, with a soft fade-in. */
object EndingCard {
    const val LABEL = "Ending"

    fun file(ctx: Context, text: String, aspect: Float) = File(File(ctx.filesDir, "endings"), "e_${text.hashCode()}_${(aspect * 100).toInt()}.mp4")

    /** The finished card if it was made before (then adding the ending is instant), else null. */
    fun cached(ctx: Context, text: String, aspect: Float): FrameVideo.Result? {
        val f = file(ctx, text, aspect)
        if (!f.exists() || f.length() < 1000L) return null
        val (w, h) = size(aspect)
        return FrameVideo.Result(f, 2500L, w, h)
    }

    private fun size(aspect: Float): Pair<Int, Int> {
        val short = 720
        return if (aspect >= 1f) Pair((short * aspect).toInt() / 2 * 2, short) else Pair(short, (short / aspect).toInt() / 2 * 2)
    }

    fun make(ctx: Context, text: String, aspect: Float, progress: (Int) -> Boolean): FrameVideo.Result? {
        cached(ctx, text, aspect)?.let { return it }
        val (w, h) = size(aspect)
        val fps = 30
        val total = 75
        val logo = ContextCompat.getDrawable(ctx, R.drawable.ic_launcher_fg)
        val finalFile = file(ctx, text, aspect)
        val out = File(finalFile.path + ".tmp")
        val tile = min(w, h) * 0.3f
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = min(w, h) * 0.058f
        val lines = wrap(text.ifBlank { "Made with Get Cut" }, tp, w * 0.84f)
        val res = FrameVideo.write(out, fps, total, 3_000_000, { i ->
            val t = i / fps.toFloat()
            val a = (t / 0.7f).coerceIn(0f, 1f)
            val ease = 1f - (1f - a) * (1f - a)
            val ta = ((t - 0.5f) / 0.7f).coerceIn(0f, 1f)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            cv.drawColor(Color.BLACK)
            val cx = w / 2f
            val blockH = tile + tp.textSize * 1.4f * lines.size + tile * 0.18f
            val top = (h - blockH) / 2f
            val sc = 0.8f + 0.2f * ease
            cv.save()
            cv.scale(sc, sc, cx, top + tile / 2f)
            val box = RectF(cx - tile / 2f, top, cx + tile / 2f, top + tile)
            val bp = Paint(Paint.ANTI_ALIAS_FLAG); bp.color = Color.WHITE; bp.alpha = (255 * ease).toInt()
            cv.drawRoundRect(box, tile * 0.22f, tile * 0.22f, bp)
            if (logo != null) {
                val inner = tile * 1.5f
                logo.setBounds((cx - inner / 2f).toInt(), (top + tile / 2f - inner / 2f).toInt(), (cx + inner / 2f).toInt(), (top + tile / 2f + inner / 2f).toInt())
                logo.alpha = (255 * ease).toInt()
                cv.save(); cv.clipRect(box); logo.draw(cv); cv.restore()
            }
            cv.restore()
            tp.color = Color.WHITE; tp.alpha = (255 * ta).toInt()
            var y = top + tile + tile * 0.18f + tp.textSize
            for (ln in lines) { cv.drawText(ln, cx, y, tp); y += tp.textSize * 1.4f }
            bmp
        }, progress) ?: return null
        finalFile.delete()
        return if (out.renameTo(finalFile)) FrameVideo.Result(finalFile, res.durationMs, res.width, res.height) else res
    }

    private fun wrap(t: String, p: Paint, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var cur = ""
        for (word in t.split(' ', '\n').filter { it.isNotEmpty() }) {
            val next = if (cur.isEmpty()) word else "$cur $word"
            if (p.measureText(next) <= maxW || cur.isEmpty()) cur = next else { out.add(cur); cur = word }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out.take(4)
    }
}

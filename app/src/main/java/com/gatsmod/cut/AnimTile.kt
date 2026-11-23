package com.gatsmod.cut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.max

/** A tile of the Animations panel: the clip's own picture playing the animation in a loop, like CapCut's thumbnails. */
class AnimTile(ctx: Context, private val pic: Bitmap?, private val mode: Int, private val id: Int, private val radius: Float, private val fit: Boolean = false) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2C31.toInt() }
    private val path = Path()
    private val rect = RectF()
    private val cm = ColorMatrix()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.save()
        path.reset(); path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(path)
        canvas.drawRect(rect, fill)
        val now = SystemClock.uptimeMillis()
        var pose = Pose()
        var mask = floatArrayOf(0f, 1f)
        when (mode) {
            0 -> { val t = now % 1900L; pose = Anim.pose(id, 800, 0, 0, 0, t, 1900); mask = Anim.maskState(id, 800, 0, 0, t, 1900) }
            1 -> { val t = now % 1900L; pose = Anim.pose(0, 0, id, 800, 0, t, 1900); mask = Anim.maskState(0, 0, id, 800, t, 1900) }
            3 -> pose = ProText.pose(id, now % 100000L)
            else -> pose = Anim.pose(0, 0, 0, 0, id, now % 3000L, 3000)
        }
        if (pic != null) {
            canvas.save()
            canvas.translate(w / 2f + pose.x / 2f * w, h / 2f - pose.y / 2f * h)
            canvas.rotate(pose.rot)
            canvas.scale(pose.sx, pose.sy)
            val g = pose.gain.coerceIn(0f, 4f)
            cm.set(floatArrayOf(g, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            paint.colorFilter = ColorMatrixColorFilter(cm)
            if (mask[0] > 0f) {
                val s = mask[1] * 0.5f * h
                val mp = Path()
                when (mask[0].toInt()) {
                    1 -> mp.addCircle(0f, 0f, s, Path.Direction.CW)
                    2 -> mp.addRect(-s * 1.3f, -s, s * 1.3f, s, Path.Direction.CW)
                    else -> { val d = s * 1.2f; mp.moveTo(0f, -d); mp.lineTo(d, 0f); mp.lineTo(0f, d); mp.lineTo(-d, 0f); mp.close() }
                }
                // the picture is drawn centred and scaled, so the clip is applied in the same space
                canvas.clipPath(mp)
            }
            if (fit) {
                val k = minOf(w * 0.84f / pic.width, h * 0.84f / pic.height)
                val pw = pic.width * k; val ph = pic.height * k
                canvas.drawBitmap(pic, null, RectF(-pw / 2f, -ph / 2f, pw / 2f, ph / 2f), paint)
            } else canvas.drawBitmap(pic, null, RectF(-w / 2f, -h / 2f, w / 2f, h / 2f), paint)
            canvas.restore()
        }
        canvas.restore()
        postInvalidateOnAnimation()
    }
}

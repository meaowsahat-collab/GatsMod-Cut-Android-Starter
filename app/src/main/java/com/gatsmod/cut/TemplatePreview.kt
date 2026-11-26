package com.gatsmod.cut

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import android.view.ViewOutlineProvider
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Sample pictures drawn in code, so a template can be shown working before the person picks their own photos. */
object Samples {
    const val W = 360
    const val H = 640
    private var cache: List<Bitmap>? = null
    @Volatile private var real: List<Bitmap>? = null
    private val QUERIES = listOf("sunset beach", "mountain lake landscape", "city skyline night", "flower field", "autumn forest path", "travel adventure", "woman portrait nature", "coffee cafe", "sports car", "winter snow", "wedding couple", "friends party")

    /** Real free photos (Openverse, Creative Commons) when they could be downloaded, otherwise the pictures drawn in code. */
    fun get(): List<Bitmap> = real ?: fallback()

    @Synchronized
    private fun fallback(): List<Bitmap> {
        cache?.let { return it }
        val l = listOf(sunset(), mountains(), city(), meadow(), autumn())
        cache = l
        return l
    }

    /** Loads saved sample photos at once and refreshes them from the internet about once a week. */
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            try {
                loadCached(app)
                val stamp = java.io.File(java.io.File(app.filesDir, "samples"), "stamp")
                if (real == null || System.currentTimeMillis() - stamp.lastModified() > 7L * 24 * 3600 * 1000) download(app)
            } catch (_: Throwable) {}
        }.also { it.priority = Thread.MIN_PRIORITY }.start()
    }

    private fun loadCached(ctx: Context) {
        val dir = java.io.File(ctx.filesDir, "samples")
        val files = dir.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: return
        val l = ArrayList<Bitmap>()
        for (f in files) {
            try {
                val o = BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.path, o)
                if (o.outWidth <= 0) continue
                var s = 1; while (o.outWidth / s > 800) s *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.let { l.add(it) }
            } catch (_: Throwable) {}
        }
        if (l.size >= 3) real = l
    }

    private fun download(ctx: Context) {
        val dir = java.io.File(ctx.filesDir, "samples"); dir.mkdirs()
        val credits = org.json.JSONArray()
        var k = 0
        val old = dir.listFiles { f -> f.name.startsWith("n") && f.name.endsWith(".jpg") }
        old?.forEach { it.delete() }
        for (q in QUERIES) {
            if (k >= 10) break
            try {
                val url = "https://api.openverse.org/v1/images/?q=" + java.net.URLEncoder.encode(q, "UTF-8") + "&license_type=commercial&aspect_ratio=tall&size=medium&page_size=8"
                val arr = org.json.JSONObject(SoundsData.cachedText(ctx, url, 7L * 24 * 3600 * 1000)).optJSONArray("results") ?: continue
                var got = 0
                for (i in 0 until arr.length()) {
                    if (got >= 1) break
                    val o = arr.getJSONObject(i)
                    val src = o.optString("thumbnail").ifBlank { o.optString("url") }
                    if (src.isBlank()) continue
                    try {
                        val f = java.io.File(dir, "n$k.jpg")
                        SoundsData.open(src).inputStream.use { ins -> f.outputStream().use { out -> val buf = ByteArray(16384); var tot = 0; while (true) { val r = ins.read(buf); if (r < 0) break; tot += r; if (tot > 4_000_000) throw java.io.IOException("big"); out.write(buf, 0, r) } } }
                        val bo = BitmapFactory.Options(); bo.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.path, bo)
                        if (bo.outWidth < 200) { f.delete(); continue }
                        credits.put(org.json.JSONObject().put("title", o.optString("title")).put("creator", o.optString("creator")).put("license", o.optString("license").uppercase() + " " + o.optString("license_version")).put("link", o.optString("foreign_landing_url")))
                        k++; got++
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        if (k >= 3) {
            dir.listFiles { f -> f.name.startsWith("s") && f.name.endsWith(".jpg") }?.forEach { it.delete() }
            for (i in 0 until k) java.io.File(dir, "n$i.jpg").renameTo(java.io.File(dir, "s$i.jpg"))
            java.io.File(dir, "credits.json").writeText(credits.toString())
            java.io.File(dir, "stamp").writeText(System.currentTimeMillis().toString())
            loadCached(ctx)
        } else {
            dir.listFiles { f -> f.name.startsWith("n") && f.name.endsWith(".jpg") }?.forEach { it.delete() }
        }
    }

    /** Text for the credits dialog. */
    fun credits(ctx: Context): String {
        val f = java.io.File(java.io.File(ctx.filesDir, "samples"), "credits.json")
        if (!f.exists() || real == null) return "The sample pictures in the previews are drawn inside the app, so there is nothing to credit."
        return try {
            val a = org.json.JSONArray(f.readText())
            (0 until a.length()).joinToString("\n\n") { val o = a.getJSONObject(it); "\"" + o.optString("title").ifBlank { "Photo" } + "\" by " + o.optString("creator").ifBlank { "unknown" } + " (" + o.optString("license") + ")\n" + o.optString("link") }
        } catch (_: Exception) { "No credits found." }
    }

    private fun bmp(draw: (Canvas, Paint) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        draw(Canvas(b), p)
        return b
    }

    private fun grad(p: Paint, y0: Float, y1: Float, vararg c: Int) {
        p.shader = LinearGradient(0f, y0, 0f, y1, c, null, Shader.TileMode.CLAMP)
    }

    private fun glow(cv: Canvas, p: Paint, x: Float, y: Float, r: Float, col: Int) {
        p.shader = RadialGradient(x, y, r, intArrayOf(col, Color.argb(0, Color.red(col), Color.green(col), Color.blue(col))), null, Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, r, p); p.shader = null
    }

    private fun person(cv: Canvas, p: Paint, x: Float, y: Float, s: Float, body: Int) {
        p.shader = null; p.color = body
        cv.drawCircle(x, y - 78 * s, 20 * s, p)
        cv.drawCircle(x + 14 * s, y - 92 * s, 9 * s, p)
        val path = Path()
        path.moveTo(x - 48 * s, y + 70 * s); path.quadTo(x - 52 * s, y - 40 * s, x - 18 * s, y - 56 * s)
        path.lineTo(x + 18 * s, y - 56 * s); path.quadTo(x + 52 * s, y - 40 * s, x + 48 * s, y + 70 * s); path.close()
        cv.drawPath(path, p)
    }

    private fun sunset() = bmp { cv, p ->
        val hz = H * 0.56f
        grad(p, 0f, hz, 0xFF2B1B5A.toInt(), 0xFFB23A7A.toInt(), 0xFFFF8A4C.toInt(), 0xFFFFC56B.toInt())
        cv.drawRect(0f, 0f, W.toFloat(), hz, p)
        val r = Random(3)
        p.shader = null; p.color = 0x33FFFFFF.toInt()
        for (i in 0 until 6) cv.drawOval(RectF(r.nextInt(W) - 60f, 40f + r.nextInt(180), r.nextInt(W) + 80f, 70f + r.nextInt(190)), p)
        glow(cv, p, W * 0.55f, hz - 10, 150f, 0xAAFFD27A.toInt())
        p.color = 0xFFFFF1B0.toInt(); cv.drawCircle(W * 0.55f, hz - 10, 34f, p)
        grad(p, hz, H.toFloat(), 0xFFF29A5C.toInt(), 0xFF8E3F6B.toInt(), 0xFF1B2250.toInt())
        cv.drawRect(0f, hz, W.toFloat(), H.toFloat(), p); p.shader = null
        p.color = 0x55FFE3A0.toInt()
        for (i in 0 until 14) { val w = 90f - i * 5.5f; cv.drawRect(W * 0.55f - w / 2, hz + 8 + i * 17f, W * 0.55f + w / 2, hz + 13 + i * 17f, p) }
        p.color = 0xFF1A1424.toInt()
        val boat = Path(); boat.moveTo(40f, hz + 6); boat.lineTo(120f, hz + 6); boat.lineTo(104f, hz + 26); boat.lineTo(56f, hz + 26); boat.close(); cv.drawPath(boat, p)
        cv.drawRect(78f, hz - 34, 81f, hz + 6, p)
        person(cv, p, W * 0.34f, H * 0.82f, 1.25f, 0xFF140F1E.toInt())
    }

    private fun mountains() = bmp { cv, p ->
        grad(p, 0f, H * 0.6f, 0xFF4FA3E8.toInt(), 0xFFBFE4FF.toInt(), 0xFFFFF3DC.toInt())
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p); p.shader = null
        glow(cv, p, W * 0.78f, H * 0.2f, 120f, 0xAAFFFFFF.toInt())
        val r = Random(9)
        val cols = intArrayOf(0xFF9DB6D6.toInt(), 0xFF5B7BA6.toInt(), 0xFF2F4F6B.toInt())
        for (layer in 0..2) {
            val base = H * (0.46f + layer * 0.11f)
            val path = Path(); path.moveTo(0f, H.toFloat()); path.lineTo(0f, base)
            var x = 0f
            while (x < W) { x += 40f + r.nextInt(50); path.lineTo(x, base - 40f - r.nextInt(120 - layer * 25)) ; x += 30f + r.nextInt(40); path.lineTo(x, base + r.nextInt(30)) }
            path.lineTo(W.toFloat(), H.toFloat()); path.close()
            p.color = cols[layer]; cv.drawPath(path, p)
        }
        grad(p, H * 0.7f, H.toFloat(), 0xFF2E7D6B.toInt(), 0xFF123B33.toInt())
        cv.drawRect(0f, H * 0.72f, W.toFloat(), H.toFloat(), p); p.shader = null
        p.color = 0xFF0E3A2C.toInt()
        for (i in 0 until 16) {
            val x = r.nextInt(W).toFloat(); val y = H * 0.74f + r.nextInt((H * 0.24f).toInt()); val s = 0.7f + (y - H * 0.74f) / 200f
            val t = Path(); t.moveTo(x, y - 70 * s); t.lineTo(x - 22 * s, y); t.lineTo(x + 22 * s, y); t.close(); cv.drawPath(t, p)
        }
    }

    private fun city() = bmp { cv, p ->
        grad(p, 0f, H.toFloat(), 0xFF080B25.toInt(), 0xFF2D2A6E.toInt(), 0xFFB04A8F.toInt())
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p); p.shader = null
        val r = Random(5)
        p.color = Color.WHITE
        for (i in 0 until 60) cv.drawCircle(r.nextInt(W).toFloat(), r.nextInt((H * 0.5f).toInt()).toFloat(), 0.8f + r.nextFloat() * 1.2f, p)
        glow(cv, p, W * 0.72f, H * 0.17f, 90f, 0x66FFF2C8.toInt())
        p.color = 0xFFFFF6DA.toInt(); cv.drawCircle(W * 0.72f, H * 0.17f, 28f, p)
        var x = -10f
        while (x < W) {
            val w = 38f + r.nextInt(46); val h = 150f + r.nextInt(300)
            p.color = if (r.nextBoolean()) 0xFF0F1230.toInt() else 0xFF171A3F.toInt()
            cv.drawRect(x, H - h, x + w, H.toFloat(), p)
            var yy = H - h + 12
            while (yy < H - 14) { var xx = x + 6; while (xx < x + w - 8) { if (r.nextInt(3) != 0) { p.color = if (r.nextInt(4) == 0) 0xFFFF7AC8.toInt() else 0xFFFFD86B.toInt(); cv.drawRect(xx, yy, xx + 5, yy + 7, p) }; xx += 11 }; yy += 16 }
            x += w + 2
        }
        person(cv, p, W * 0.3f, H * 0.9f, 1.1f, 0xFF05060F.toInt())
    }

    private fun meadow() = bmp { cv, p ->
        grad(p, 0f, H * 0.5f, 0xFF6EC6FF.toInt(), 0xFFEFFBFF.toInt())
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p); p.shader = null
        glow(cv, p, W * 0.2f, H * 0.12f, 140f, 0xCCFFF8C8.toInt())
        val r = Random(11)
        p.color = 0x88FFFFFF.toInt()
        for (i in 0 until 5) cv.drawOval(RectF(r.nextInt(W) - 40f, 60f + r.nextInt(160), r.nextInt(W) + 90f, 100f + r.nextInt(170)), p)
        grad(p, H * 0.42f, H.toFloat(), 0xFF9AD66B.toInt(), 0xFF2F8F3F.toInt())
        val hill = Path(); hill.moveTo(0f, H * 0.5f); hill.quadTo(W * 0.5f, H * 0.38f, W.toFloat(), H * 0.52f); hill.lineTo(W.toFloat(), H.toFloat()); hill.lineTo(0f, H.toFloat()); hill.close()
        cv.drawPath(hill, p); p.shader = null
        val cols = intArrayOf(0xFFFF6FA5.toInt(), 0xFFFFE04A.toInt(), Color.WHITE, 0xFFB46BFF.toInt())
        for (i in 0 until 260) {
            val y = H * 0.52f + r.nextFloat() * (H * 0.48f); val s = 2f + (y - H * 0.5f) / 45f
            p.color = cols[r.nextInt(cols.size)]; cv.drawCircle(r.nextInt(W).toFloat(), y, s, p)
        }
    }

    private fun autumn() = bmp { cv, p ->
        grad(p, 0f, H.toFloat(), 0xFFFFD58A.toInt(), 0xFFFF9B54.toInt(), 0xFF8A3E1C.toInt())
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p); p.shader = null
        glow(cv, p, W * 0.5f, H * 0.3f, 170f, 0x99FFF0B0.toInt())
        val r = Random(21)
        for (k in 0 until 7) {
            val x = 20f + k * 52f + r.nextInt(20); val top = H * 0.2f + r.nextInt(120); val base = H * 0.78f
            p.color = 0xFF4A2410.toInt(); cv.drawRect(x - 6, top + 80, x + 6, base, p)
            val cc = intArrayOf(0xFFE8541E.toInt(), 0xFFF2A020.toInt(), 0xFFC73A12.toInt())
            for (j in 0 until 6) { p.color = cc[r.nextInt(3)]; cv.drawCircle(x + r.nextInt(70) - 35f, top + 40f + r.nextInt(90), 28f + r.nextInt(18), p) }
        }
        p.color = 0xFF6B3015.toInt(); cv.drawRect(0f, H * 0.78f, W.toFloat(), H.toFloat(), p)
        for (i in 0 until 60) { p.color = if (r.nextBoolean()) 0xFFF2A020.toInt() else 0xFFE8541E.toInt(); cv.drawOval(RectF(r.nextInt(W).toFloat(), H * 0.78f + r.nextInt((H * 0.22f).toInt()), 0f, 0f).also { it.right = it.left + 12; it.bottom = it.top + 6 }, p) }
    }
}

/** Plays a template on the sample pictures in a loop: its look, zoom, transitions and title, as the finished video will use them. */
class TemplatePreview(ctx: Context, private val t: Templates.T) : View(ctx) {
    private val scenes: List<Bitmap> get() = Samples.get()
    private val n = min(t.maxClips, 5)
    private val slotD = (t.slotMs * 0.65f).coerceIn(1000f, 1700f)
    private val total = n * slotD
    private val off = abs(t.id.hashCode()) % 7
    private val t0 = SystemClock.uptimeMillis() - (abs(t.id.hashCode()) % 1500)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clip = Clip(id = 0, kind = LaneKind.VIDEO).also { t.look(it) }
    private val cf: ColorMatrixColorFilter = buildFilter()
    private val rnd = Random(1)
    private val tmp = Rect()
    private var vig: RadialGradient? = null

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 16 * resources.displayMetrics.density) }
        }
        clipToOutline = true
    }

    private fun buildFilter(): ColorMatrixColorFilter {
        val cm = ColorMatrix()
        fun add(m: ColorMatrix) = cm.postConcat(m)
        fun mat(vararg v: Float) = ColorMatrix(v)
        val fd = Fx.FILTERS.getOrNull(clip.filter)
        if (fd != null && clip.filter != 0) {
            val sm = ColorMatrix(); sm.setSaturation(if (fd.mode == 1) 0f else (1f + fd.sat / 100f).coerceAtLeast(0f)); add(sm)
            if (fd.mode == 2) add(mat(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            val k = 1f + fd.con; val o = 127.5f * (1f - k) + fd.br * 255f
            add(mat(k * fd.r, 0f, 0f, 0f, o, 0f, k * fd.g, 0f, 0f, o, 0f, 0f, k * fd.b, 0f, o, 0f, 0f, 0f, 1f, 0f))
        }
        val b = clip.brightness * 255f
        add(mat(1f, 0f, 0f, 0f, b, 0f, 1f, 0f, 0f, b, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f))
        val k = clip.contrast; val o = 127.5f * (1f - k)
        add(mat(k, 0f, 0f, 0f, o, 0f, k, 0f, 0f, o, 0f, 0f, k, 0f, o, 0f, 0f, 0f, 1f, 0f))
        add(mat(1f + 0.25f * clip.temp, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f - 0.25f * clip.temp, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        val sm = ColorMatrix(); sm.setSaturation(clip.saturation.coerceAtLeast(0f)); add(sm)
        return ColorMatrixColorFilter(cm)
    }

    private fun draw(cv: Canvas, idx: Int, s: Float, tx: Float, ty: Float, rot: Float, alpha: Float) {
        if (alpha <= 0.01f) return
        val sc0 = scenes; val b = sc0[(idx + off) % sc0.size]
        val sc = max(width.toFloat() / b.width, height.toFloat() / b.height)
        paint.colorFilter = cf
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        cv.save()
        cv.translate(width / 2f + tx, height / 2f + ty)
        cv.rotate(rot)
        cv.scale(s * sc, s * sc)
        cv.translate(-b.width / 2f, -b.height / 2f)
        cv.drawBitmap(b, 0f, 0f, paint)
        cv.restore()
    }

    private fun ease(q: Float) = 1f - (1f - q) * (1f - q)
    private fun back(q: Float): Float { val c = 1.70158f; val x = q - 1f; return 1f + (c + 1f) * x * x * x + c * x * x }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        if (!getGlobalVisibleRect(tmp)) { postInvalidateDelayed(300); return }
        val now = ((SystemClock.uptimeMillis() - t0) % total.toLong()).toFloat()
        val i = (now / slotD).toInt().coerceIn(0, n - 1)
        val lt = now - i * slotD
        val prev = (i - 1 + n) % n
        val kb = if (t.kenBurns) 1f else 0f
        val sCur = 1f + 0.14f * kb * (lt / slotD)
        val sPrev = 1f + 0.14f * kb
        val trD = if (t.trans != null) (if (t.transName == "Dissolve") slotD * 0.55f else min(450f, slotD * 0.4f)) else if (t.dipMs > 0) 700f else 0f
        cv.drawColor(Color.BLACK)
        val q = if (trD > 0f && lt < trD && i > 0 || (trD > 0f && lt < trD && i == 0 && now == lt && false)) lt / trD else 1f
        val inTr = q < 1f
        if (!inTr) draw(cv, i, sCur, 0f, 0f, 0f, 1f) else when (t.transName) {
            "Zoom" -> { draw(cv, prev, sPrev * (1f + 0.4f * q), 0f, 0f, 0f, 1f - q); draw(cv, i, sCur * (1.4f - 0.4f * q), 0f, 0f, 0f, q) }
            "Slide left" -> { draw(cv, prev, sPrev, -w * ease(q), 0f, 0f, 1f); draw(cv, i, sCur, w * (1f - ease(q)), 0f, 0f, 1f) }
            "Slide up" -> { draw(cv, prev, sPrev, 0f, -h * ease(q), 0f, 1f); draw(cv, i, sCur, 0f, h * (1f - ease(q)), 0f, 1f) }
            "Pop" -> { draw(cv, prev, sPrev, 0f, 0f, 0f, 1f); draw(cv, i, sCur * (0.4f + 0.6f * back(q)), 0f, 0f, 0f, min(1f, q * 2.5f)) }
            "Dip to black" -> if (q < 0.5f) draw(cv, prev, sPrev, 0f, 0f, 0f, 1f - q * 2f) else draw(cv, i, sCur, 0f, 0f, 0f, q * 2f - 1f)
            "Flash" -> { if (q < 0.5f) draw(cv, prev, sPrev, 0f, 0f, 0f, 1f) else draw(cv, i, sCur, 0f, 0f, 0f, 1f)
                cv.drawColor(Color.argb((255 * (1f - abs(2f * q - 1f))).toInt().coerceIn(0, 255), 255, 255, 255)) }
            "Glitch" -> {
                val bands = 9; val bh = h / bands
                val seed = (SystemClock.uptimeMillis() / 90)
                rnd.setSeed(seed)
                for (k in 0 until bands) {
                    val off = (rnd.nextFloat() - 0.5f) * w * 0.35f * (1f - abs(2f * q - 1f))
                    cv.save(); cv.clipRect(0f, k * bh, w, (k + 1) * bh)
                    if (q < 0.5f) draw(cv, prev, sPrev, off, 0f, 0f, 1f) else draw(cv, i, sCur, off, 0f, 0f, 1f)
                    cv.restore()
                }
            }
            "Motion blur" -> {
                val e = ease(q); val mv = 1f - abs(2f * q - 1f)
                for (g in 0..3) {
                    val a = 0.55f - g * 0.12f; val o = w * 0.09f * g * mv
                    draw(cv, prev, sPrev, -w * e - o, 0f, 0f, a)
                    draw(cv, i, sCur, w * (1f - e) + o, 0f, 0f, a)
                }
                cv.drawColor(Color.argb((110 * mv).toInt().coerceIn(0, 255), 255, 255, 255))
            }
            "Dissolve" -> { draw(cv, prev, sPrev, 0f, 0f, 0f, 1f); draw(cv, i, sCur, 0f, 0f, 0f, ease(q)) }
            "Spin" -> { draw(cv, prev, sPrev * (1f - 0.3f * q), 0f, 0f, 90f * q, 1f - q); draw(cv, i, sCur * (0.7f + 0.3f * q), 0f, 0f, -90f * (1f - q), q) }
            else -> { draw(cv, prev, sPrev, 0f, 0f, 0f, 1f); draw(cv, i, sCur, 0f, 0f, 0f, q) }
        }
        // look effects on top
        when (clip.fx) {
            1 -> {
                if (vig == null) vig = RadialGradient(w / 2, h / 2, max(w, h) * 0.75f, intArrayOf(0x00000000, 0x00000000, Color.argb((190 * clip.fxAmount).toInt().coerceIn(0, 255), 0, 0, 0)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                paint.colorFilter = null; paint.alpha = 255; paint.shader = vig; cv.drawRect(0f, 0f, w, h, paint); paint.shader = null
            }
            4 -> {
                paint.colorFilter = null; paint.color = 0x26000000.toInt()
                var y = 0f; while (y < h) { cv.drawRect(0f, y, w, y + 1.5f, paint); y += 5f }
                rnd.setSeed(SystemClock.uptimeMillis() / 120)
                paint.color = 0x33FF3060.toInt(); cv.drawRect(0f, rnd.nextFloat() * h, w, rnd.nextFloat() * 12f + 4f + 0f, paint)
            }
            5 -> {
                paint.colorFilter = null; rnd.setSeed(SystemClock.uptimeMillis() / 60)
                for (k in 0 until 140) { paint.color = if (k % 2 == 0) 0x33FFFFFF.toInt() else 0x33000000; cv.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 1.2f, paint) }
            }
            8 -> {
                paint.colorFilter = null; paint.alpha = 255
                paint.shader = RadialGradient(w * 0.5f, h * 0.35f, max(w, h) * 0.8f, Color.argb((120 * clip.fxAmount).toInt().coerceIn(0, 255), 255, 240, 250), 0x00FFFFFF, Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, w, h, paint); paint.shader = null
            }
        }
        // title
        if (t.title != null) {
            val ta = (now / total)
            val a = when { now < 250f -> now / 250f; now < slotD * 1.6f -> 1f; now < slotD * 1.6f + 300f -> 1f - (now - slotD * 1.6f) / 300f; else -> 0f }
            if (a > 0f) {
                paint.colorFilter = null; paint.shader = null; paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); paint.textSize = w * 0.13f
                paint.setShadowLayer(w * 0.012f, 0f, w * 0.006f, 0xAA000000.toInt())
                paint.color = t.titleColor; paint.alpha = (a * 255).toInt()
                cv.drawText(t.title, w / 2, h * 0.5f, paint)
                paint.clearShadowLayer(); paint.alpha = 255
                if (ta < 0f) return
            }
        }
        postInvalidateDelayed(33)
    }
}

package com.gatsmod.cut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import kotlin.math.max
import kotlin.math.min

/** Ready-made video templates: pick photos / videos and get a finished edit (look, motion, transitions, title, music). */
object Templates {
    const val ASPECT = 9f / 16f

    class T(
        val id: String, val name: String, val cat: String, val desc: String,
        val c1: Int, val c2: Int,
        val slotMs: Long, val maxClips: Int,
        val music: String?, val title: String?, val titleColor: Int = Color.WHITE,
        val transName: String, val trans: Pair<Int, Int>?, val dipMs: Long = 0L,
        val kenBurns: Boolean, val lookName: String, val sparkle: Boolean = false, val look: (Clip) -> Unit
    ) {
        val totalMs: Long get() = slotMs * maxClips
        val musicName: String get() = SoundGen.MUSIC.firstOrNull { it.id == music }?.name ?: "No music"
        fun notes(): List<String> = listOfNotNull(
            "Up to $maxClips photos or videos, ${"%.1f".format(slotMs / 1000f)} s each",
            "Look: $lookName",
            "Transition: $transName",
            "Music: $musicName" + if (music != null) " (made inside the app)" else "",
            if (title != null) "Title: \"$title\"" else "No title text",
            if (kenBurns) "Slow zoom on photos" else "Photos stay still",
            if (sparkle) "Twinkling sparkles on top" else null
        )
    }

    val CATS = listOf("All", "Cinematic", "Vlog", "Photo", "Music", "Fun")

    private fun fi(n: String) = Fx.FILTERS.indexOfFirst { it.name == n }.coerceAtLeast(0)

    val ALL: List<T> = listOf(
        T("cine", "Cinematic Story", "Cinematic", "Moody, slow, film look", 0xFF1F3A4D.toInt(), 0xFF0B1620.toInt(),
            3000, 6, "epic", null, transName = "Zoom", trans = 1001 to 1, kenBurns = true, lookName = "Cinematic") { c ->
            c.contrast = 1.15f; c.saturation = 0.9f; c.temp = -0.12f; c.brightness = -0.02f; c.fx = 1; c.fxAmount = 0.55f },
        T("vlog", "Vlog Bright", "Vlog", "Warm, bright, sliding cuts", 0xFFFFB74D.toInt(), 0xFFE65100.toInt(),
            2500, 8, "pop", null, transName = "Slide left", trans = 1007 to 8, kenBurns = true, lookName = "Warm and sharp") { c ->
            c.brightness = 0.04f; c.contrast = 1.08f; c.saturation = 1.2f; c.temp = 0.15f; c.fx = 2; c.fxAmount = 0.4f },
        T("dump", "Photo Dump", "Photo", "Fast pops, one photo after another", 0xFFFF8A65.toInt(), 0xFF6A1B9A.toInt(),
            900, 10, "funk", null, transName = "Pop", trans = 1002 to 2, kenBurns = false, lookName = "Vivid") { c ->
            c.saturation = 1.2f; c.contrast = 1.05f },
        T("memo", "Memories", "Photo", "Dreamy glow with a soft fade", 0xFFF48FB1.toInt(), 0xFF7E57C2.toInt(),
            3500, 6, "dream", "Memories", transName = "Dip to black", trans = null, dipMs = 500, kenBurns = true, lookName = "Dreamy glow") { c ->
            c.fx = 8; c.fxAmount = 0.6f; c.saturation = 1.1f; c.brightness = 0.03f },
        T("neon", "Neon Beat", "Music", "Quick flashes with vivid colour", 0xFFFF4081.toInt(), 0xFF00E5FF.toInt(),
            700, 10, "pop", null, transName = "Flash", trans = 1005 to 5, kenBurns = false, lookName = "Neon pop") { c ->
            c.filter = 1; c.contrast = 1.15f; c.saturation = 1.5f; c.fx = 2; c.fxAmount = 0.3f },
        T("vhs", "Retro VHS", "Fun", "Glitchy tape look", 0xFF26C6DA.toInt(), 0xFF6A1B9A.toInt(),
            1500, 8, "lofi", null, transName = "Glitch", trans = 1014 to 14, kenBurns = false, lookName = "VHS glitch") { c ->
            c.fx = 4; c.fxAmount = 0.25f; c.saturation = 1.25f; c.temp = 0.1f },
        T("glide", "Travel Glide", "Vlog", "Smooth slides for trips", 0xFF4DB6AC.toInt(), 0xFF01579B.toInt(),
            2200, 8, "lofi", null, transName = "Slide up", trans = 1009 to 10, kenBurns = true, lookName = "Warm travel") { c ->
            c.saturation = 1.15f; c.temp = 0.1f; c.contrast = 1.06f },
        T("noir", "Noir Drama", "Cinematic", "Black and white, tense", 0xFF616161.toInt(), 0xFF111111.toInt(),
            2800, 6, "dark", null, transName = "Dip to black", trans = null, dipMs = 450, kenBurns = true, lookName = "Black and white") { c ->
            c.filter = 4; c.contrast = 1.25f; c.fx = 1; c.fxAmount = 0.6f },
        T("bday", "Birthday Party", "Fun", "Bouncy pops with a title", 0xFFFFD54F.toInt(), 0xFFE91E63.toInt(),
            1800, 8, "funk", "Happy Birthday!", titleColor = 0xFFFFEB3B.toInt(), transName = "Pop", trans = 1002 to 2, kenBurns = true, lookName = "Vivid") { c ->
            c.saturation = 1.3f; c.brightness = 0.03f; c.contrast = 1.08f },
        T("trailer", "Epic Trailer", "Cinematic", "Dark, loud, big title", 0xFF37474F.toInt(), 0xFFB71C1C.toInt(),
            1600, 8, "epic", "COMING SOON", transName = "Zoom", trans = 1001 to 1, kenBurns = true, lookName = "High contrast") { c ->
            c.contrast = 1.25f; c.saturation = 0.85f; c.temp = -0.08f; c.fx = 1; c.fxAmount = 0.6f },
        T("vibes", "Good Vibes", "Music", "Spin cuts and warm colour", 0xFFFFA726.toInt(), 0xFFAB47BC.toInt(),
            1400, 10, "lofi", null, transName = "Spin", trans = 1004 to 4, kenBurns = false, lookName = "Warm") { c ->
            c.temp = 0.18f; c.saturation = 1.2f; c.brightness = 0.02f },
        T("love", "Slow Zoom", "Photo", "Long soft zooms, gentle fade", 0xFFEF9A9A.toInt(), 0xFF5E35B1.toInt(),
            4000, 5, "dream", "Us", transName = "Dip to black", trans = null, dipMs = 600, kenBurns = true, lookName = "Soft") { c ->
            c.fx = 8; c.fxAmount = 0.4f; c.brightness = 0.04f; c.saturation = 1.05f }
    )

    /** The Out animation that pairs with an In animation id. */
    private fun outFor(inId: Int): Int = (Anim.OUT.firstOrNull { it.src == inId } ?: Anim.EXTRA_OUT.firstOrNull { it.src == inId })?.def?.id ?: 1006

    val MORE: List<T> = listOf(
        T("poolside", "Motion Blur Beats", "Music", "Zoom cuts with a streaking blur", 0xFF6D4C41.toInt(), 0xFFFFCC80.toInt(),
            1100, 10, "pop", null, transName = "Motion blur", trans = outFor(292) to 292, kenBurns = true, lookName = "Warm contrast") { c ->
            c.filter = fi("Golden"); c.contrast = 1.12f; c.saturation = 1.1f; c.fx = 1; c.fxAmount = 0.35f },
        T("morph", "Photo Dissolve", "Photo", "Two photos melting into each other", 0xFFFFAB91.toInt(), 0xFF8D6E63.toInt(),
            1900, 6, "dream", null, transName = "Dissolve", trans = outFor(299) to 299, kenBurns = true, lookName = "Warm peach", sparkle = true) { c ->
            c.filter = fi("Peach"); c.fx = 8; c.fxAmount = 0.3f; c.brightness = 0.02f },
        T("swipeblur", "Swipe Blur", "Fun", "Fast swipes with blur", 0xFF00ACC1.toInt(), 0xFF7E57C2.toInt(),
            900, 10, "funk", null, transName = "Motion blur", trans = outFor(292) to 292, kenBurns = false, lookName = "Vivid") { c ->
            c.filter = fi("Vivid") },
        T("flashcut", "Zoom Flash Cut", "Cinematic", "Big slow zooms, white flashes", 0xFF455A64.toInt(), 0xFFECEFF1.toInt(),
            1600, 8, "epic", null, transName = "Flash", trans = 1005 to 5, kenBurns = true, lookName = "Cinema") { c ->
            c.filter = fi("Cinema"); c.fx = 1; c.fxAmount = 0.4f },
        T("lux", "Luxury Life", "Cinematic", "Teal and orange, slow zooms", 0xFF263238.toInt(), 0xFFFF8F00.toInt(),
            2200, 8, "epic", null, transName = "Zoom", trans = 1001 to 1, kenBurns = true, lookName = "Teal Orange") { c ->
            c.filter = fi("Teal Orange"); c.fx = 1; c.fxAmount = 0.4f },
        T("ny27", "New Year 2027", "Fun", "Pops and flashes with a big title", 0xFF1A237E.toInt(), 0xFFFFC107.toInt(),
            1300, 8, "funk", "HAPPY 2027", titleColor = 0xFFFFD54F.toInt(), transName = "Flash", trans = 1005 to 5, kenBurns = true, lookName = "Punch") { c ->
            c.filter = fi("Punch") },
        T("summer", "Summer Trip", "Vlog", "Sunny colours, quick slides", 0xFFFFCA28.toInt(), 0xFF29B6F6.toInt(),
            1800, 10, "pop", "Summer", transName = "Slide left", trans = 1007 to 8, kenBurns = true, lookName = "Sunny") { c ->
            c.filter = fi("Sunny") },
        T("rose", "Love Story", "Photo", "Rose tones, soft fades", 0xFFF06292.toInt(), 0xFF880E4F.toInt(),
            3000, 6, "dream", "Love Story", transName = "Dip to black", trans = null, dipMs = 500, kenBurns = true, lookName = "Rose") { c ->
            c.filter = fi("Rose"); c.fx = 8; c.fxAmount = 0.35f },
        T("gym", "Beast Mode", "Music", "Hard cuts, dark and punchy", 0xFF212121.toInt(), 0xFFD50000.toInt(),
            800, 10, "dark", "NO PAIN", transName = "Glitch", trans = 1014 to 14, kenBurns = false, lookName = "Dark punch") { c ->
            c.filter = fi("Dark"); c.contrast = 1.2f; c.saturation = 1.1f; c.fx = 1; c.fxAmount = 0.5f },
        T("cyber", "Night City", "Cinematic", "Cyber glow and glitches", 0xFF311B92.toInt(), 0xFF00E5FF.toInt(),
            1500, 8, "dark", null, transName = "Glitch", trans = 1014 to 14, kenBurns = true, lookName = "Cyber") { c ->
            c.filter = fi("Cyber"); c.fx = 4; c.fxAmount = 0.2f },
        T("golden", "Golden Hour", "Vlog", "Warm golden light", 0xFFFFB300.toInt(), 0xFFBF360C.toInt(),
            2600, 6, "lofi", null, transName = "Dip to black", trans = null, dipMs = 450, kenBurns = true, lookName = "Golden") { c ->
            c.filter = fi("Golden"); c.fx = 8; c.fxAmount = 0.3f },
        T("friends", "Friends Forever", "Fun", "Spins and candy colours", 0xFFAB47BC.toInt(), 0xFFFF7043.toInt(),
            1200, 10, "funk", "Squad", transName = "Spin", trans = 1004 to 4, kenBurns = false, lookName = "Candy") { c ->
            c.filter = fi("Candy") },
        T("nature", "Nature Calm", "Photo", "Forest greens, gentle slides", 0xFF2E7D32.toInt(), 0xFF004D40.toInt(),
            3000, 6, "dream", null, transName = "Slide up", trans = 1009 to 10, kenBurns = true, lookName = "Forest") { c ->
            c.filter = fi("Forest") },
        T("winter", "Cold Winter", "Cinematic", "Icy blue, slow and quiet", 0xFF90CAF9.toInt(), 0xFF0D47A1.toInt(),
            3200, 6, "dream", null, transName = "Dip to black", trans = null, dipMs = 500, kenBurns = true, lookName = "Ice") { c ->
            c.filter = fi("Ice"); c.fx = 8; c.fxAmount = 0.25f },
        T("film", "Old Film", "Fun", "Faded tape with grain", 0xFF8D6E63.toInt(), 0xFF3E2723.toInt(),
            2400, 6, "lofi", null, transName = "Dip to black", trans = null, dipMs = 400, kenBurns = true, lookName = "Film grain") { c ->
            c.filter = fi("Film"); c.fx = 5; c.fxAmount = 0.5f }
    )

    @Volatile private var remote: List<T> = emptyList()
    const val REMOTE_URL = "https://raw.githubusercontent.com/meaowsahat-collab/GatsMod-Cut-Android-Starter/main/templates.json"

    fun byId(id: String) = EVERY.firstOrNull { it.id == id }
    val EVERY: List<T> get() { val r = remote; val ids = r.map { it.id }.toSet(); return r + (ALL + MORE).filter { it.id !in ids } }
    fun isOnline(t: T) = remote.any { it.id == t.id }

    private fun col(s: String, d: Int): Int = try { Color.parseColor(s) } catch (_: Exception) { d }

    private fun parse(text: String): List<T> {
        val a = org.json.JSONArray(text)
        val out = ArrayList<T>()
        for (i in 0 until a.length()) {
            try {
                val o = a.getJSONObject(i)
                val tr = o.optString("trans", "Dissolve")
                val pair: Pair<Int, Int>? = when (tr) {
                    "Zoom" -> 1001 to 1; "Slide left" -> 1007 to 8; "Slide up" -> 1009 to 10; "Pop" -> 1002 to 2; "Flash" -> 1005 to 5
                    "Glitch" -> 1014 to 14; "Spin" -> 1004 to 4; "Motion blur" -> outFor(292) to 292; "Dissolve" -> outFor(299) to 299
                    else -> null
                }
                val music = o.optString("music", "").ifBlank { null }?.takeIf { m -> SoundGen.MUSIC.any { it.id == m } }
                val fname = o.optString("look", "")
                val fx = o.optInt("fx", 0); val fa = o.optDouble("fxAmount", 0.5).toFloat()
                val br = o.optDouble("brightness", 0.0).toFloat(); val co = o.optDouble("contrast", 1.0).toFloat()
                val sa = o.optDouble("saturation", 1.0).toFloat(); val te = o.optDouble("temp", 0.0).toFloat()
                out.add(T(o.getString("id"), o.getString("name"), o.optString("cat", "Fun"), o.optString("desc", ""),
                    col(o.optString("c1"), 0xFF455A64.toInt()), col(o.optString("c2"), 0xFF111111.toInt()),
                    o.optLong("slotMs", 1800L).coerceIn(500L, 6000L), o.optInt("maxClips", 8).coerceIn(2, 12), music,
                    o.optString("title", "").ifBlank { null }, col(o.optString("titleColor"), Color.WHITE),
                    tr, pair, o.optLong("dipMs", if (tr == "Dip to black") 500L else 0L), o.optBoolean("kenBurns", true),
                    o.optString("lookName", fname.ifBlank { "Custom" }), o.optBoolean("sparkle", false)) { c ->
                    if (fname.isNotBlank()) c.filter = fi(fname)
                    c.brightness = br; c.contrast = co; c.saturation = sa; c.temp = te; c.fx = fx; c.fxAmount = fa
                })
            } catch (_: Exception) {}
        }
        return out
    }

    @Volatile var status = "Not loaded yet"
    private val URLS = listOf(REMOTE_URL, "https://cdn.jsdelivr.net/gh/meaowsahat-collab/GatsMod-Cut-Android-Starter@main/templates.json")

    /** Template list kept in the app's GitHub repository (templates.json): new templates appear without updating the app. */
    fun loadRemote(ctx: Context, force: Boolean = false, onChange: (String) -> Unit) {
        val f = java.io.File(ctx.filesDir, "templates_remote.json")
        try { if (f.exists()) { remote = parse(f.readText()); status = "${remote.size} online templates (saved copy)" } } catch (e: Exception) { status = "Saved copy unreadable: ${e.message}" }
        Thread {
            if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < 20 * 60 * 1000L) { onChange(status); return@Thread }
            var err = ""
            for (u in URLS) {
                try {
                    val text = SoundsData.open(u).inputStream.bufferedReader().use { it.readText() }
                    val list = parse(text)
                    if (list.isEmpty()) { err = "The list was empty or unreadable"; continue }
                    f.writeText(text); remote = list
                    status = "${list.size} online templates loaded"
                    onChange(status); return@Thread
                } catch (e: Exception) { err = (e.message ?: e.javaClass.simpleName) }
            }
            status = "Could not load online templates: $err"
            onChange(status)
        }.also { it.priority = Thread.MIN_PRIORITY }.start()
    }

    /** A small four-point star with a soft glow, used as a twinkle on top of the edit. */
    fun sparkleFile(ctx: Context): java.io.File {
        val f = java.io.File(java.io.File(ctx.filesDir, "tpl").apply { mkdirs() }, "sparkle.png")
        if (f.exists() && f.length() > 100) return f
        val S = 192
        val b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val c = S / 2f
        p.shader = android.graphics.RadialGradient(c, c, c, intArrayOf(0x88FFF3C4.toInt(), 0x00FFF3C4), null, android.graphics.Shader.TileMode.CLAMP)
        cv.drawCircle(c, c, c, p); p.shader = null
        val path = android.graphics.Path()
        val r = c * 0.95f; val k = c * 0.12f
        path.moveTo(c, c - r); path.quadTo(c + k, c - k, c + r, c); path.quadTo(c + k, c + k, c, c + r)
        path.quadTo(c - k, c + k, c - r, c); path.quadTo(c - k, c - k, c, c - r); path.close()
        p.color = Color.WHITE; cv.drawPath(path, p)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    /** Puts a photo on a 9:16 picture: the photo fitted in the middle over a blurred, enlarged copy of itself. */
    fun stillFrame(ctx: Context, uri: Uri): Bitmap? {
        try {
            val W = 1080; val H = 1920
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0) return null
            var s = 1
            while (max(o.outWidth, o.outHeight) / s > 2400) s *= 2
            var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: return null
            val deg = try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                        6 -> 90; 3 -> 180; 8 -> 270; else -> 0
                    }
                } ?: 0
            } catch (_: Throwable) { 0 }
            if (deg != 0) {
                val m = Matrix(); m.postRotate(deg.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            // blurred background: shrink a lot, then stretch back
            val small = Bitmap.createScaledBitmap(bmp, max(2, W / 28), max(2, H / 28), true)
            val bs = max(W.toFloat() / small.width, H.toFloat() / small.height)
            cv.save(); cv.translate((W - small.width * bs) / 2f, (H - small.height * bs) / 2f); cv.scale(bs, bs); cv.drawBitmap(small, 0f, 0f, p); cv.restore()
            cv.drawColor(0x66000000)
            val aspectImg = bmp.width.toFloat() / bmp.height
            val close = Math.abs(aspectImg - W.toFloat() / H) < 0.18f
            val sc = if (close) max(W.toFloat() / bmp.width, H.toFloat() / bmp.height) else min(W.toFloat() / bmp.width, H.toFloat() / bmp.height)
            cv.save(); cv.translate((W - bmp.width * sc) / 2f, (H - bmp.height * sc) / 2f); cv.scale(sc, sc); cv.drawBitmap(bmp, 0f, 0f, p); cv.restore()
            return out
        } catch (_: Throwable) { return null }
    }
}

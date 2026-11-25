package com.gatsmod.cut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Online music lists and covers for the Sounds page, with a disk cache and a background preload that starts when the app opens. */
object SoundsData {
    private const val PREFS = "getcut_sounds"
    private const val TTL = 12L * 3600 * 1000
    val CATS = listOf("Trending", "Phonk", "Lofi", "Vlog", "Funny", "Cinematic", "Hip hop", "Chill", "Happy", "Sad", "Gaming", "Piano", "Beat", "Nature")

    @Volatile private var started = false

    fun preloadOn(ctx: Context) = ctx.getSharedPreferences(PREFS, 0).getBoolean("preload", true)
    fun setPreload(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("preload", on).apply()
    fun jamendoId(ctx: Context) = ctx.getSharedPreferences(PREFS, 0).getString("jamendo_id", "")?.trim() ?: ""

    private fun dir(ctx: Context) = File(ctx.filesDir, "sounds_cache").apply { mkdirs() }
    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun open(url: String): HttpURLConnection {
        var u = url
        for (i in 0 until 6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 12000; c.readTimeout = 20000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "GetCut/1.0 (Android video editor)")
            val code = c.responseCode
            if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location") ?: break).toString(); c.disconnect(); continue }
            if (code != 200) { c.disconnect(); throw java.io.IOException("HTTP $code") }
            return c
        }
        throw java.io.IOException("Too many redirects")
    }

    /** Fresh copy from disk if there is one (instant); otherwise from the internet. If the internet fails, an old copy is better than nothing. */
    fun cachedText(ctx: Context, url: String, ttl: Long = TTL): String {
        val f = File(dir(ctx), md5(url) + ".json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < ttl) return f.readText()
        return try {
            val text = open(url).inputStream.bufferedReader().use { it.readText() }
            val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(text); tmp.renameTo(f)
            text
        } catch (e: Exception) {
            if (f.exists()) f.readText() else throw e
        }
    }

    fun url(jid: String, query: String, cat: Int, commercial: Boolean, page: Int): String {
        if (jid.isNotBlank()) {
            val sb = StringBuilder("https://api.jamendo.com/v3.0/tracks/?client_id=").append(URLEncoder.encode(jid, "UTF-8"))
                .append("&format=json&limit=20&offset=").append((page - 1) * 20).append("&imagesize=200&audioformat=mp32&include=licenses")
            when {
                query.isBlank() -> sb.append("&order=popularity_week")
                cat > 0 -> sb.append("&order=popularity_month&fuzzytags=").append(URLEncoder.encode(query.lowercase().replace(" ", "+"), "UTF-8"))
                else -> sb.append("&order=relevance&search=").append(URLEncoder.encode(query, "UTF-8"))
            }
            return sb.toString()
        }
        val q = if (query.isBlank()) "instrumental" else query
        return "https://api.openverse.org/v1/audio/?q=" + URLEncoder.encode(q, "UTF-8") + "&category=music&page_size=20&page=$page" +
            (if (commercial) "&license_type=commercial" else "")
    }

    private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k)

    /** Returns the playable tracks and how many items the server sent (to know if there is another page). */
    fun parse(text: String, jamendo: Boolean, commercial: Boolean): Pair<List<SoundsPage.Track>, Int> {
        val o = JSONObject(text)
        val list = mutableListOf<SoundsPage.Track>()
        val arr: JSONArray
        if (jamendo) {
            val hdr = o.optJSONObject("headers")
            if (hdr != null && hdr.optString("status") == "failed") throw java.io.IOException("jamendo: " + hdr.optString("error_message"))
            arr = o.optJSONArray("results") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val u = r.str("audio").ifBlank { r.str("audiodownload") }
                if (!u.startsWith("http")) continue
                val seg = r.str("license_ccurl").substringAfter("/licenses/", "").trim('/').split("/")
                val t = SoundsPage.Track(r.str("id"), r.str("name"), r.str("artist_name"), u, r.str("image").takeIf { it.startsWith("http") },
                    r.optLong("duration", 0L) * 1000L, seg.getOrNull(0) ?: "", seg.getOrNull(1) ?: "", r.str("shareurl"), "mp3")
                if (commercial && !t.commercialOk) continue
                list.add(t)
            }
        } else {
            arr = o.optJSONArray("results") ?: JSONArray()
            val rx = Regex("\\.(mp3|ogg|oga|opus|wav|m4a|aac|flac)(\\?|$)", RegexOption.IGNORE_CASE)
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val u = r.str("url")
                if (!u.startsWith("http")) continue
                val ft = r.str("filetype").lowercase()
                val ext = rx.find(u)?.groupValues?.get(1)?.lowercase() ?: (if (ft == "mpeg" || ft.isBlank()) "mp3" else ft)
                list.add(SoundsPage.Track(r.str("id").ifBlank { u.hashCode().toString() }, r.str("title"), r.str("creator"), u,
                    r.str("thumbnail").takeIf { it.startsWith("http") }, r.optLong("duration", 0L), r.str("license"), r.str("license_version"),
                    r.str("foreign_landing_url"), ext))
            }
        }
        return list to arr.length()
    }

    /** Cover picture: from disk if saved, else downloaded once and saved. */
    fun cover(ctx: Context, url: String): Bitmap? {
        val f = File(File(dir(ctx), "thumbs").apply { mkdirs() }, md5(url))
        try {
            if (f.exists() && f.length() > 100) BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })?.let { return it }
            val tmp = File(f.parentFile, f.name + ".tmp")
            open(url).inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            tmp.renameTo(f)
            return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })
        } catch (_: Exception) { return null }
    }

    private fun metered(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
    } catch (_: Exception) { true }

    /**
     * Runs once per app start, quietly, on a low-priority thread:
     * 1) makes the built-in Sound FX and Brand music files, 2) saves the first page of the main music categories,
     * 3) on Wi-Fi only, saves the cover pictures. Then the Sounds page opens with everything already there.
     */
    fun preload(ctx0: Context) {
        val ctx = ctx0.applicationContext
        if (started || !preloadOn(ctx)) return
        started = true
        Thread {
            try {
                SoundGen.SFX.forEach { SoundGen.ensureSfx(ctx, it) }
                SoundGen.MUSIC.forEach { SoundGen.ensureMusic(ctx, it) }
            } catch (_: Throwable) {}
            try {
                val jid = jamendoId(ctx)
                val wifi = !metered(ctx)
                for (c in 0..5) {
                    val name = CATS[c]
                    val q = if (c == 0) "" else name
                    val text = cachedText(ctx, url(jid, q, c, false, 1))
                    if (wifi) {
                        val (tracks, _) = parse(text, jid.isNotBlank(), false)
                        tracks.take(10).forEach { t -> t.thumb?.let { cover(ctx, it) } }
                    }
                    Thread.sleep(1500)
                }
                try { cachedText(ctx, "https://api.openverse.org/v1/audio/?q=click&category=sound_effect&page_size=20&page=1") } catch (_: Exception) {}
            } catch (_: Throwable) {}
        }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true; name = "getcut-preload" }.start()
    }
}

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

    fun clearCache(ctx: Context) { try { dir(ctx).listFiles()?.forEach { it.deleteRecursively() } } catch (_: Exception) {} }

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

    private fun dropCache(ctx: Context, url: String) { try { File(dir(ctx), md5(url) + ".json").delete() } catch (_: Exception) {} }

    private fun commonsUrl(q: String, page: Int) = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=20" +
        "&gsroffset=" + ((page - 1) * 20) + "&gsrsearch=" + URLEncoder.encode("$q filetype:audio", "UTF-8") +
        "&prop=imageinfo&iiprop=url%7Cmime&iiextmetadatafilter=LicenseShortName%7CArtist&iiextmetadatalanguage=en"

    /** Wikimedia Commons audio files (Creative Commons / public domain). */
    private fun parseCommons(text: String): Pair<List<SoundsPage.Track>, Int> {
        val pages = JSONObject(text).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList<SoundsPage.Track>() to 0
        val list = mutableListOf<SoundsPage.Track>()
        val keys = pages.keys().asSequence().toList()
        for (k in keys) {
            val p = pages.getJSONObject(k)
            val ii = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val u = ii.str("url")
            if (!u.startsWith("http") || !ii.str("mime").startsWith("audio")) continue
            val meta = ii.optJSONObject("extmetadata")
            val licName = meta?.optJSONObject("LicenseShortName")?.str("value")?.lowercase() ?: ""
            val artist = (meta?.optJSONObject("Artist")?.str("value") ?: "").replace(Regex("<[^>]*>"), "").trim()
            val m = Regex("cc[- ]by(-sa|-nc-sa|-nc-nd|-nc|-nd)?[- ]?([0-9.]+)?").find(licName)
            val lic = when {
                licName.contains("cc0") || licName.contains("cc-zero") -> "cc0"
                licName.contains("public domain") || licName.contains("pd") -> "pdm"
                m != null -> "by" + (m.groupValues[1])
                else -> "by-sa"
            }
            val ver = m?.groupValues?.get(2) ?: ""
            val title = p.str("title").removePrefix("File:").substringBeforeLast('.').replace('_', ' ')
            val ext = u.substringAfterLast('.', "ogg").lowercase().take(4)
            list.add(SoundsPage.Track("wm_" + p.optLong("pageid"), title, artist, u, null, 0L, lic, ver,
                "https://commons.wikimedia.org/wiki/" + URLEncoder.encode(p.str("title").replace(' ', '_'), "UTF-8"), ext))
        }
        return list to keys.size
    }

    /** Sound effects: Openverse sound_effect category -> Openverse text search -> Wikimedia Commons. Empty answers are never kept in the cache. */
    fun sfxSearch(ctx: Context, query: String, page: Int): Triple<List<SoundsPage.Track>, Int, String> {
        val q = if (query.isBlank()) "click" else query
        val enc = URLEncoder.encode(q, "UTF-8")
        val dbg = StringBuilder()
        fun short(l: List<SoundsPage.Track>) = l.filter { it.durMs <= 0 || it.durMs <= 30000 }
        // 1
        val u1 = "https://api.openverse.org/v1/audio/?q=$enc&category=sound_effect&page_size=20&page=$page"
        try {
            val (l, c) = parse(cachedText(ctx, u1), false, false)
            dbg.append("openverse ").append(c).append(" / ")
            val sl = short(l)
            if (sl.isNotEmpty()) return Triple(sl, c, "")
            dropCache(ctx, u1)
        } catch (e: Exception) { dbg.append("openverse ").append(e.message).append(" / "); dropCache(ctx, u1) }
        // 2
        val u2 = "https://api.openverse.org/v1/audio/?q=" + URLEncoder.encode("$q sound effect", "UTF-8") + "&page_size=20&page=$page"
        try {
            val (l, c) = parse(cachedText(ctx, u2), false, false)
            dbg.append("openverse2 ").append(c).append(" / ")
            val sl = short(l).filter { it.durMs > 0 }
            if (sl.isNotEmpty()) return Triple(sl, c, "")
            dropCache(ctx, u2)
        } catch (e: Exception) { dbg.append("openverse2 ").append(e.message).append(" / "); dropCache(ctx, u2) }
        // 3
        val u3 = commonsUrl(q, page)
        try {
            val (l, c) = parseCommons(cachedText(ctx, u3))
            dbg.append("commons ").append(c)
            if (l.isNotEmpty()) return Triple(l, c, "")
            dropCache(ctx, u3)
        } catch (e: Exception) { dbg.append("commons ").append(e.message); dropCache(ctx, u3) }
        return Triple(emptyList(), 0, dbg.toString())
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
                try { sfxSearch(ctx, "click", 1) } catch (_: Exception) {}
            } catch (_: Throwable) {}
        }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true; name = "getcut-preload" }.start()
    }
}

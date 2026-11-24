package com.gatsmod.cut

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * "Sounds" page like CapCut's: search, categories, preview, bookmarks and Add.
 * The music comes from Openverse (openverse.org), a free catalogue of Creative Commons music.
 * Every track shows its licence; credit and commercial-use notes are kept for the Copyright check.
 */
class SoundsPage(
    private val act: Activity,
    private val onAdd: (uri: String, durMs: Long, label: String) -> Unit,
    private val onMyFiles: () -> Unit
) {
    class Track(
        val id: String, val title: String, val creator: String, val url: String, val thumb: String?,
        val durMs: Long, val license: String, val version: String, val landing: String, val ext: String
    ) {
        val licenseName: String get() = when (license.lowercase()) {
            "cc0" -> "CC0"; "pdm" -> "Public domain"
            else -> "CC " + license.uppercase() + (if (version.isNotBlank()) " $version" else "")
        }
        val commercialOk: Boolean get() = !license.lowercase().contains("nc")
        val needsCredit: Boolean get() = license.lowercase() !in listOf("cc0", "pdm")
        fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("creator", creator).put("url", url)
            .put("thumb", thumb ?: "").put("dur", durMs).put("lic", license).put("ver", version).put("landing", landing).put("ext", ext)
        companion object {
            fun from(o: JSONObject) = Track(o.optString("id"), o.optString("title"), o.optString("creator"), o.optString("url"),
                o.optString("thumb").ifBlank { null }, o.optLong("dur"), o.optString("lic"), o.optString("ver"), o.optString("landing"), o.optString("ext", "mp3"))
        }
    }

    companion object {
        private const val PREFS = "getcut_sounds"
        private val thumbCache = android.util.LruCache<String, Bitmap>(60)

        fun creditFor(ctx: android.content.Context, uri: String): Track? = try {
            JSONObject(ctx.getSharedPreferences(PREFS, 0).getString("credits", "{}")!!).optJSONObject(uri)?.let { Track.from(it) }
        } catch (_: Exception) { null }

        private fun saveCredit(ctx: android.content.Context, uri: String, t: Track) {
            val sp = ctx.getSharedPreferences(PREFS, 0)
            val o = try { JSONObject(sp.getString("credits", "{}")!!) } catch (_: Exception) { JSONObject() }
            o.put(uri, t.toJson())
            sp.edit().putString("credits", o.toString()).apply()
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val CYAN = 0xFF25F4EE.toInt()
    private val prefs = act.getSharedPreferences(PREFS, 0)

    private var dialog: Dialog? = null
    private var listBox: LinearLayout? = null
    private var headingView: TextView? = null
    private val results = mutableListOf<Track>()
    private var query = ""
    private var commercial = false
    private var tab = 0 // 0 online, 1 saved
    private var page = 1
    private var hasMore = false
    private var loading = false
    private var error: String? = null
    private var selected: Track? = null
    private var player: MediaPlayer? = null
    private var playerReady = false
    private var adding = false
    private var gen = 0

    private val categories = listOf("Trending", "Phonk", "Lofi", "Vlog", "Funny", "Cinematic", "Hip hop", "Chill", "Happy", "Sad", "Gaming", "Piano", "Beat", "Nature")
    private var cat = 0

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    private fun saved(): MutableList<Track> {
        val out = mutableListOf<Track>()
        try { val a = JSONArray(prefs.getString("saved", "[]")); for (i in 0 until a.length()) out.add(Track.from(a.getJSONObject(i))) } catch (_: Exception) {}
        return out
    }

    private fun isSaved(t: Track) = saved().any { it.id == t.id }

    private fun toggleSaved(t: Track) {
        val l = saved()
        if (l.any { it.id == t.id }) l.removeAll { it.id == t.id } else l.add(0, t)
        val a = JSONArray(); l.take(100).forEach { a.put(it.toJson()) }
        prefs.edit().putString("saved", a.toString()).apply()
    }

    // ------------------------------------------------------------------ UI

    fun show() {
        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog = d
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF121214.toInt())
        root.setPadding(dp(16), dp(30), dp(16), 0)

        // top: close + All / Commercial
        val top = LinearLayout(act); top.gravity = Gravity.CENTER_VERTICAL
        val close = ImageView(act); close.setImageResource(R.drawable.ic_close); close.setPadding(dp(8), dp(8), dp(8), dp(8))
        close.setOnClickListener { d.dismiss() }
        top.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
        val allT = TextView(act); val comT = TextView(act)
        fun styleTabs() {
            allT.setTextColor(if (!commercial) Color.WHITE else 0xFF8A8A92.toInt()); allT.typeface = if (!commercial) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            comT.setTextColor(if (commercial) Color.WHITE else 0xFF8A8A92.toInt()); comT.typeface = if (commercial) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        allT.text = "All"; comT.text = "Commercial"; allT.textSize = 18f; comT.textSize = 18f
        allT.gravity = Gravity.CENTER; comT.gravity = Gravity.CENTER
        allT.setOnClickListener { commercial = false; styleTabs(); tab = 0; search(true) }
        comT.setOnClickListener { commercial = true; styleTabs(); tab = 0; search(true) }
        styleTabs()
        top.addView(allT, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(comT, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        // search
        val box = EditText(act).apply {
            hint = "Search music (phonk, lofi, vlog...)"; setHintTextColor(0xFF77777F.toInt()); setTextColor(Color.WHITE); textSize = 15f
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFF2A2A2E.toInt()) }
            setOnEditorActionListener { v, a, _ ->
                if (a == EditorInfo.IME_ACTION_SEARCH) {
                    query = v.text.toString().trim(); tab = 0; cat = -1
                    (v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                    refreshChips(); search(true); true
                } else false
            }
        }
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })

        // categories
        val chipsRow = LinearLayout(act); chipsRow.orientation = LinearLayout.HORIZONTAL
        chipHolder = chipsRow
        val hs = HorizontalScrollView(act); hs.isHorizontalScrollBarEnabled = false; hs.addView(chipsRow)
        root.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) })
        categories.forEachIndexed { i, name ->
            val c = TextView(act); c.text = name; c.textSize = 14f; c.gravity = Gravity.CENTER; c.setPadding(dp(16), 0, dp(16), 0)
            c.setOnClickListener { cat = i; query = if (i == 0) "" else name; box.setText(""); tab = 0; refreshChips(); search(true) }
            chipsRow.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { rightMargin = dp(8) })
        }
        refreshChips()

        // sub tabs: recommended / my files / saved
        val tabs = LinearLayout(act); tabs.orientation = LinearLayout.HORIZONTAL
        fun tabIcon(res: Int, sel: Boolean, act1: () -> Unit): View {
            val f = FrameLayout(act)
            val iv = ImageView(act); iv.setImageResource(res); iv.setColorFilter(if (sel) Color.WHITE else 0xFF77777F.toInt())
            f.addView(iv, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            val bar = View(act); bar.setBackgroundColor(if (sel) CYAN else 0xFF2C2C31.toInt())
            f.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.BOTTOM))
            f.setOnClickListener { act1() }
            return f
        }
        tabs.addView(tabIcon(R.drawable.ic_star, tab == 0) { tab = 0; rebuildTabs(tabs, ::tabIcon); render() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        tabs.addView(tabIcon(R.drawable.ic_folder, false) { dialog?.dismiss(); onMyFiles() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        tabs.addView(tabIcon(R.drawable.ic_bookmark, tab == 1) { tab = 1; rebuildTabs(tabs, ::tabIcon); render() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })
        tabHolder = tabs

        val heading = TextView(act); heading.setTextColor(Color.WHITE); heading.textSize = 20f; heading.setPadding(0, dp(14), 0, dp(6))
        headingView = heading
        root.addView(heading)
        val lb = LinearLayout(act); lb.orientation = LinearLayout.VERTICAL
        listBox = lb
        val sc = ScrollView(act); sc.addView(lb)
        root.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        d.setContentView(root)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF121214.toInt()))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        d.setOnDismissListener { stopPlayer(); gen++ }
        d.show()
        search(true)
    }

    private var chipHolder: LinearLayout? = null
    private var tabHolder: LinearLayout? = null

    private fun rebuildTabs(row: LinearLayout, mk: (Int, Boolean, () -> Unit) -> View) {
        // re-colour the two selectable tab icons
        for (i in 0 until row.childCount) {
            val f = row.getChildAt(i) as FrameLayout
            val sel = (i == 0 && tab == 0) || (i == 2 && tab == 1)
            (f.getChildAt(0) as ImageView).setColorFilter(if (sel) Color.WHITE else 0xFF77777F.toInt())
            f.getChildAt(1).setBackgroundColor(if (sel) CYAN else 0xFF2C2C31.toInt())
        }
    }

    private fun refreshChips() {
        val row = chipHolder ?: return
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i) as TextView
            val sel = i == cat
            c.setTextColor(if (sel) Color.BLACK else Color.WHITE)
            c.background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (sel) CYAN else 0xFF2A2A2E.toInt()) }
        }
    }

    private fun render() {
        val lb = listBox ?: return
        lb.removeAllViews()
        val list: List<Track> = if (tab == 1) saved() else results
        headingView?.text = when {
            tab == 1 -> "Saved"
            query.isBlank() -> "Recommended"
            else -> "Results for \"$query\""
        }
        if (tab == 0 && loading && results.isEmpty()) { lb.addView(note("Loading music...")); return }
        if (tab == 0 && error != null && results.isEmpty()) {
            lb.addView(note(error!!))
            lb.addView(pill("Try again") { search(true) })
            return
        }
        if (list.isEmpty()) { lb.addView(note(if (tab == 1) "Tap the bookmark on a track to save it here." else "No music found. Try another word.")); return }
        list.forEach { lb.addView(row(it)) }
        if (tab == 0 && hasMore) lb.addView(pill(if (loading) "Loading..." else "Load more") { if (!loading) { page++; search(false) } })
        lb.addView(note("Free music from Openverse (Creative Commons). Check each track's licence tag; tracks that need credit are listed in Audio > Copyright."))
    }

    private fun note(s: String) = TextView(act).apply { text = s; setTextColor(0xFF8A8A92.toInt()); textSize = 13f; setPadding(0, dp(14), 0, dp(14)) }

    private fun pill(s: String, onClick: () -> Unit) = TextView(act).apply {
        text = s; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xFF2A2A2E.toInt()) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8); bottomMargin = dp(8) }
    }

    private fun mmss(ms: Long): String { val s = ms / 1000; return "%02d:%02d".format(s / 60, s % 60) }

    private fun row(t: Track): View {
        val isSel = selected?.id == t.id
        val wrap = LinearLayout(act); wrap.orientation = LinearLayout.VERTICAL
        if (isSel) wrap.background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xFF1E2230.toInt()) }
        wrap.setPadding(dp(8), dp(8), dp(8), dp(8))
        val line = LinearLayout(act); line.gravity = Gravity.CENTER_VERTICAL
        val art = FrameLayout(act)
        val iv = ImageView(act); iv.scaleType = ImageView.ScaleType.CENTER_CROP
        val hue = (kotlin.math.abs(t.id.hashCode()) % 360).toFloat()
        iv.background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.45f))) }
        iv.clipToOutline = true
        art.addView(iv, FrameLayout.LayoutParams(dp(48), dp(48)))
        val glyph = ImageView(act); glyph.setImageResource(if (isSel && playerReady && player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
        glyph.setPadding(dp(12), dp(12), dp(12), dp(12))
        art.addView(glyph, FrameLayout.LayoutParams(dp(48), dp(48)))
        loadThumb(t, iv)
        line.addView(art, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(14) })
        val col = LinearLayout(act); col.orientation = LinearLayout.VERTICAL
        col.addView(TextView(act).apply { text = t.title.ifBlank { "Untitled" }; setTextColor(Color.WHITE); textSize = 16f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val sub = (if (t.durMs > 0) mmss(t.durMs) + " · " else "") + t.creator.ifBlank { "Unknown" } + " · " + t.licenseName + (if (!t.commercialOk) " (non-commercial)" else "")
        col.addView(TextView(act).apply { text = sub; setTextColor(if (t.commercialOk) 0xFF9A9AA2.toInt() else 0xFFE0A050.toInt()); textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (isSel) {
            val bm = ImageView(act); bm.setImageResource(if (isSaved(t)) R.drawable.ic_bookmark_fill else R.drawable.ic_bookmark)
            bm.setPadding(dp(8), dp(8), dp(8), dp(8))
            bm.setOnClickListener { toggleSaved(t); if (tab == 1) selected = null; render() }
            line.addView(bm, LinearLayout.LayoutParams(dp(40), dp(40)))
            val add = FrameLayout(act)
            add.background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(CYAN) }
            val plus = ImageView(act); plus.setImageResource(R.drawable.ic_add); plus.setColorFilter(Color.BLACK)
            add.addView(plus, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            add.setOnClickListener { addTrack(t) }
            line.addView(add, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(6) })
        }
        wrap.addView(line)
        if (isSel) {
            val bar = LinearLayout(act); bar.gravity = Gravity.CENTER_VERTICAL
            val time = TextView(act); time.text = "00:00"; time.setTextColor(Color.WHITE); time.textSize = 14f
            bar.addView(time, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
            val sb = SeekBar(act)
            sb.progressTintList = android.content.res.ColorStateList.valueOf(CYAN)
            sb.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            sb.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x55FFFFFF)
            sb.max = 1000
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val pl = player ?: return
                    if (fromUser && playerReady && pl.duration > 0) pl.seekTo((pl.duration.toLong() * p / 1000).toInt())
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            bar.addView(sb, LinearLayout.LayoutParams(0, dp(30), 1f))
            wrap.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(6) })
            progressTime = time; progressBar = sb
        }
        wrap.setOnClickListener { if (!isSel) select(t) else togglePlay() }
        line.setOnClickListener { if (!isSel) select(t) else togglePlay() }
        wrap.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
        return wrap
    }

    private var progressTime: TextView? = null
    private var progressBar: SeekBar? = null
    private val tick = object : Runnable {
        override fun run() {
            val pl = player
            if (pl != null && playerReady) {
                try {
                    val pos = pl.currentPosition; val dur = pl.duration
                    progressTime?.text = mmss(pos.toLong())
                    if (dur > 0) progressBar?.progress = (pos.toLong() * 1000 / dur).toInt()
                } catch (_: Exception) {}
            }
            ui.postDelayed(this, 250)
        }
    }

    private fun loadThumb(t: Track, iv: ImageView) {
        val u = t.thumb ?: return
        thumbCache.get(u)?.let { iv.setImageBitmap(it); return }
        iv.tag = u
        pool.execute {
            try {
                val c = open(u)
                val bmp = c.inputStream.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) }
                if (bmp != null) { thumbCache.put(u, bmp); ui.post { if (iv.tag == u) iv.setImageBitmap(bmp) } }
            } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ playback

    private fun select(t: Track) {
        selected = t
        startPlayer(t)
        render()
    }

    private fun startPlayer(t: Track) {
        stopPlayer()
        val my = ++gen
        try {
            val mp = MediaPlayer()
            player = mp; playerReady = false
            mp.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build())
            mp.setDataSource(t.url)
            mp.setOnPreparedListener { if (gen == my) { playerReady = true; mp.start(); render() } }
            mp.setOnCompletionListener { if (gen == my) render() }
            mp.setOnErrorListener { _, _, _ -> if (gen == my) { toast("Could not play this track"); playerReady = false }; true }
            mp.prepareAsync()
            ui.removeCallbacks(tick); ui.post(tick)
        } catch (_: Exception) { toast("Could not play this track") }
    }

    private fun togglePlay() {
        val pl = player ?: return
        if (!playerReady) return
        try { if (pl.isPlaying) pl.pause() else pl.start() } catch (_: Exception) {}
        render()
    }

    private fun stopPlayer() {
        ui.removeCallbacks(tick)
        try { player?.release() } catch (_: Exception) {}
        player = null; playerReady = false
    }

    // ------------------------------------------------------------------ network

    private fun toast(s: String) = ui.post { Toast.makeText(act, s, Toast.LENGTH_SHORT).show() }

    private fun open(url: String): HttpURLConnection {
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

    private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k)

    private fun search(reset: Boolean) {
        if (reset) { page = 1; results.clear(); hasMore = false; selected = null; stopPlayer() }
        if (tab != 0) { render(); return }
        loading = true; error = null
        render()
        val q = if (query.isBlank()) "instrumental" else query
        val url = "https://api.openverse.org/v1/audio/?q=" + URLEncoder.encode(q, "UTF-8") +
            "&category=music&page_size=20&page=$page" + (if (commercial) "&license_type=commercial" else "")
        val my = ++searchGen
        pool.execute {
            try {
                val text = open(url).inputStream.bufferedReader().use { it.readText() }
                val arr = JSONObject(text).optJSONArray("results") ?: JSONArray()
                val list = mutableListOf<Track>()
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    val u = r.str("url")
                    if (!u.startsWith("http")) continue
                    val ft = r.str("filetype").lowercase().ifBlank { u.substringAfterLast('.', "mp3").take(4).lowercase() }
                    if (ft !in listOf("mp3", "mpeg", "ogg", "oga", "opus", "wav", "m4a", "aac", "flac")) continue
                    list.add(Track(r.str("id"), r.str("title"), r.str("creator"), u, r.str("thumbnail").takeIf { it.startsWith("http") },
                        r.optLong("duration", 0L), r.str("license"), r.str("license_version"), r.str("foreign_landing_url"), if (ft == "mpeg") "mp3" else ft))
                }
                val count = arr.length()
                ui.post { if (my == searchGen) { results.addAll(list); hasMore = count >= 20; loading = false; render() } }
            } catch (e: Exception) {
                val busy = e.message?.contains("429") == true
                ui.post { if (my == searchGen) { loading = false; error = if (busy) "The free music server is busy right now. Wait a minute and try again." else "Could not load music. Check your internet connection."; render() } }
            }
        }
    }

    private var searchGen = 0

    private fun addTrack(t: Track) {
        if (adding) return
        adding = true
        toast("Downloading...")
        pool.execute {
            try {
                val dir = File(act.filesDir, "music").apply { mkdirs() }
                val f = File(dir, "dl_" + t.id.filter { it.isLetterOrDigit() }.take(40) + "." + t.ext.ifBlank { "mp3" })
                if (!f.exists() || f.length() < 1000) {
                    val tmp = File(dir, f.name + ".tmp")
                    open(t.url).inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    tmp.renameTo(f)
                }
                val info = MediaProbe.probe(act, Uri.fromFile(f))
                val dur = info?.durationMs ?: t.durMs
                if (dur <= 0) throw java.io.IOException("unreadable")
                val uri = Uri.fromFile(f).toString()
                saveCredit(act, uri, t)
                ui.post { adding = false; stopPlayer(); dialog?.dismiss(); onAdd(uri, dur, t.title.ifBlank { "Music" }) }
            } catch (_: Exception) {
                ui.post { adding = false; Toast.makeText(act, "Could not download this track", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}

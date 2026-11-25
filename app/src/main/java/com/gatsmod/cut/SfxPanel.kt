package com.gatsmod.cut

import android.app.Activity
import android.app.Dialog
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
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/**
 * "Sound FX" panel like CapCut's bottom sheet: search, category tabs, tap to listen, bookmark, + to add.
 * Online effects come from Openverse (Creative Commons sound effects); "Built-in" are made inside Get Cut and work offline.
 */
class SfxPanel(private val act: Activity, private val onAdd: (uri: String, durMs: Long, label: String) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private val CYAN = 0xFF25F4EE.toInt()
    private val prefs = act.getSharedPreferences("getcut_sounds", 0)

    // tab label -> search word. Index 0 = saved (bookmark), 1 = built-in.
    private val tabs = listOf("" to "", "Built-in" to "", "Trending" to "click", "Funny" to "funny", "Swooshes" to "swoosh", "Impact" to "impact", "Pop" to "pop",
        "Ding" to "bell", "Laugh" to "laugh", "Applause" to "applause", "Cartoon" to "cartoon", "Horror" to "horror", "Game" to "game",
        "Notification" to "notification", "Nature" to "nature", "Animals" to "animal", "Human" to "voice")

    private var dialog: Dialog? = null
    private var listBox: LinearLayout? = null
    private var tabRow: LinearLayout? = null
    private val items = mutableListOf<SoundsPage.Track>()
    private var tab = 2
    private var query = ""
    private var page = 1
    private var hasMore = false
    private var loading = false
    private var error: String? = null
    private var player: MediaPlayer? = null
    private var playingId: String? = null
    private var adding = false
    private var gen = 0

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun toast(s: String) = ui.post { Toast.makeText(act, s, Toast.LENGTH_SHORT).show() }

    private fun saved(): MutableList<SoundsPage.Track> {
        val out = mutableListOf<SoundsPage.Track>()
        try { val a = JSONArray(prefs.getString("saved_sfx", "[]")); for (i in 0 until a.length()) out.add(SoundsPage.Track.from(a.getJSONObject(i))) } catch (_: Exception) {}
        return out
    }

    private fun isSaved(t: SoundsPage.Track) = saved().any { it.id == t.id }

    private fun toggleSaved(t: SoundsPage.Track) {
        val l = saved()
        if (l.any { it.id == t.id }) l.removeAll { it.id == t.id } else l.add(0, t)
        val a = JSONArray(); l.take(100).forEach { a.put(it.toJson()) }
        prefs.edit().putString("saved_sfx", a.toString()).apply()
    }

    fun show() {
        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar)
        dialog = d
        val root = LinearLayout(act); root.orientation = LinearLayout.VERTICAL
        root.background = GradientDrawable().apply { cornerRadii = floatArrayOf(dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), 0f, 0f, 0f, 0f); setColor(0xFF222224.toInt()) }
        root.setPadding(dp(16), dp(14), dp(16), 0)

        val top = LinearLayout(act); top.gravity = Gravity.CENTER_VERTICAL
        val box = EditText(act).apply {
            hint = "Search sound effects (click, whoosh, laugh...)"; setHintTextColor(0xFF77777F.toInt()); setTextColor(Color.WHITE); textSize = 14f
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH; setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFF38383B.toInt()) }
            setOnEditorActionListener { v, a, _ ->
                if (a == EditorInfo.IME_ACTION_SEARCH) {
                    query = v.text.toString().trim(); tab = 2
                    (v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                    styleTabs(); load(true); true
                } else false
            }
        }
        top.addView(box, LinearLayout.LayoutParams(0, dp(42), 1f))
        val ok = ImageView(act); ok.setImageResource(R.drawable.ic_check); ok.setPadding(dp(10), dp(10), dp(10), dp(10))
        ok.setOnClickListener { d.dismiss() }
        top.addView(ok, LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(6) })
        root.addView(top)

        val row = LinearLayout(act); row.orientation = LinearLayout.HORIZONTAL
        tabRow = row
        val hs = HorizontalScrollView(act); hs.isHorizontalScrollBarEnabled = false; hs.addView(row)
        root.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(6) })
        tabs.forEachIndexed { i, (name, _) ->
            val col = LinearLayout(act); col.orientation = LinearLayout.VERTICAL; col.gravity = Gravity.CENTER_HORIZONTAL
            val v: View = if (i == 0) ImageView(act).apply { setImageResource(R.drawable.ic_bookmark); setPadding(0, dp(2), 0, dp(2)) }
                else TextView(act).apply { text = name; textSize = 16f; gravity = Gravity.CENTER }
            col.addView(v, LinearLayout.LayoutParams(if (i == 0) dp(24) else ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
            val bar = View(act); col.addView(bar, LinearLayout.LayoutParams(dp(26), dp(2)).apply { topMargin = dp(4) })
            col.setOnClickListener { tab = i; query = tabs[i].second; box.setText(""); styleTabs(); load(true) }
            row.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(20) })
        }
        styleTabs()
        val line = View(act); line.setBackgroundColor(0xFF343437.toInt())
        root.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        val lb = LinearLayout(act); lb.orientation = LinearLayout.VERTICAL
        listBox = lb
        val sc = ScrollView(act); sc.addView(lb)
        root.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        d.setContentView(root)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setDimAmount(0f)
            val h = (act.resources.displayMetrics.heightPixels * 0.58f).toInt()
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        d.setOnDismissListener { stopPlayer(); gen++ }
        d.show()
        load(true)
    }

    private fun styleTabs() {
        val row = tabRow ?: return
        for (i in 0 until row.childCount) {
            val col = row.getChildAt(i) as LinearLayout
            val sel = i == tab
            val v = col.getChildAt(0)
            if (v is TextView) { v.setTextColor(if (sel) Color.WHITE else 0xFF8A8A92.toInt()); v.typeface = if (sel) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT }
            if (v is ImageView) v.setColorFilter(if (sel) Color.WHITE else 0xFF8A8A92.toInt())
            col.getChildAt(1).setBackgroundColor(if (sel) CYAN else Color.TRANSPARENT)
        }
    }

    private fun load(reset: Boolean) {
        val my = ++gen
        if (reset) { page = 1; items.clear(); hasMore = false; stopPlayer() }
        error = null
        when (tab) {
            0 -> { items.addAll(saved()); loading = false; render(); return }
            1 -> {
                loading = true; render()
                pool.execute {
                    val list = SoundGen.SFX.mapNotNull { s ->
                        try {
                            val f = SoundGen.ensureSfx(act, s)
                            SoundsPage.Track("sg_" + s.id, s.name, "Get Cut", Uri.fromFile(f).toString(), null, (s.seconds * 1000).toLong(), "cc0", "", "", "wav")
                        } catch (_: Throwable) { null }
                    }
                    ui.post { if (my == gen) { items.addAll(list); loading = false; render() } }
                }
                return
            }
        }
        loading = true; render()
        pool.execute {
            try {
                val (list, count, dbg) = SoundsData.sfxSearch(act, query, page)
                ui.post {
                    if (my == gen) {
                        items.addAll(list); hasMore = count >= 20; loading = false
                        if (list.isEmpty() && items.isEmpty()) error = "No sounds found online. Try another word or open the Built-in tab.\n($dbg)"
                        render()
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    if (my == gen) {
                        loading = false
                        error = "Could not load sounds. Check your internet - or open the Built-in tab."
                        render()
                    }
                }
            }
        }
    }

    private fun mmss(ms: Long): String { val s = (ms + 500) / 1000; return "%02d:%02d".format(s / 60, s % 60) }

    private fun note(s: String) = TextView(act).apply { text = s; setTextColor(0xFF8A8A92.toInt()); textSize = 13f; setPadding(dp(4), dp(16), dp(4), dp(16)) }

    private fun render() {
        val lb = listBox ?: return
        lb.removeAllViews()
        if (loading && items.isEmpty()) { lb.addView(note("Loading...")); return }
        if (error != null && items.isEmpty()) { lb.addView(note(error!!)); return }
        if (items.isEmpty()) { lb.addView(note(if (tab == 0) "Tap the bookmark on a sound to save it here." else "No sounds found. Try another word.")); return }
        items.forEach { lb.addView(row(it)) }
        if (hasMore) lb.addView(TextView(act).apply {
            text = if (loading) "Loading..." else "Load more"; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
            setOnClickListener { if (!loading) { page++; load(false) } }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        })
        if (tab >= 2) lb.addView(note("Free sound effects (Creative Commons). Sounds that need credit are listed in Audio > Copyright."))
    }

    private fun row(t: SoundsPage.Track): View {
        val playing = playingId == t.id
        val r = LinearLayout(act); r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(4), 0, dp(4))
        val ic = ImageView(act); ic.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_music); ic.setColorFilter(if (playing) CYAN else Color.WHITE)
        ic.setPadding(dp(4), dp(4), dp(4), dp(4))
        r.addView(ic, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(12) })
        val name = TextView(act); name.text = t.title.ifBlank { "Sound" }; name.setTextColor(if (playing) CYAN else Color.WHITE); name.textSize = 15f
        name.maxLines = 1; name.ellipsize = android.text.TextUtils.TruncateAt.END
        r.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (t.durMs > 0) r.addView(TextView(act).apply { text = mmss(t.durMs); setTextColor(0xFF8A8A92.toInt()); textSize = 13f }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        val bm = ImageView(act); bm.setImageResource(if (isSaved(t)) R.drawable.ic_bookmark_fill else R.drawable.ic_bookmark); bm.setPadding(dp(8), dp(8), dp(8), dp(8))
        bm.setOnClickListener { toggleSaved(t); if (tab == 0) load(true) else render() }
        r.addView(bm, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(6) })
        val add = FrameLayout(act); add.background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(CYAN) }
        val plus = ImageView(act); plus.setImageResource(R.drawable.ic_add); plus.setColorFilter(Color.BLACK)
        add.addView(plus, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        add.setOnClickListener { addSound(t) }
        r.addView(add, LinearLayout.LayoutParams(dp(54), dp(40)).apply { leftMargin = dp(6) })
        r.setOnClickListener { togglePlay(t) }
        r.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        return r
    }

    private fun togglePlay(t: SoundsPage.Track) {
        if (playingId == t.id) { stopPlayer(); render(); return }
        stopPlayer()
        playingId = t.id
        val my = gen
        try {
            val mp = MediaPlayer(); player = mp
            mp.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).setUsage(AudioAttributes.USAGE_MEDIA).build())
            mp.setDataSource(t.url)
            mp.setOnPreparedListener { if (gen == my && playingId == t.id) mp.start() }
            mp.setOnCompletionListener { if (playingId == t.id) { playingId = null; render() } }
            mp.setOnErrorListener { _, _, _ -> toast("Could not play this sound"); playingId = null; render(); true }
            mp.prepareAsync()
        } catch (_: Exception) { toast("Could not play this sound"); playingId = null }
        render()
    }

    private fun stopPlayer() {
        try { player?.release() } catch (_: Exception) {}
        player = null; playingId = null
    }

    private fun addSound(t: SoundsPage.Track) {
        if (adding) return
        if (t.url.startsWith("file:")) { stopPlayer(); onAdd(t.url, t.durMs, t.title); return }
        adding = true
        toast("Downloading...")
        pool.execute {
            try {
                val dir = File(act.filesDir, "music").apply { mkdirs() }
                val f = File(dir, "fx_" + t.id.filter { it.isLetterOrDigit() }.take(40) + "." + t.ext.ifBlank { "mp3" })
                if (!f.exists() || f.length() < 200) {
                    val tmp = File(dir, f.name + ".tmp")
                    SoundsData.open(t.url).inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    tmp.renameTo(f)
                }
                val info = MediaProbe.probe(act, Uri.fromFile(f))
                val dur = info?.durationMs ?: t.durMs
                if (dur <= 0) throw java.io.IOException("unreadable")
                val uri = Uri.fromFile(f).toString()
                SoundsPage.saveCredit(act, uri, t)
                ui.post { adding = false; onAdd(uri, dur, t.title.ifBlank { "Sound effect" }) }
            } catch (_: Exception) {
                ui.post { adding = false; Toast.makeText(act, "Could not download this sound", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}

package com.gatsmod.cut

import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** CapCut-style start page: new video, quick tools and the list of saved projects. */
class HomeActivity : AppCompatActivity() {

    private val updater by lazy { Updater(this) }
    private lateinit var pages: FrameLayout
    private lateinit var navItems: List<Pair<ImageView, TextView>>
    private var cur = 0
    private val projectBoxes = ArrayList<LinearLayout>()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_GatsModCut)
        super.onCreate(savedInstanceState)
        SoundsData.preload(this)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        window.statusBarColor = 0xFFD9F4FF.toInt()
        window.navigationBarColor = Color.WHITE

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFD9F4FF.toInt(), 0xFFF4FBFF.toInt(), Color.WHITE))

        pages = FrameLayout(this)
        root.addView(pages, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(navBar())
        setContentView(root)
        showPage(0)
        updater.cleanup()
        updater.check(true)
    }

    private fun editPage(): View {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(14), dp(10), dp(14), dp(6))
        top.addView(iconBtn(R.drawable.ic_person) { showPage(4) })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(iconBtn(R.drawable.ic_bell) { updater.check(false) })
        top.addView(iconBtn(R.drawable.ic_gear) { aboutDialog() })
        page.addView(top)

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(14), dp(6), dp(14), dp(24))

        val cards = LinearLayout(this)
        cards.addView(bigCard(R.drawable.ic_add, "New video", 1.5f) { openEditor(null, true) })
        cards.addView(bigCard(R.drawable.ic_photo, "Edit photo", 1f) { startActivity(Intent(this, PhotoActivity::class.java)) })
        body.addView(cards, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(116)))

        val tools = listOf(
            Triple(R.drawable.ic_ai, "AI Lab", "ailab"),
            Triple(R.drawable.ic_music, "Discover music", "discover"),
            Triple(R.drawable.ic_folder, "Projects", "projects"),
            Triple(R.drawable.ic_autocap, "Auto captions", "Auto captions"),
            Triple(R.drawable.ic_removebg, "Remove background", "Remove BG"),
            Triple(R.drawable.ic_camera, "Camera", "camera"),
            Triple(R.drawable.ic_captions, "Teleprompter", "tele"),
            Triple(R.drawable.ic_collage, "All tools", "")
        )
        for (r in 0 until 2) {
            val row = LinearLayout(this)
            row.setPadding(0, dp(14), 0, 0)
            for (i in 0 until 4) {
                val (ic, name, key) = tools[r * 4 + i]
                row.addView(toolCell(ic, name) {
                    when (key) {
                        "" -> openEditor(null, false)
                        "ailab" -> showPage(2)
                        "discover" -> showPage(1)
                        "projects" -> showPage(3)
                        "camera" -> startActivity(Intent(this, MainActivity::class.java).putExtra("camera", true))
                        "tele" -> startActivity(Intent(this, TeleprompterActivity::class.java))
                        else -> openTool(key)
                    }
                })
            }
            body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        body.addView(heading("Projects"))
        val pb = LinearLayout(this)
        pb.orientation = LinearLayout.VERTICAL
        projectBoxes.add(pb)
        body.addView(pb)
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return page
    }

    private fun heading(t: String): View {
        val ph = TextView(this)
        ph.text = t
        ph.textSize = 22f
        ph.setTypeface(null, Typeface.BOLD)
        ph.setTextColor(0xFF111111.toInt())
        ph.setPadding(0, dp(26), 0, dp(10))
        return ph
    }

    private fun sub(t: String): View {
        val v = TextView(this)
        v.text = t
        v.textSize = 13f
        v.setTextColor(0xFF6B717B.toInt())
        v.setPadding(0, 0, 0, dp(8))
        return v
    }

    /** Opens the editor so the person picks a video, then runs the named tool on it. */
    private fun openTool(tool: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("pick", true).putExtra("autoTool", tool))
    }

    // ---------- bottom navigation ----------

    private fun navBar(): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setBackgroundColor(Color.WHITE)
        val line = View(this)
        line.setBackgroundColor(0xFFEDEEF1.toInt())
        wrap.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val bar = LinearLayout(this)
        val items = listOf(Triple(R.drawable.ic_cut, "Edit", 0), Triple(R.drawable.ic_media, "Discover", 1), Triple(R.drawable.ic_ai, "AI Lab", 2), Triple(R.drawable.ic_folder, "Projects", 3), Triple(R.drawable.ic_person, "Me", 4))
        val list = ArrayList<Pair<ImageView, TextView>>()
        for ((ic, name, idx) in items) {
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.setPadding(0, dp(8), 0, dp(8))
            c.setOnClickListener { showPage(idx) }
            val iv = ImageView(this)
            iv.setImageResource(ic)
            c.addView(iv, LinearLayout.LayoutParams(dp(28), dp(28)))
            val t = TextView(this)
            t.text = name
            t.textSize = 12f
            t.gravity = Gravity.CENTER
            t.maxLines = 1
            t.setPadding(0, dp(3), 0, 0)
            c.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            bar.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.add(Pair(iv, t))
        }
        navItems = list
        wrap.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    private fun showPage(i: Int) {
        stopPlayer()
        cur = i
        pages.removeAllViews()
        projectBoxes.clear()
        val v = when (i) {
            0 -> editPage()
            1 -> discoverPage()
            2 -> aiLabPage()
            3 -> projectsPage()
            else -> mePage()
        }
        pages.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        navItems.forEachIndexed { k, (iv, t) ->
            val on = k == i
            iv.setColorFilter(if (on) 0xFF111111.toInt() else 0xFF9AA0A8.toInt())
            t.setTextColor(if (on) 0xFF111111.toInt() else 0xFF9AA0A8.toInt())
            t.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        refreshProjects()
    }

    private fun scrollPage(title: String, subtitle: String, fill: (LinearLayout) -> Unit): View {
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(16), dp(14), dp(16), dp(24))
        val h = heading(title)
        h.setPadding(0, dp(6), 0, dp(4))
        body.addView(h)
        body.addView(sub(subtitle))
        fill(body)
        scroll.addView(body)
        return scroll
    }

    private fun card(title: String, desc: String, icon: Int, btn: String?, click: () -> Unit): View {
        val l = LinearLayout(this)
        l.gravity = Gravity.CENTER_VERTICAL
        l.setPadding(dp(12), dp(12), dp(12), dp(12))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(16).toFloat()
        bg.setColor(Color.WHITE)
        bg.setStroke(dp(1), 0xFFE3E7EC.toInt())
        l.background = bg
        l.setOnClickListener { click() }
        val box = FrameLayout(this)
        val ib = GradientDrawable()
        ib.cornerRadius = dp(12).toFloat()
        ib.setColor(0xFFF1F3F6.toInt())
        box.background = ib
        val iv = ImageView(this)
        iv.setImageResource(icon)
        iv.setColorFilter(0xFF222222.toInt())
        box.addView(iv, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        l.addView(box, LinearLayout.LayoutParams(dp(50), dp(50)))
        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.setPadding(dp(12), 0, dp(8), 0)
        val t = TextView(this)
        t.text = title
        t.textSize = 16f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(0xFF111111.toInt())
        t.maxLines = 1
        info.addView(t)
        val d = TextView(this)
        d.text = desc
        d.textSize = 12f
        d.setTextColor(0xFF6B717B.toInt())
        d.maxLines = 2
        info.addView(d)
        l.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (btn != null) {
            val b = TextView(this)
            b.text = btn
            b.textSize = 13f
            b.setTypeface(null, Typeface.BOLD)
            b.setTextColor(Color.WHITE)
            b.gravity = Gravity.CENTER
            b.setPadding(dp(14), dp(8), dp(14), dp(8))
            val bb = GradientDrawable()
            bb.cornerRadius = dp(18).toFloat()
            bb.setColor(0xFF111111.toInt())
            b.background = bb
            b.setOnClickListener { click() }
            l.addView(b)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(10))
        l.layoutParams = lp
        return l
    }

    // ---------- AI Lab ----------

    private fun aiLabPage(): View {
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(16), dp(14), dp(16), dp(24))
        val h = TextView(this)
        h.text = "AI Lab"; h.textSize = 26f; h.setTypeface(null, Typeface.BOLD); h.setTextColor(0xFF111111.toInt())
        body.addView(h)
        body.addView(sub("Smart tools that run on your phone. Pick a video and the tool opens right away."))
        val items = listOf(
            arrayOf(R.drawable.ic_removebg, "Remove background", "Cut the person out of the video", "Remove BG", 0xFFB5E6FF, 0xFF6FD3F2),
            arrayOf(R.drawable.ic_autocap, "Auto captions", "Speech to subtitles, offline", "Auto captions", 0xFFFFD9B5, 0xFFFF9F6B),
            arrayOf(R.drawable.ic_stabilize, "Stabilize", "Smooth shaky footage", "Stabilize", 0xFFD3F5C8, 0xFF7ED67A),
            arrayOf(R.drawable.ic_speed, "Beat sync", "Find the beats of the music", "Beats", 0xFFE4D4FF, 0xFFA98BFF),
            arrayOf(R.drawable.ic_noise, "Noise reduction", "Clean noise from the voice", "Denoise", 0xFFFFD0E0, 0xFFFF7FA8),
            arrayOf(R.drawable.ic_chroma, "Chroma key", "Remove a green screen", "Chroma key", 0xFFC9F2EE, 0xFF5FD4C8),
            arrayOf(R.drawable.ic_mask, "Mask", "Reveal part with a shape", "Mask", 0xFFFFF0B5, 0xFFFFC94D),
            arrayOf(R.drawable.ic_tts, "Text to audio", "Turn text into a voice", "Text to audio", 0xFFD6E0FF, 0xFF7B96FF)
        )
        var i = 0
        while (i < items.size) {
            val row = LinearLayout(this)
            for (k in 0..1) {
                val it = items[i + k]
                val c = LinearLayout(this)
                c.orientation = LinearLayout.VERTICAL
                c.gravity = Gravity.BOTTOM
                c.setPadding(dp(14), dp(14), dp(14), dp(14))
                val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf((it[4] as Long).toInt(), (it[5] as Long).toInt()))
                g.cornerRadius = dp(20).toFloat()
                c.background = g
                c.setOnClickListener { _ -> openTool(it[3] as String) }
                val bx = FrameLayout(this)
                val bg = GradientDrawable(); bg.cornerRadius = dp(14).toFloat(); bg.setColor(0x66FFFFFF)
                bx.background = bg
                val iv = ImageView(this)
                iv.setImageResource(it[0] as Int); iv.setColorFilter(0xFF111111.toInt())
                bx.addView(iv, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                c.addView(bx, LinearLayout.LayoutParams(dp(52), dp(52)))
                c.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
                val t = TextView(this)
                t.text = it[1] as String; t.textSize = 16f; t.setTypeface(null, Typeface.BOLD); t.setTextColor(0xFF111111.toInt())
                c.addView(t)
                val d = TextView(this)
                d.text = it[2] as String; d.textSize = 12f; d.setTextColor(0xFF2B3036.toInt()); d.setPadding(0, dp(2), 0, 0)
                c.addView(d)
                val lp = LinearLayout.LayoutParams(0, dp(168), 1f)
                lp.setMargins(if (k == 0) 0 else dp(6), 0, if (k == 0) dp(6) else 0, dp(12))
                row.addView(c, lp)
            }
            body.addView(row)
            i += 2
        }
        scroll.addView(body)
        return scroll
    }

    // ---------- Projects ----------

    private fun projectsPage(): View {
        val frame = FrameLayout(this)
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(16), dp(14), dp(8), dp(4))
        val h = TextView(this)
        h.text = "Projects"; h.textSize = 26f; h.setTypeface(null, Typeface.BOLD); h.setTextColor(0xFF111111.toInt())
        top.addView(h, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(iconBtn(R.drawable.ic_bell) { updater.check(false) })
        page.addView(top)
        val tab = TextView(this)
        tab.text = "Local"; tab.textSize = 16f; tab.setTextColor(0xFF111111.toInt()); tab.setPadding(dp(4), dp(10), dp(4), dp(8))
        val tabRow = LinearLayout(this)
        tabRow.orientation = LinearLayout.VERTICAL
        tabRow.setPadding(dp(16), 0, 0, 0)
        val tw = LinearLayout(this); tw.orientation = LinearLayout.VERTICAL
        tw.addView(tab)
        val ul = View(this); ul.setBackgroundColor(0xFF111111.toInt())
        tw.addView(ul, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        tabRow.addView(tw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        page.addView(tabRow)
        val dv = View(this); dv.setBackgroundColor(0xFFEDEEF1.toInt())
        page.addView(dv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val n = ProjectStore.list(this).size
        val cnt = TextView(this)
        cnt.text = if (n == 1) "1 project" else "$n projects"
        cnt.textSize = 17f; cnt.setTypeface(null, Typeface.BOLD); cnt.setTextColor(0xFF111111.toInt())
        cnt.setPadding(dp(16), dp(14), dp(16), dp(6))
        page.addView(cnt)
        val sc = ScrollView(this)
        sc.isVerticalScrollBarEnabled = false
        val pb = LinearLayout(this)
        pb.orientation = LinearLayout.VERTICAL
        pb.setPadding(dp(16), 0, dp(8), dp(90))
        projectBoxes.add(pb)
        sc.addView(pb)
        page.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val fab = LinearLayout(this)
        fab.gravity = Gravity.CENTER
        fab.setPadding(dp(22), dp(14), dp(22), dp(14))
        val fg = GradientDrawable(); fg.cornerRadius = dp(28).toFloat(); fg.setColor(0xFF111111.toInt())
        fab.background = fg
        fab.elevation = dp(6).toFloat()
        val fi = ImageView(this); fi.setImageResource(R.drawable.ic_add)
        fab.addView(fi, LinearLayout.LayoutParams(dp(22), dp(22)))
        val ft = TextView(this); ft.text = "Create"; ft.textSize = 17f; ft.setTypeface(null, Typeface.BOLD); ft.setTextColor(Color.WHITE); ft.setPadding(dp(8), 0, 0, 0)
        fab.addView(ft)
        fab.setOnClickListener { openEditor(null, true) }
        val flp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        flp.setMargins(0, 0, dp(18), dp(22))
        frame.addView(fab, flp)
        return frame
    }

    // ---------- Me ----------

    private fun meRow(icon: Int, text: String, value: String? = null, click: () -> Unit): View {
        val l = LinearLayout(this)
        l.gravity = Gravity.CENTER_VERTICAL
        l.setPadding(dp(16), dp(16), dp(16), dp(16))
        l.setOnClickListener { click() }
        val iv = ImageView(this)
        iv.setImageResource(icon); iv.setColorFilter(0xFF111111.toInt())
        l.addView(iv, LinearLayout.LayoutParams(dp(26), dp(26)))
        val t = TextView(this)
        t.text = text; t.textSize = 17f; t.setTextColor(0xFF111111.toInt()); t.setPadding(dp(16), 0, dp(8), 0)
        l.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) {
            val v = TextView(this)
            v.text = value; v.textSize = 14f; v.setTextColor(0xFF8A8F98.toInt())
            l.addView(v)
        }
        val ch = TextView(this)
        ch.text = "›"; ch.textSize = 24f; ch.setTextColor(0xFF9AA0A8.toInt()); ch.setPadding(dp(8), 0, 0, 0)
        l.addView(ch)
        return l
    }

    private fun mePage(): View {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(16), dp(24), dp(16), dp(20))
        val av = FrameLayout(this)
        val ag = GradientDrawable(); ag.shape = GradientDrawable.OVAL; ag.setColor(0xFFDDE5EC.toInt())
        av.background = ag
        val ai = ImageView(this); ai.setImageResource(R.drawable.ic_person); ai.setColorFilter(Color.WHITE)
        av.addView(ai, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER))
        head.addView(av, LinearLayout.LayoutParams(dp(92), dp(92)))
        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.setPadding(dp(16), 0, 0, 0)
        val nm = TextView(this); nm.text = "Get Cut"; nm.textSize = 22f; nm.setTypeface(null, Typeface.BOLD); nm.setTextColor(0xFF111111.toInt())
        val vs = TextView(this); vs.text = "Version $v  ·  no account needed"; vs.textSize = 14f; vs.setTextColor(0xFF6B717B.toInt())
        info.addView(nm); info.addView(vs)
        head.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(iconBtn(R.drawable.ic_gear) { aboutDialog() })
        body.addView(head)
        val used = dirSize(filesDir)
        body.addView(meRow(R.drawable.ic_bell, "Check for updates") { updater.check(false) })
        body.addView(meRow(R.drawable.ic_color, "Theme colour") { lightSheet("Theme", EditorTheme.picker(this) {}) })
        body.addView(meRow(R.drawable.ic_captions, "Ending card on new videos", if (Branding.endingOn(this)) "ON" else "OFF") { Branding.setEndingOn(this, !Branding.endingOn(this)); showPage(4) })
        body.addView(meRow(R.drawable.ic_music, "Preload music in background", if (SoundsData.preloadOn(this)) "ON" else "OFF") { SoundsData.setPreload(this, !SoundsData.preloadOn(this)); showPage(4) })
        body.addView(meRow(R.drawable.ic_media, "Jamendo Client ID", if (SoundsData.jamendoId(this).isBlank()) "Not set" else "Set") { jamendoDialog() })
        val dv = View(this); dv.setBackgroundColor(0xFFEDEEF1.toInt())
        body.addView(dv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).also { it.setMargins(dp(16), dp(6), dp(16), dp(6)) })
        body.addView(meRow(R.drawable.ic_delete, "Storage used", sizeText(used)) {
            SoundsData.clearCache(this); toast("Online music list cache cleared (music in your projects is kept)"); showPage(4)
        })
        body.addView(meRow(R.drawable.ic_share, "Share Get Cut") {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Get Cut - free video editor: https://github.com/meaowsahat-collab/GatsMod-Cut-Android-Starter/releases/latest")
            startActivity(Intent.createChooser(i, "Share Get Cut"))
        })
        body.addView(meRow(R.drawable.ic_help, "Help Center") {
            android.app.AlertDialog.Builder(this).setTitle("Help Center").setMessage("• New video: pick a video, then use the toolbar at the bottom.\n• Select a clip to see its tools (Split, Speed, Remove BG...).\n• Audio > Sounds: free music (Creative Commons). Audio > Copyright shows the credits to put in your video description.\n• AI Lab: pick a video and the tool opens by itself.\n• Updates: Me > Check for updates.").setPositiveButton("OK", null).show()
        })
        scroll.addView(body)
        return scroll
    }

    private fun dirSize(f: java.io.File): Long = if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0L)

    private fun jamendoDialog() {
        val prefs = getSharedPreferences("getcut_sounds", 0)
        val input = EditText(this)
        input.hint = "Jamendo Client ID"
        input.setText(SoundsData.jamendoId(this))
        input.isSingleLine = true
        input.setTextColor(0xFF111111.toInt())
        android.app.AlertDialog.Builder(this).setTitle("Jamendo Client ID").setMessage("Free at devportal.jamendo.com. Leave empty to use Openverse.").setView(input)
            .setPositiveButton("Save") { _, _ -> prefs.edit().putString("jamendo_id", input.text.toString().trim()).apply(); toast("Saved") }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- Discover ----------

    private var player: android.media.MediaPlayer? = null
    private var playingId: String? = null

    private fun stopPlayer() {
        try { player?.release() } catch (_: Exception) {}
        player = null; playingId = null
        badge?.let { it.text = it.text.toString().replace("❚❚  ", "▶  ") }
        badge = null
    }

    private fun discoverPage(): View {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(dp(16), dp(14), dp(16), 0)
        val sbar = LinearLayout(this)
        sbar.gravity = Gravity.CENTER_VERTICAL
        val sg = GradientDrawable(); sg.cornerRadius = dp(14).toFloat(); sg.setColor(0xFFF1F3F6.toInt())
        sbar.background = sg
        val input = EditText(this)
        input.hint = "Search music"; input.isSingleLine = true; input.setBackgroundColor(Color.TRANSPARENT)
        input.setTextColor(0xFF111111.toInt()); input.setHintTextColor(0xFF9AA0A8.toInt()); input.setPadding(dp(14), 0, dp(8), 0)
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        sbar.addView(input, LinearLayout.LayoutParams(0, dp(46), 1f))
        val go = ImageView(this); go.setImageResource(R.drawable.ic_search); go.setColorFilter(0xFF111111.toInt()); go.scaleType = ImageView.ScaleType.CENTER
        sbar.addView(go, LinearLayout.LayoutParams(dp(46), dp(46)))
        page.addView(sbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val chips = HorizontalScrollView(this)
        chips.isHorizontalScrollBarEnabled = false
        val cr = LinearLayout(this)
        chips.addView(cr)
        page.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.setMargins(0, dp(12), 0, dp(12)) })
        val sc = ScrollView(this)
        sc.isVerticalScrollBarEnabled = false
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, 0, 0, dp(20))
        sc.addView(list)
        page.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        var gen = 0
        val chipViews = ArrayList<TextView>()
        fun run(query: String, ci: Int) {
            val my = ++gen
            stopPlayer()
            list.removeAllViews()
            val msg = TextView(this); msg.text = "Loading…"; msg.setTextColor(0xFF8A8F98.toInt()); msg.setPadding(0, dp(20), 0, 0); list.addView(msg)
            val jid = SoundsData.jamendoId(this)
            val url = SoundsData.url(jid, query, if (query.isBlank() || ci < 0) 0 else 1, true, 1)
            Thread {
                try {
                    val tracks = SoundsData.parse(SoundsData.cachedText(this, url), jid.isNotBlank(), true).first
                    runOnUiThread { if (my == gen && cur == 1) fillTracks(list, tracks) }
                } catch (e: Exception) {
                    runOnUiThread { if (my == gen && cur == 1) msg.text = "Could not load music. Check your internet connection." }
                }
            }.start()
        }
        fun pick(ci: Int) {
            chipViews.forEachIndexed { k, t ->
                val g = GradientDrawable(); g.cornerRadius = dp(18).toFloat()
                g.setColor(Color.WHITE); g.setStroke(dp(if (k == ci) 2 else 1), if (k == ci) 0xFF111111.toInt() else 0xFFD5D9DF.toInt())
                t.background = g; t.setTypeface(null, if (k == ci) Typeface.BOLD else Typeface.NORMAL)
            }
            run(if (ci == 0) "" else SoundsData.CATS[ci], ci)
        }
        SoundsData.CATS.forEachIndexed { k, name ->
            val t = TextView(this)
            t.text = name; t.textSize = 15f; t.setTextColor(0xFF111111.toInt()); t.setPadding(dp(16), dp(8), dp(16), dp(8))
            t.setOnClickListener { input.setText(""); pick(k) }
            cr.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.setMargins(0, 0, dp(8), 0) })
            chipViews.add(t)
        }
        val doSearch = {
            val q = input.text.toString().trim()
            if (q.isNotEmpty()) {
                chipViews.forEach { t -> val g = GradientDrawable(); g.cornerRadius = dp(18).toFloat(); g.setColor(Color.WHITE); g.setStroke(dp(1), 0xFFD5D9DF.toInt()); t.background = g }
                run(q, -1)
            }
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        }
        go.setOnClickListener { doSearch() }
        input.setOnEditorActionListener { _, _, _ -> doSearch(); true }
        pick(0)
        return page
    }

    private var badge: TextView? = null

    private fun fillTracks(list: LinearLayout, tracks: List<SoundsPage.Track>) {
        list.removeAllViews()
        if (tracks.isEmpty()) { val m = TextView(this); m.text = "No music found."; m.setTextColor(0xFF8A8F98.toInt()); m.setPadding(0, dp(20), 0, 0); list.addView(m); return }
        val w = (resources.displayMetrics.widthPixels - dp(32) - dp(12)) / 2
        val grads = listOf(0xFF6FD3F2, 0xFFFF9F6B, 0xFF7ED67A, 0xFFA98BFF, 0xFFFF7FA8, 0xFF5FD4C8, 0xFFFFC94D, 0xFF7B96FF)
        var i = 0
        while (i < tracks.size) {
            val row = LinearLayout(this)
            for (k in 0..1) {
                val t = tracks.getOrNull(i + k)
                if (t == null) { row.addView(View(this), LinearLayout.LayoutParams(w, 1)); continue }
                val cell = LinearLayout(this)
                cell.orientation = LinearLayout.VERTICAL
                val cover = FrameLayout(this)
                val gc = grads[Math.abs(t.id.hashCode()) % grads.size].toInt()
                val cg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(gc, 0xFF1B1F27.toInt()))
                cg.cornerRadius = dp(16).toFloat()
                cover.background = cg
                cover.clipToOutline = true
                val ci = ImageView(this)
                ci.scaleType = ImageView.ScaleType.CENTER_CROP
                val ph = ImageView(this); ph.setImageResource(R.drawable.ic_music); ph.setColorFilter(0x88FFFFFF.toInt())
                cover.addView(ph, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER))
                cover.addView(ci, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                t.thumb?.let { u -> Thread { val b = SoundsData.cover(this, u); if (b != null) runOnUiThread { ci.setImageBitmap(b) } }.start() }
                val sec = t.durMs / 1000
                val pl = TextView(this)
                pl.text = "▶  " + (if (sec > 0) "%d:%02d".format(sec / 60, sec % 60) else "")
                pl.textSize = 13f; pl.setTextColor(Color.WHITE); pl.setTypeface(null, Typeface.BOLD)
                pl.setPadding(dp(8), dp(4), dp(8), dp(4))
                val pg = GradientDrawable(); pg.cornerRadius = dp(10).toFloat(); pg.setColor(0x99000000.toInt()); pl.background = pg
                cover.addView(pl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).also { it.setMargins(dp(8), 0, 0, dp(8)) })
                val use = TextView(this)
                use.text = "Use"; use.textSize = 13f; use.setTypeface(null, Typeface.BOLD); use.setTextColor(0xFF111111.toInt())
                use.setPadding(dp(14), dp(6), dp(14), dp(6))
                val ug = GradientDrawable(); ug.cornerRadius = dp(14).toFloat(); ug.setColor(Color.WHITE); use.background = ug
                use.setOnClickListener { useTrack(t) }
                cover.addView(use, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).also { it.setMargins(0, 0, dp(8), dp(8)) })
                cover.setOnClickListener { playTrack(t, pl) }
                cell.addView(cover, LinearLayout.LayoutParams(w, (w * 1.15f).toInt()))
                val title = TextView(this)
                title.text = t.title.ifBlank { "Music" }; title.textSize = 15f; title.setTextColor(0xFF111111.toInt()); title.maxLines = 1; title.ellipsize = android.text.TextUtils.TruncateAt.END
                title.setPadding(0, dp(8), 0, 0)
                cell.addView(title)
                val by = TextView(this)
                by.text = t.creator.ifBlank { "Unknown" } + " · " + t.licenseName; by.textSize = 12f; by.setTextColor(0xFF8A8F98.toInt()); by.maxLines = 1; by.ellipsize = android.text.TextUtils.TruncateAt.END
                cell.addView(by)
                row.addView(cell, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.setMargins(if (k == 1) dp(12) else 0, 0, 0, dp(16)) })
            }
            list.addView(row)
            i += 2
        }
    }

    private fun playTrack(t: SoundsPage.Track, pl: TextView) {
        val was = playingId == t.id
        stopPlayer()
        if (was) return
        playingId = t.id
        badge = pl
        val base = pl.text.toString().removePrefix("▶  ")
        pl.text = "❚❚  $base"
        try {
            val mp = android.media.MediaPlayer()
            mp.setAudioAttributes(android.media.AudioAttributes.Builder().setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            mp.setDataSource(t.url)
            mp.setOnPreparedListener { if (playingId == t.id) it.start() }
            mp.setOnErrorListener { _, _, _ -> toast("Could not play this track"); stopPlayer(); true }
            mp.setOnCompletionListener { stopPlayer() }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) { toast("Could not play this track"); stopPlayer() }
    }

    private fun useTrack(t: SoundsPage.Track) {
        stopPlayer()
        toast("Downloading…")
        Thread {
            try {
                val dir = java.io.File(filesDir, "music").apply { mkdirs() }
                val f = java.io.File(dir, "dl_" + t.id.filter { it.isLetterOrDigit() }.take(40) + "." + t.ext.ifBlank { "mp3" })
                if (!f.exists() || f.length() < 1000) {
                    val tmp = java.io.File(dir, f.name + ".tmp")
                    SoundsData.open(t.url).inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    tmp.renameTo(f)
                }
                val info = MediaProbe.probe(this, android.net.Uri.fromFile(f))
                val dur = info?.durationMs ?: t.durMs
                if (dur <= 0) throw java.io.IOException("unreadable")
                val uri = android.net.Uri.fromFile(f).toString()
                SoundsPage.saveCredit(this, uri, t)
                runOnUiThread {
                    startActivity(Intent(this, MainActivity::class.java).putExtra("pick", true)
                        .putExtra("audioUri", uri).putExtra("audioDur", dur).putExtra("audioName", t.title.ifBlank { "Music" }))
                }
            } catch (_: Exception) { runOnUiThread { toast("Could not download this track") } }
        }.start()
    }

    override fun onPause() { super.onPause(); stopPlayer() }

    override fun onResume() {
        super.onResume()
        updater.resumePendingInstall()
        refreshProjects()
    }

    private fun openEditor(id: String?, pick: Boolean) {
        val i = Intent(this, MainActivity::class.java)
        if (id != null) i.putExtra("id", id)
        i.putExtra("pick", pick)
        startActivity(i)
    }

    // ---------- pieces ----------

    private fun iconBtn(icon: Int, click: () -> Unit): View {
        val v = ImageView(this)
        v.setImageResource(icon)
        v.setColorFilter(0xFF111111.toInt())
        v.scaleType = ImageView.ScaleType.CENTER
        v.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        v.setOnClickListener { click() }
        return v
    }

    private fun bigCard(icon: Int, label: String, weight: Float, click: () -> Unit): View {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.gravity = Gravity.CENTER
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFB5E6FF.toInt(), 0xFF6FD3F2.toInt()))
        g.cornerRadius = dp(22).toFloat()
        l.background = g
        l.isClickable = true
        l.isFocusable = true
        l.setOnClickListener { click() }
        val box = FrameLayout(this)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(12).toFloat()
        bg.setColor(0xFF111111.toInt())
        box.background = bg
        val iv = ImageView(this)
        iv.setImageResource(icon)
        box.addView(iv, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        l.addView(box, LinearLayout.LayoutParams(dp(44), dp(44)))
        val t = TextView(this)
        t.text = label
        t.gravity = Gravity.CENTER
        t.textSize = 17f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(0xFF111111.toInt())
        t.setPadding(0, dp(8), 0, 0)
        l.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
        lp.setMargins(if (weight > 1f) 0 else dp(10), 0, 0, 0)
        l.layoutParams = lp
        return l
    }

    private fun toolCell(icon: Int, label: String, click: () -> Unit): View {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.gravity = Gravity.CENTER_HORIZONTAL
        l.setOnClickListener { click() }
        val box = FrameLayout(this)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(18).toFloat()
        bg.setColor(0xFFF1F3F6.toInt())
        box.background = bg
        val iv = ImageView(this)
        iv.setImageResource(icon)
        iv.setColorFilter(0xFF222222.toInt())
        box.addView(iv, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        l.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))
        val t = TextView(this)
        t.text = label
        t.textSize = 11f
        t.gravity = Gravity.CENTER
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(0xFF111111.toInt())
        t.setPadding(0, dp(6), 0, 0)
        l.addView(t)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(5), 0, dp(5), 0)
        l.layoutParams = lp
        return l
    }

    // ---------- projects ----------

    private fun refreshProjects() {
        projectBoxes.forEach { fillProjects(it) }
    }

    private fun fillProjects(projectsBox: LinearLayout) {
        projectsBox.removeAllViews()
        val list = ProjectStore.list(this)
        if (list.isEmpty()) {
            val t = TextView(this)
            t.text = "No projects yet. Tap New video to start."
            t.setTextColor(0xFF8A8F98.toInt())
            t.textSize = 14f
            projectsBox.addView(t)
            return
        }
        val df = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
        list.forEach { m ->
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(8), 0, dp(8))
            row.setOnClickListener { openEditor(m.id, false) }
            val th = ImageView(this)
            th.scaleType = ImageView.ScaleType.CENTER_CROP
            val tf = ProjectStore.thumb(this, m.id)
            if (tf.exists()) BitmapFactory.decodeFile(tf.absolutePath)?.let { th.setImageBitmap(it) } else th.setBackgroundColor(0xFFDDE3EA.toInt())
            th.clipToOutline = true
            val tbg = GradientDrawable()
            tbg.cornerRadius = dp(10).toFloat()
            tbg.setColor(0xFFDDE3EA.toInt())
            th.background = tbg
            row.addView(th, LinearLayout.LayoutParams(dp(92), dp(92)))
            val info = LinearLayout(this)
            info.orientation = LinearLayout.VERTICAL
            info.setPadding(dp(14), 0, 0, 0)
            val name = TextView(this)
            name.text = m.name
            name.textSize = 17f
            name.setTypeface(null, Typeface.BOLD)
            name.setTextColor(0xFF111111.toInt())
            name.maxLines = 1
            val date = TextView(this)
            date.text = df.format(Date(m.updated))
            date.textSize = 13f
            date.setTextColor(0xFF8A8F98.toInt())
            date.setPadding(0, dp(4), 0, 0)
            val meta = TextView(this)
            val sec = m.durMs / 1000
            meta.text = "✂ ${sizeText(m.size)}  |  %02d:%02d".format(sec / 60, sec % 60)
            meta.textSize = 13f
            meta.setTextColor(0xFF8A8F98.toInt())
            meta.setPadding(0, dp(4), 0, 0)
            info.addView(name); info.addView(date); info.addView(meta)
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val more = TextView(this)
            more.text = "⋮"
            more.textSize = 24f
            more.gravity = Gravity.CENTER
            more.setTextColor(0xFF666B75.toInt())
            more.setOnClickListener { projectMenu(m) }
            row.addView(more, LinearLayout.LayoutParams(dp(44), dp(60)))
            projectsBox.addView(row)
        }
    }

    private fun sizeText(b: Long): String = when {
        b >= 1_000_000_000L -> "%.1fGB".format(b / 1e9)
        b >= 1_000_000L -> "%dMB".format(b / 1_000_000L)
        else -> "%dKB".format(b / 1000L)
    }

    private fun lightSheet(title: String, vararg views: View): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.cornerRadii = floatArrayOf(dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), 0f, 0f, 0f, 0f)
        root.background = bg
        root.setPadding(dp(18), dp(16), dp(18), dp(18))
        val t = TextView(this)
        t.text = title
        t.textSize = 16f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(0xFF111111.toInt())
        t.setPadding(0, 0, 0, dp(8))
        root.addView(t)
        views.forEach { root.addView(it) }
        d.setContentView(root)
        d.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
        }
        d.show()
        return d
    }

    private fun row(text: String, danger: Boolean = false, click: () -> Unit): View {
        val t = TextView(this)
        t.text = text
        t.textSize = 16f
        t.setTextColor(if (danger) 0xFFE53935.toInt() else 0xFF111111.toInt())
        t.setPadding(0, dp(13), 0, dp(13))
        t.setOnClickListener { click() }
        return t
    }

    private fun projectMenu(m: ProjectStore.Meta) {
        var d: Dialog? = null
        d = lightSheet(m.name,
            row("Rename") { d?.dismiss(); renameDialog(m) },
            row("Duplicate") { d?.dismiss(); ProjectStore.duplicate(this, m.id); refreshProjects() },
            row("Delete", true) { d?.dismiss(); ProjectStore.delete(this, m.id); refreshProjects() })
    }

    private fun renameDialog(m: ProjectStore.Meta) {
        val e = EditText(this)
        e.setText(m.name)
        e.setSelection(e.text.length)
        e.setTextColor(0xFF111111.toInt())
        var d: Dialog? = null
        d = lightSheet("Rename project", e, row("Save") {
            val n = e.text.toString().trim()
            if (n.isNotEmpty()) ProjectStore.rename(this, m.id, n)
            d?.dismiss()
            refreshProjects()
        })
    }

    private fun aboutDialog() {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        lightSheet("Get Cut  v$v", row("Check for updates") { updater.check(false) }, row("Ending card on new videos: " + (if (Branding.endingOn(this)) "ON" else "OFF") + "  (tap to change)") { Branding.setEndingOn(this, !Branding.endingOn(this)); toast("Ending card is now " + (if (Branding.endingOn(this)) "ON" else "OFF")) }, row("Preload music in background: " + (if (SoundsData.preloadOn(this)) "ON" else "OFF") + "  (tap to change)") { SoundsData.setPreload(this, !SoundsData.preloadOn(this)); toast("Background preload is now " + (if (SoundsData.preloadOn(this)) "ON" else "OFF")) }, EditorTheme.picker(this) {})
    }
}

package com.gatsmod.cut

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import java.io.File
import kotlin.math.max
import kotlin.math.min

@UnstableApi
class MainActivity : AppCompatActivity() {

    private val project = Project()
    private lateinit var engine: PreviewEngine
    private val exporter by lazy { Exporter(this) }
    private val updater by lazy { Updater(this) }
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var timeline: TimelineView
    private lateinit var overlayView: OverlayView
    private lateinit var playButton: ImageButton
    private lateinit var timeLabel: TextView
    private lateinit var emptyPreview: View
    private lateinit var muteIcon: ImageView
    private lateinit var muteLabel: TextView
    private lateinit var resRows: List<Pair<Int, TextView>>
    private lateinit var fpsLabel: TextView

    private var resShort = 1080
    private var fps = 30
    private var fullscreen = false

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { persist(it); loadVideo(it) } }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { persist(it); loadAudio(it) } }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { persist(it); loadImage(it) } }
    private val srtPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { persist(it); readSrt(it) } }

    private fun persist(u: Uri) {
        try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun soon(name: String) = toast("$name is coming soon")

    // ============================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        engine = PreviewEngine(this, project)

        findViewById<PlayerView>(R.id.playerView).player = engine.player
        timeline = findViewById(R.id.timeline)
        overlayView = findViewById(R.id.overlayView)
        playButton = findViewById(R.id.playButton)
        timeLabel = findViewById(R.id.timeLabel)
        emptyPreview = findViewById(R.id.emptyPreview)
        muteIcon = findViewById(R.id.muteIcon)
        muteLabel = findViewById(R.id.muteLabel)

        timeline.project = project
        overlayView.project = project
        overlayView.onDragStart = { project.checkpoint() }
        overlayView.onDragEnd = { timeline.invalidate() }

        engine.listener = object : PreviewEngine.Listener {
            override fun onPlayingChanged(playing: Boolean) {
                runOnUiThread { playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play) }
            }
        }

        timeline.onScrub = { ms ->
            engine.pause()
            engine.seekTo(ms)
            syncPlayhead()
        }
        timeline.onSelect = { c ->
            project.selectedId = c?.id ?: -1L
            syncUi()
        }
        timeline.onEdited = { afterEdit() }

        findViewById<View>(R.id.emptyPreview).setOnClickListener { pickVideo() }
        findViewById<View>(R.id.addClipButton).setOnClickListener { pickVideo() }
        findViewById<View>(R.id.undoButton).setOnClickListener { if (project.undo()) { engine.pause(); afterEdit() } else toast("Nothing to undo") }
        findViewById<View>(R.id.redoButton).setOnClickListener { if (project.redo()) { engine.pause(); afterEdit() } else toast("Nothing to redo") }
        playButton.setOnClickListener {
            if (project.isEmpty()) { toast("Import a video first"); return@setOnClickListener }
            if (engine.isPlaying) engine.pause() else engine.play()
        }
        findViewById<View>(R.id.fullscreenButton).setOnClickListener { toggleFullscreen() }
        findViewById<View>(R.id.muteBox).setOnClickListener { toggleMute() }
        findViewById<View>(R.id.menuButton).setOnClickListener { projectDialog() }
        findViewById<View>(R.id.settingsButton).setOnClickListener { aboutDialog() }
        val chip = findViewById<TextView>(R.id.versionChip)
        chip.text = "v${versionName()} · Update"
        chip.setOnClickListener { updater.check(false) }

        buildRail()
        buildRightColumn()
        buildTools()
        buildBottomNav()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) toggleFullscreen() else finish()
            }
        })

        updater.cleanup()
        updater.check(true)
        syncUi()
        tickLoop()
    }

    private val tick = object : Runnable {
        override fun run() {
            engine.tick()
            syncPlayhead()
            ui.postDelayed(this, 80)
        }
    }

    private fun tickLoop() { ui.post(tick) }

    override fun onResume() { super.onResume(); updater.resumePendingInstall() }

    override fun onPause() { super.onPause(); engine.pause() }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        exporter.cancel()
        engine.release()
        Thumbs.clear()
        super.onDestroy()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) { "1.0" }

    // ============================================================ sync

    private fun fmt(ms: Long): String { val s = ms / 1000; return "%02d:%02d".format(s / 60, s % 60) }

    private fun syncPlayhead() {
        val ph = engine.playheadMs
        timeline.setPlayhead(ph)
        overlayView.playheadMs = ph
        overlayView.invalidate()
        timeLabel.text = "${fmt(ph)} / ${fmt(project.totalMs())}"
    }

    private fun syncUi() {
        emptyPreview.visibility = if (project.isEmpty()) View.VISIBLE else View.GONE
        overlayView.canvasAspect = project.effectiveAspect()
        val c = project.selected() ?: project.mainClipAt(engine.playheadMs)
        val muted = c != null && c.volume <= 0.001f
        muteIcon.setImageResource(if (muted) R.drawable.ic_mute else R.drawable.ic_volume)
        muteLabel.text = if (muted) "Unmute" else "Mute"
        syncPlayhead()
        timeline.invalidate()
        overlayView.invalidate()
    }

    private fun afterEdit() {
        project.clampToTimeline()
        engine.refresh()
        syncUi()
    }

    private fun need(video: Boolean = false): Clip? {
        engine.pause()
        var c = project.selected()
        if (c == null) {
            c = project.mainClipAt(engine.playheadMs)
            if (c != null) project.selectedId = c.id
        }
        if (c == null) { toast("Import a video first"); return null }
        if (video && c.kind != LaneKind.VIDEO) { toast("Select a video clip first"); return null }
        return c
    }

    private fun result(err: String?) {
        if (err != null) toast(err)
        afterEdit()
    }

    // ============================================================ importing

    private fun pickVideo() = videoPicker.launch(arrayOf("video/*"))

    private fun loadVideo(u: Uri) {
        Thread {
            val info = MediaProbe.probe(this, u)
            val name = MediaProbe.displayName(this, u)
            runOnUiThread {
                if (info == null || !info.hasVideo || info.durationMs <= 0) { toast("Could not read this video"); return@runOnUiThread }
                engine.pause()
                val first = project.isEmpty()
                val c = Clip(
                    id = project.newId(), kind = LaneKind.VIDEO, uri = u.toString(),
                    srcInMs = 0, srcOutMs = info.durationMs, srcTotalMs = info.durationMs, label = name,
                    width = info.displayW, height = info.displayH, hasAudio = info.hasAudio, fps = info.fps
                )
                project.addMain(c)
                engine.refresh()
                if (first) engine.seekTo(0)
                syncUi()
            }
        }.start()
    }

    private fun loadAudio(u: Uri) {
        if (project.isEmpty()) { toast("Import a video first"); return }
        Thread {
            val info = MediaProbe.probe(this, u)
            val name = MediaProbe.displayName(this, u)
            runOnUiThread {
                if (info == null || info.durationMs <= 0) { toast("Could not read this audio"); return@runOnUiThread }
                val start = min(engine.playheadMs, max(0L, project.totalMs() - 100))
                project.addFree(
                    Clip(
                        id = project.newId(), kind = LaneKind.AUDIO, startMs = start, uri = u.toString(),
                        srcInMs = 0, srcOutMs = info.durationMs, srcTotalMs = info.durationMs, label = name
                    )
                )
                afterEdit()
            }
        }.start()
    }

    private fun loadImage(u: Uri) {
        if (project.isEmpty()) { toast("Import a video first"); return }
        val start = min(engine.playheadMs, max(0L, project.totalMs() - 100))
        project.addFree(
            Clip(
                id = project.newId(), kind = LaneKind.OVERLAY, startMs = start, uri = u.toString(),
                label = MediaProbe.displayName(this, u), durMs = 3000, sizeFrac = 0.3f, posX = 0.5f, posY = 0.5f
            )
        )
        afterEdit()
    }

    // ============================================================ UI building blocks

    private fun tile(icon: Int, label: String, accent: Boolean = false, onClick: () -> Unit): View {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.gravity = Gravity.CENTER
        l.setBackgroundResource(if (accent) R.drawable.bg_tile_accent else R.drawable.bg_tile)
        l.isClickable = true
        l.isFocusable = true
        l.layoutParams = LinearLayout.LayoutParams(dp(66), dp(62)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        l.setOnClickListener { onClick() }
        val iv = ImageView(this)
        iv.setImageResource(icon)
        iv.layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        l.addView(iv)
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(0xFFDAD7EA.toInt())
        tv.textSize = 10f
        tv.gravity = Gravity.CENTER
        tv.maxLines = 1
        tv.setPadding(0, dp(3), 0, 0)
        l.addView(tv)
        return l
    }

    private fun section(title: String, tiles: List<View>) {
        val box = findViewById<LinearLayout>(R.id.toolsContainer)
        val t = TextView(this)
        t.text = title
        t.setTextColor(0xFFFFFFFF.toInt())
        t.textSize = 13f
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(dp(8), dp(8), 0, dp(2))
        box.addView(t)
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        tiles.forEach { row.addView(it) }
        hs.addView(row)
        box.addView(hs)
    }

    private fun slider(name: String, min: Float, max: Float, value: Float, fmt: (Float) -> String, onChange: (Float) -> Unit): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(4), 0, dp(4))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        val n = TextView(this)
        n.text = name
        n.setTextColor(0xFFC9C6DA.toInt())
        n.textSize = 12f
        n.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val v = TextView(this)
        v.text = fmt(value)
        v.setTextColor(0xFFFFD166.toInt())
        v.textSize = 12f
        head.addView(n)
        head.addView(v)
        box.addView(head)
        val sb = SeekBar(this)
        sb.max = 1000
        sb.progress = (((value - min) / (max - min)) * 1000f).toInt().coerceIn(0, 1000)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val x = min + (p / 1000f) * (max - min)
                v.text = fmt(x)
                onChange(x)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(sb)
        return box
    }

    private fun chips(names: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(4), 0, dp(4))
        val views = ArrayList<TextView>()
        names.forEachIndexed { i, n ->
            val t = TextView(this)
            t.text = n
            t.textSize = 12f
            t.setBackgroundResource(R.drawable.bg_chip)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(14), 0, dp(14), 0)
            t.setTextColor(if (i == selected) 0xFFFFD166.toInt() else 0xFFFFFFFF.toInt())
            t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { setMargins(0, 0, dp(6), 0) }
            t.setOnClickListener {
                views.forEachIndexed { j, v -> v.setTextColor(if (j == i) 0xFFFFD166.toInt() else 0xFFFFFFFF.toInt()) }
                onPick(i)
            }
            views.add(t)
            row.addView(t)
        }
        hs.addView(row)
        return hs
    }

    private fun colorChips(colors: List<Int>, onPick: (Int) -> Unit): View {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(4), 0, dp(4))
        colors.forEach { col ->
            val v = View(this)
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            d.setColor(col)
            d.setStroke(dp(2), 0xFFFFFFFF.toInt())
            v.background = d
            v.layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(0, 0, dp(8), 0) }
            v.setOnClickListener { onPick(col) }
            row.addView(v)
        }
        hs.addView(row)
        return hs
    }

    private fun label(s: String): View {
        val t = TextView(this)
        t.text = s
        t.setTextColor(0xFFC9C6DA.toInt())
        t.textSize = 12f
        t.setPadding(0, dp(6), 0, 0)
        return t
    }

    private fun button(text: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(0xFFFFFFFF.toInt())
        t.textSize = 13f
        t.gravity = Gravity.CENTER
        t.setBackgroundResource(R.drawable.bg_chip)
        t.setPadding(dp(16), 0, dp(16), 0)
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { setMargins(0, dp(6), dp(8), 0) }
        t.setOnClickListener { onClick() }
        return t
    }

    /** Bottom sheet that leaves the preview visible so changes can be seen live. */
    private fun sheet(title: String, vararg views: View, onClose: (() -> Unit)? = null): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundResource(R.drawable.bg_panel)
        root.setPadding(dp(14), dp(10), dp(14), dp(12))
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        val t = TextView(this)
        t.text = title
        t.setTextColor(0xFFFFFFFF.toInt())
        t.textSize = 15f
        t.setTypeface(null, Typeface.BOLD)
        t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val done = TextView(this)
        done.text = "Done"
        done.setTextColor(0xFFFFD166.toInt())
        done.textSize = 14f
        done.setTypeface(null, Typeface.BOLD)
        done.setPadding(dp(12), dp(6), 0, dp(6))
        done.setOnClickListener { d.dismiss() }
        head.addView(t)
        head.addView(done)
        root.addView(head)
        views.forEach { root.addView(it) }
        d.setContentView(root)
        d.setOnDismissListener { onClose?.invoke() }
        d.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setDimAmount(0.0f)
        }
        d.show()
        return d
    }

    /** Sheet for tweaking the look of a clip: one undo step, live preview. */
    private fun lookSheet(title: String, c: Clip, vararg views: View) {
        project.checkpoint()
        val before = c.paramSig()
        sheet(title, *views, onClose = {
            if (c.paramSig() == before) project.discardCheckpoint()
            afterEdit()
        })
    }

    private fun live() { engine.refresh(); timeline.invalidate() }

    // ============================================================ rail / right column / nav

    private fun railItem(icon: Int, name: String, accent: Boolean, onClick: () -> Unit): View {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.gravity = Gravity.CENTER
        if (accent) l.setBackgroundResource(R.drawable.bg_rail_on)
        l.isClickable = true
        l.isFocusable = true
        l.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        l.setOnClickListener { onClick() }
        val iv = ImageView(this)
        iv.setImageResource(icon)
        iv.layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        l.addView(iv)
        val tv = TextView(this)
        tv.text = name
        tv.textSize = 9f
        tv.setTextColor(0xFFDAD7EA.toInt())
        tv.gravity = Gravity.CENTER
        l.addView(tv)
        return l
    }

    private fun buildRail() {
        val rail = findViewById<LinearLayout>(R.id.rail)
        rail.addView(railItem(R.drawable.ic_media, "Media", true) { pickVideo() })
        rail.addView(railItem(R.drawable.ic_audio, "Audio", false) { audioPicker.launch(arrayOf("audio/*")) })
        rail.addView(railItem(R.drawable.ic_text, "Text", false) { textDialog() })
        rail.addView(railItem(R.drawable.ic_stickers, "Stickers", false) { stickerDialog() })
        rail.addView(railItem(R.drawable.ic_effects, "Effects", false) { effectsDialog() })
    }

    private fun buildRightColumn() {
        val col = findViewById<LinearLayout>(R.id.rightCol)
        val export = LinearLayout(this)
        export.orientation = LinearLayout.HORIZONTAL
        export.gravity = Gravity.CENTER
        export.setBackgroundResource(R.drawable.bg_export)
        export.isClickable = true
        export.isFocusable = true
        export.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
        export.setOnClickListener { startExport() }
        val ei = ImageView(this)
        ei.setImageResource(R.drawable.ic_export)
        ei.layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        val et = TextView(this)
        et.text = "Export"
        et.setTextColor(Color.WHITE)
        et.textSize = 13f
        et.setTypeface(null, Typeface.BOLD)
        et.setPadding(dp(5), 0, 0, 0)
        export.addView(ei)
        export.addView(et)
        col.addView(export)

        val sizes = listOf("720P" to 720, "1080P" to 1080, "4K" to 2160)
        val rows = ArrayList<Pair<Int, TextView>>()
        sizes.forEach { (name, short) ->
            val t = TextView(this)
            t.text = name
            t.textSize = 12f
            t.gravity = Gravity.CENTER_VERTICAL
            t.setPadding(dp(10), 0, dp(6), 0)
            t.setBackgroundResource(R.drawable.bg_chip)
            t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)).apply { topMargin = dp(5) }
            t.setOnClickListener { resShort = short; refreshRes() }
            rows.add(short to t)
            col.addView(t)
        }
        resRows = rows

        fpsLabel = TextView(this)
        fpsLabel.textSize = 12f
        fpsLabel.gravity = Gravity.CENTER_VERTICAL
        fpsLabel.setTextColor(Color.WHITE)
        fpsLabel.setPadding(dp(10), 0, dp(6), 0)
        fpsLabel.setBackgroundResource(R.drawable.bg_chip)
        fpsLabel.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)).apply { topMargin = dp(5) }
        fpsLabel.setOnClickListener {
            val opts = listOf(24, 30, 60)
            sheet("Frame rate", chips(opts.map { "$it fps" }, opts.indexOf(fps)) { fps = opts[it]; refreshRes() },
                label("Frames are only dropped, never invented: a 30 fps video stays 30 fps even if you pick 60."))
        }
        col.addView(fpsLabel)

        val hdr = TextView(this)
        hdr.text = "HDR  Off"
        hdr.textSize = 12f
        hdr.gravity = Gravity.CENTER_VERTICAL
        hdr.setTextColor(0xFF9A96B5.toInt())
        hdr.setPadding(dp(10), 0, dp(6), 0)
        hdr.setBackgroundResource(R.drawable.bg_chip)
        hdr.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)).apply { topMargin = dp(5) }
        hdr.setOnClickListener { soon("HDR export") }
        col.addView(hdr)
        refreshRes()
    }

    private fun refreshRes() {
        resRows.forEach { (short, tv) ->
            val on = short == resShort
            tv.setTextColor(if (on) 0xFFFFD166.toInt() else 0xFFFFFFFF.toInt())
            val base = when (short) { 720 -> "720P"; 1080 -> "1080P"; else -> "4K" }
            tv.text = if (on) "$base  ✓" else base
        }
        fpsLabel.text = "$fps fps  ▾"
    }

    private fun buildBottomNav() {
        val nav = findViewById<LinearLayout>(R.id.bottomNav)
        fun item(icon: Int, name: String, accent: Boolean, click: () -> Unit) {
            val l = LinearLayout(this)
            l.orientation = LinearLayout.VERTICAL
            l.gravity = Gravity.CENTER
            l.isClickable = true
            l.isFocusable = true
            l.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            l.setOnClickListener { click() }
            val iv = ImageView(this)
            iv.setImageResource(icon)
            iv.layoutParams = LinearLayout.LayoutParams(if (accent) dp(30) else dp(22), if (accent) dp(30) else dp(22))
            l.addView(iv)
            val tv = TextView(this)
            tv.text = name
            tv.textSize = 10f
            tv.setTextColor(if (accent) 0xFFFFFFFF.toInt() else 0xFFB9B5D0.toInt())
            l.addView(tv)
            nav.addView(l)
        }
        item(R.drawable.ic_home, "Home", false) { findViewById<View>(R.id.toolsScroll).scrollTo(0, 0) }
        item(R.drawable.ic_folder, "Project", false) { projectDialog() }
        item(R.drawable.ic_add, "Import", true) { pickVideo() }
        item(R.drawable.ic_star, "Template", false) { soon("Templates") }
        item(R.drawable.ic_person, "Me", false) { aboutDialog() }
    }

    // ============================================================ tool rows

    private fun buildTools() {
        section("Edit", listOf(
            tile(R.drawable.ic_split, "Cut", true) { doCut() },
            tile(R.drawable.ic_trim, "Trim start") { doTrim(true) },
            tile(R.drawable.ic_trim, "Trim end") { doTrim(false) },
            tile(R.drawable.ic_merge, "Merge") { engine.pause(); result(project.merge()) },
            tile(R.drawable.ic_dup, "Duplicate") { engine.pause(); result(project.duplicate()) },
            tile(R.drawable.ic_delete, "Delete") { engine.pause(); result(project.delete()) },
            tile(R.drawable.ic_speed, "Speed") { speedDialog() },
            tile(R.drawable.ic_volume, "Volume") { volumeDialog() },
            tile(R.drawable.ic_frameback, "Frame ◀") { step(-1) },
            tile(R.drawable.ic_frameforward, "Frame ▶") { step(1) },
            tile(R.drawable.ic_zoom, "Zoom +") { timeline.zoomIn() },
            tile(R.drawable.ic_zoom, "Zoom −") { timeline.zoomOut() }
        ))
        section("Picture", listOf(
            tile(R.drawable.ic_crop, "Crop") { cropDialog() },
            tile(R.drawable.ic_rotate, "Rotate") { rotate() },
            tile(R.drawable.ic_flip, "Flip") { flipDialog() },
            tile(R.drawable.ic_canvas, "Canvas") { canvasDialog() },
            tile(R.drawable.ic_keyframe, "Keyframe") { keyframeDialog() },
            tile(R.drawable.ic_animation, "Animation") { animationDialog() },
            tile(R.drawable.ic_reverse, "Reverse") { soon("Reverse") },
            tile(R.drawable.ic_freeze, "Freeze") { soon("Freeze frame") }
        ))
        section("Look", listOf(
            tile(R.drawable.ic_filters, "Filters") { filtersDialog() },
            tile(R.drawable.ic_adjust, "Adjust") { adjustDialog() },
            tile(R.drawable.ic_hsl, "HSL") { hslDialog() },
            tile(R.drawable.ic_effects, "Effects") { effectsDialog() },
            tile(R.drawable.ic_blur, "Blur") { blurDialog() },
            tile(R.drawable.ic_chroma, "Chroma Key") { chromaDialog() },
            tile(R.drawable.ic_mask, "Mask") { soon("Mask") },
            tile(R.drawable.ic_blend, "Blend") { soon("Blend") },
            tile(R.drawable.ic_removebg, "Remove BG") { soon("Remove background") },
            tile(R.drawable.ic_stabilize, "Stabilize") { soon("Stabilize") }
        ))
        section("Text & stickers", listOf(
            tile(R.drawable.ic_text, "Text") { textDialog() },
            tile(R.drawable.ic_captions, "Captions") { srtPicker.launch(arrayOf("*/*")) },
            tile(R.drawable.ic_stickers, "Stickers") { stickerDialog() },
            tile(R.drawable.ic_overlay, "Overlay") { if (project.isEmpty()) toast("Import a video first") else imagePicker.launch(arrayOf("image/*")) },
            tile(R.drawable.ic_autocap, "Auto captions") { soon("Auto captions") }
        ))
        section("Audio", listOf(
            tile(R.drawable.ic_music, "Music") { audioPicker.launch(arrayOf("audio/*")) },
            tile(R.drawable.ic_extract, "Extract") { extractAudio() },
            tile(R.drawable.ic_mute, "Mute") { toggleMute() },
            tile(R.drawable.ic_mic, "Voiceover") { soon("Voiceover") },
            tile(R.drawable.ic_noise, "Noise") { soon("Noise reduction") },
            tile(R.drawable.ic_beat, "Beats") { soon("Beat sync") }
        ))
    }

    // ============================================================ edit tools

    private fun doCut() {
        engine.pause()
        result(project.split(engine.playheadMs))
    }

    private fun doTrim(start: Boolean) {
        engine.pause()
        result(if (start) project.trimStartTo(engine.playheadMs) else project.trimEndTo(engine.playheadMs))
    }

    private fun step(dir: Int) {
        engine.pause()
        val f = (1000f / max(1f, project.main.firstOrNull()?.fps ?: 30f)).toLong()
        engine.seekTo(engine.playheadMs + dir * max(1L, f))
        syncPlayhead()
    }

    private fun toggleMute() {
        val c = need() ?: return
        project.checkpoint()
        c.volume = if (c.volume <= 0.001f) 1f else 0f
        afterEdit()
    }

    private fun volumeDialog() {
        val c = need() ?: return
        lookSheet("Volume", c, slider("Volume", 0f, 2f, c.volume, { "${(it * 100).toInt()}%" }) { c.volume = it; live() })
    }

    private fun speedDialog() {
        val c = need() ?: return
        if (!c.isMedia) { toast("Speed works on video and audio clips"); return }
        project.checkpoint()
        val before = c.speed
        val d = slider("Speed", 0.25f, 4f, c.speed, { "%.2fx".format(it) }) { project.setSpeed(c, it); live() }
        val presets = chips(listOf("0.25x", "0.5x", "1x", "2x", "3x", "4x"), -1) {
            project.setSpeed(c, floatArrayOf(0.25f, 0.5f, 1f, 2f, 3f, 4f)[it]); live(); syncUi()
        }
        sheet("Speed", d, presets, label("Changes the length of the clip. Audio speed is changed too."), onClose = {
            if (c.speed == before) project.discardCheckpoint()
            project.relayout()
            afterEdit()
        })
    }

    private fun rotate() {
        val c = need(true) ?: return
        project.checkpoint()
        c.rotation = (c.rotation + 90) % 360
        afterEdit()
    }

    private fun flipDialog() {
        val c = need(true) ?: return
        lookSheet("Flip", c,
            button("Mirror left ↔ right") { c.flipH = !c.flipH; live() },
            button("Mirror top ↕ bottom") { c.flipV = !c.flipV; live() })
    }

    private fun cropDialog() {
        val c = need(true) ?: return
        val cur = Fx.CROP_RATIOS.indexOfFirst { it == c.cropRatio }.coerceAtLeast(0)
        lookSheet("Crop", c, chips(Fx.CROP_NAMES, cur) { c.cropRatio = Fx.CROP_RATIOS[it]; live(); syncUi() })
    }

    private fun canvasDialog() {
        val names = listOf("Auto", "16:9", "9:16", "1:1", "4:5", "4:3")
        val ratios = listOf(0f, 16f / 9f, 9f / 16f, 1f, 4f / 5f, 4f / 3f)
        val cur = ratios.indexOfFirst { it == project.canvasAspect }.coerceAtLeast(0)
        sheet("Canvas (export shape)", chips(names, cur) { project.canvasAspect = ratios[it]; syncUi() },
            label("The exported video is fitted into this shape with black bars. Use Crop to fill the frame instead."))
    }

    private fun extractAudio() {
        val c = need(true) ?: return
        if (!c.hasAudio) { toast("This clip has no audio"); return }
        project.checkpoint()
        val a = c.deepCopy(project.newId())
        a.kind = LaneKind.AUDIO
        a.keyframes = mutableListOf()
        a.startMs = c.startMs
        project.audio.add(a)
        c.volume = 0f
        toast("Audio extracted to its own track")
        afterEdit()
    }

    // ============================================================ look tools

    private fun filtersDialog() {
        val c = need(true) ?: return
        lookSheet("Filters", c, chips(Fx.FILTER_NAMES, c.filter) { c.filter = it; live() })
    }

    private fun adjustDialog() {
        val c = need(true) ?: return
        lookSheet("Adjust", c,
            slider("Brightness", -0.5f, 0.5f, c.brightness, { "%+d".format((it * 100).toInt()) }) { c.brightness = it; live() },
            slider("Contrast", 0.5f, 1.5f, c.contrast, { "%d%%".format((it * 100).toInt()) }) { c.contrast = it; live() },
            slider("Saturation", 0f, 2f, c.saturation, { "%d%%".format((it * 100).toInt()) }) { c.saturation = it; live() },
            slider("Temperature", -1f, 1f, c.temp, { "%+d".format((it * 100).toInt()) }) { c.temp = it; live() })
    }

    private fun hslDialog() {
        val c = need(true) ?: return
        lookSheet("HSL", c,
            slider("Hue shift", 0f, 360f, c.hue, { "${it.toInt()}°" }) { c.hue = it; live() },
            slider("Lightness", -100f, 100f, c.lum, { "%+d".format(it.toInt()) }) { c.lum = it; live() })
    }

    private fun blurDialog() {
        val c = need(true) ?: return
        lookSheet("Blur", c, slider("Blur", 0f, 25f, c.blur, { "%.0f".format(it) }) { c.blur = it; live() })
    }

    private fun effectsDialog() {
        val c = need(true) ?: return
        lookSheet("Effects", c,
            label("Filter"),
            chips(Fx.FILTER_NAMES, c.filter) { c.filter = it; live() },
            slider("Blur", 0f, 25f, c.blur, { "%.0f".format(it) }) { c.blur = it; live() },
            slider("Contrast", 0.5f, 1.5f, c.contrast, { "%d%%".format((it * 100).toInt()) }) { c.contrast = it; live() })
    }

    private fun chromaDialog() {
        val c = need(true) ?: return
        val ch = c.chroma
        val keys = listOf(0xFF00B140.toInt(), 0xFF0047BB.toInt(), 0xFFFF0000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val bgs = listOf(Color.BLACK, Color.WHITE, 0xFF1E88E5.toInt(), 0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFF8E24AA.toInt())
        lookSheet("Chroma Key", c,
            chips(listOf("Off", "On"), if (ch.enabled) 1 else 0) { ch.enabled = it == 1; live() },
            label("Colour to remove"),
            colorChips(keys) { ch.key = it; ch.enabled = true; live() },
            label("Replace with"),
            colorChips(bgs) { ch.bg = it; ch.enabled = true; live() },
            slider("Similarity", 0.05f, 0.9f, ch.similarity, { "%d%%".format((it * 100).toInt()) }) { ch.similarity = it; live() },
            slider("Smoothness", 0.02f, 0.6f, ch.smooth, { "%d%%".format((it * 100).toInt()) }) { ch.smooth = it; live() },
            label("The removed colour becomes the solid colour above."))
    }

    // ============================================================ keyframes / animation

    private fun keyframeDialog() {
        val c = need(true) ?: return
        val local = (engine.playheadMs - c.startMs).coerceIn(0L, c.lengthMs)
        val cur = if (c.keyframes.isEmpty()) Keyframe(local) else Keyframes.valueAt(c.keyframes, local)
        var scale = cur.scale; var x = cur.x; var y = cur.y; var rot = cur.rot
        fun put() { project.setKeyframe(c, Keyframe(local, scale, x, y, rot)); live() }
        lookSheet("Keyframe at ${fmt(engine.playheadMs)}", c,
            slider("Scale", 0.2f, 3f, scale, { "%d%%".format((it * 100).toInt()) }) { scale = it; put() },
            slider("Position X", -1f, 1f, x, { "%+d".format((it * 100).toInt()) }) { x = it; put() },
            slider("Position Y", -1f, 1f, y, { "%+d".format((it * 100).toInt()) }) { y = it; put() },
            slider("Rotation", -180f, 180f, rot, { "${it.toInt()}°" }) { rot = it; put() },
            button("Delete all keyframes") { c.keyframes.clear(); live() },
            label("Move the playhead, open Keyframe again and set another value. The clip animates between them."))
    }

    private fun animationDialog() {
        val c = need(true) ?: return
        val names = listOf("None", "Zoom in", "Zoom out", "Pan right", "Pan left", "Spin")
        lookSheet("Animation", c, chips(names, -1) {
            val end = max(200L, c.lengthMs)
            c.keyframes = when (it) {
                1 -> mutableListOf(Keyframe(0, 1f), Keyframe(end, 1.35f))
                2 -> mutableListOf(Keyframe(0, 1.35f), Keyframe(end, 1f))
                3 -> mutableListOf(Keyframe(0, 1.25f, -0.15f), Keyframe(end, 1.25f, 0.15f))
                4 -> mutableListOf(Keyframe(0, 1.25f, 0.15f), Keyframe(end, 1.25f, -0.15f))
                5 -> mutableListOf(Keyframe(0, 1f, 0f, 0f, 0f), Keyframe(end, 1f, 0f, 0f, 360f))
                else -> mutableListOf()
            }
            live()
        })
    }

    // ============================================================ text / stickers / captions

    private val palette = listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFD166.toInt(), 0xFFFF5252.toInt(), 0xFF4CAF50.toInt(), 0xFF40C4FF.toInt(), 0xFFFF80AB.toInt())

    private fun newFree(kind: LaneKind, text: String): Clip? {
        if (project.isEmpty()) { toast("Import a video first"); return null }
        engine.pause()
        val start = min(engine.playheadMs, max(0L, project.totalMs() - 100))
        val c = Clip(id = project.newId(), kind = kind, startMs = start, text = text, durMs = min(3000L, project.totalMs() - start))
        project.addFree(c)
        return c
    }

    private fun textDialog() {
        var c = project.selected()?.takeIf { it.kind == LaneKind.TEXT }
        val fresh = c == null
        if (c == null) c = newFree(LaneKind.TEXT, "Your text") ?: return else project.checkpoint()
        val clip = c
        val edit = EditText(this)
        edit.setText(clip.text)
        edit.setTextColor(Color.WHITE)
        edit.setHintTextColor(0xFF8E8AA8.toInt())
        edit.hint = "Type here"
        edit.setSelection(edit.text.length)
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { clip.text = s?.toString() ?: ""; overlayView.invalidate(); timeline.invalidate() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c1: Int) {}
        })
        val d = sheet(if (fresh) "Add text" else "Edit text", edit,
            label("Font"),
            chips(Fx.FONT_NAMES, clip.font) { clip.font = it; overlayView.invalidate() },
            label("Colour"),
            colorChips(palette) { clip.color = it; overlayView.invalidate() },
            slider("Size", 0.02f, 0.2f, clip.sizeFrac, { "%d".format((it * 100).toInt()) }) { clip.sizeFrac = it; overlayView.invalidate() },
            chips(listOf("Bold", "Regular"), if (clip.bold) 0 else 1) { clip.bold = it == 0; overlayView.invalidate() },
            label("Drag the text in the preview to place it. Drag its ends on the timeline to set how long it shows."),
            onClose = {
                if (clip.text.isBlank()) { project.selectedId = clip.id; project.delete() }
                afterEdit()
            })
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun stickerDialog() {
        val emojis = listOf("😀", "😍", "😂", "🔥", "❤️", "👍", "🎉", "⭐", "💯", "😎", "🚀", "🌈", "👑", "🎵", "💥", "✨")
        sheet("Stickers", chips(emojis, -1) { i ->
            val c = newFree(LaneKind.TEXT, emojis[i]) ?: return@chips
            c.sizeFrac = 0.18f
            c.posY = 0.5f
            afterEdit()
        }, label("Tap a sticker to add it at the playhead. Drag it in the preview to move it."))
    }

    private fun readSrt(u: Uri) {
        if (project.isEmpty()) { toast("Import a video first"); return }
        try {
            val s = contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } ?: return
            var count = 0
            project.checkpoint()
            s.replace("\r", "").split("\n\n").forEach { b ->
                val lines = b.lines()
                val ti = lines.indexOfFirst { it.contains("-->") }
                if (ti >= 0) {
                    val parts = lines[ti].split("-->")
                    if (parts.size == 2) {
                        val st = parseTime(parts[0].trim())
                        val en = parseTime(parts[1].trim().split(" ")[0])
                        val text = lines.drop(ti + 1).joinToString(" ").trim()
                        if (st >= 0 && en > st && text.isNotEmpty()) {
                            project.text.add(Clip(id = project.newId(), kind = LaneKind.TEXT, startMs = st, text = text, durMs = en - st, sizeFrac = 0.05f, posY = 0.88f))
                            count++
                        }
                    }
                }
            }
            if (count == 0) project.discardCheckpoint()
            toast("Loaded $count subtitle cues")
            afterEdit()
        } catch (e: Exception) {
            Toast.makeText(this, "SRT read error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun parseTime(s: String): Long {
        return try {
            val p = s.replace(",", ".").split(":")
            ((p[0].toLong() * 3600 + p[1].toLong() * 60 + p[2].substringBefore(".").toLong()) * 1000) +
                p[2].substringAfter(".", "0").padEnd(3, '0').take(3).toLong()
        } catch (_: Exception) { -1L }
    }

    // ============================================================ dialogs: project / about / fullscreen

    private fun projectDialog() {
        val v = TextView(this)
        v.setTextColor(0xFFDAD7EA.toInt())
        v.textSize = 13f
        val size = exporter.outputSize(project, resShort)
        v.text = "Video clips: ${project.main.size}\nAudio clips: ${project.audio.size}\nText / stickers: ${project.text.size}\n" +
            "Overlays: ${project.overlay.size}\nLength: ${fmt(project.totalMs())}\nExport: ${size.width}×${size.height} @ $fps fps"
        sheet("Project", v)
    }

    private fun aboutDialog() {
        sheet("GatsMod Cut", label("Version ${versionName()}"), button("Check for updates") { updater.check(false) })
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        val vis = if (fullscreen) View.GONE else View.VISIBLE
        listOf(R.id.topBar, R.id.rail, R.id.rightCol, R.id.timelineBlock, R.id.toolsScroll, R.id.bottomNav).forEach { findViewById<View>(it).visibility = vis }
        val row = findViewById<View>(R.id.editorRow)
        val lp = row.layoutParams as LinearLayout.LayoutParams
        if (fullscreen) { lp.height = 0; lp.weight = 1f } else { lp.height = dp(170); lp.weight = 0f }
        row.layoutParams = lp
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ============================================================ export

    private fun startExport() {
        if (project.isEmpty()) { toast("Import a video first"); return }
        if (exporter.running) { toast("Export already running"); return }
        engine.pause()
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.isIndeterminate = true
        val status = TextView(this)
        status.setTextColor(0xFFDAD7EA.toInt())
        status.textSize = 13f
        status.text = "Exporting…"
        val dlg = sheet("Exporting", status, bar)
        dlg.setCancelable(false)
        dlg.setCanceledOnTouchOutside(false)
        var finished = false
        val cancel = button("Cancel") { exporter.cancel(); finished = true; dlg.dismiss(); toast("Export cancelled") }
        (dlg.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? ViewGroup)?.addView(cancel)

        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                val p = exporter.progress()
                if (p >= 0) { bar.isIndeterminate = false; bar.progress = p; status.text = "Exporting… $p%" }
                ui.postDelayed(this, 300)
            }
        }
        ui.post(poll)
        exporter.start(project, ExportSettings(resShort, fps), object : Exporter.Callback {
            override fun onDone(file: File) {
                finished = true
                Thread {
                    val uri = Exporter.saveToGallery(this@MainActivity, file)
                    runOnUiThread {
                        dlg.dismiss()
                        if (uri == null) toast("Export finished but saving failed") else exportDone(uri)
                    }
                }.start()
            }

            override fun onError(message: String) {
                finished = true
                runOnUiThread {
                    dlg.dismiss()
                    sheet("Export failed", label(message))
                }
            }
        })
    }

    private fun exportDone(uri: Uri) {
        sheet("Saved to Movies/GatsMod Cut", button("Open") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) { toast("No video player found") }
        }, button("Share") {
            val i = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share video"))
        })
    }
}

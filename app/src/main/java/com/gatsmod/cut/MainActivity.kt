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
import androidx.activity.result.PickVisualMediaRequest
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

    private var projectId = ""
    private var projectName = "Project"
    private var resShort = 1080
    private var fps = 30
    private var fullscreen = false

    private val videoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> u?.let { loadVideo(it) } }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { persist(it); loadAudio(it) } }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { persist(it); loadImage(it) } }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) voiceoverDialog() else toast("Microphone permission is needed to record a voiceover")
    }
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
        val crashFile = File(filesDir, "crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { crashFile.writeText(android.util.Log.getStackTraceString(e)) } catch (_: Exception) {}
            previous?.uncaughtException(t, e)
        }
        setContentView(R.layout.activity_main)
        engine = PreviewEngine(this, project)

        val pv = findViewById<PlayerView>(R.id.playerView)
        pv.player = engine.player
        engine.onPlayerReplaced = { p -> pv.player = p }
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

            override fun onProblem(message: String) {
                runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
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
        timeline.onAdd = { k -> if (k == LaneKind.AUDIO) audioPicker.launch(arrayOf("audio/*")) else textDialog() }
        timeline.onSpecial = { name -> soon(name) }

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
        findViewById<View>(R.id.menuButton).setOnClickListener {
            finish()
        }
        findViewById<View>(R.id.settingsButton).setOnClickListener { searchDialog() }
        findViewById<View>(R.id.exportButton).setOnClickListener { startExport() }
        findViewById<View>(R.id.resChip).setOnClickListener { exportSettingsDialog() }
        findViewById<View>(R.id.keyframeButton).setOnClickListener { keyframeDialog() }
        refreshRes()
        buildToolbar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) toggleFullscreen()
                else if (project.selected() != null) { project.selectedId = -1L; syncUi() }
                else finish()
            }
        })

        if (crashFile.exists()) {
            val txt = try { crashFile.readText() } catch (_: Exception) { "" }
            crashFile.delete()
            if (txt.isNotBlank()) {
                val tv = TextView(this)
                tv.text = txt.take(1800)
                tv.setTextColor(0xFFFF8A80.toInt())
                tv.textSize = 9f
                tv.setTextIsSelectable(true)
                sheet("The app crashed last time", label("Send this text to the developer:"), tv)
            }
        }
        projectId = intent.getStringExtra("id") ?: ("p" + System.currentTimeMillis())
        projectName = "Project " + java.text.SimpleDateFormat("MMdd-HH", java.util.Locale.US).format(java.util.Date())
        if (intent.hasExtra("id")) {
            val n = ProjectStore.load(this, projectId, project)
            if (n == null) toast("Could not open this project") else {
                projectName = n
                engine.refresh()
                if (!project.isEmpty()) engine.seekTo(0)
            }
        } else if (intent.getBooleanExtra("pick", false)) {
            ui.postDelayed({ pickVideo() }, 250)
        }
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

    override fun onPause() { super.onPause(); engine.pause(); saveProject() }

    /** Autosave: writes the project file now, and the size and cover picture in the background. */
    private fun saveProject() {
        if (project.isEmpty() || projectId.isEmpty()) return
        val json = ProjectStore.toJson(projectName, project, 0)
        val uris = (project.main + project.audio + project.overlay).map { it.uri }.filter { it.isNotEmpty() }.distinct()
        val first = project.main.first()
        val coverUri = first.uri
        val coverMs = first.srcInMs
        val id = projectId
        val app = applicationContext
        Thread {
            var size = 0L
            uris.forEach { u ->
                try {
                    app.contentResolver.query(Uri.parse(u), arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) size += c.getLong(0)
                    }
                } catch (_: Exception) {}
            }
            try { json.put("size", size); ProjectStore.write(app, id, json) } catch (_: Exception) {}
            try {
                val r = android.media.MediaMetadataRetriever()
                r.setDataSource(app, Uri.parse(coverUri))
                val full = r.getFrameAtTime(coverMs * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                r.release()
                if (full != null) {
                    val w = 240
                    val h = (w * full.height.toFloat() / full.width).toInt().coerceAtLeast(1)
                    val sm = android.graphics.Bitmap.createScaledBitmap(full, w, h, true)
                    ProjectStore.thumb(app, id).outputStream().use { sm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                }
            } catch (_: Exception) {}
        }.start()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try { recorder?.release() } catch (_: Exception) {}
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
        if (toolbarKeyNow() != toolbarKey) buildToolbar()
        emptyPreview.visibility = if (project.isEmpty()) View.VISIBLE else View.GONE
        overlayView.canvasAspect = project.effectiveAspect()
        val c = project.selected() ?: project.mainClipAt(engine.playheadMs)
        val muted = c != null && c.volume <= 0.001f
        muteIcon.setImageResource(if (muted) R.drawable.ic_mute else R.drawable.ic_volume)
        muteLabel.text = if (muted) "Unmute clip" else "Mute clip"
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

    private fun pickVideo() = videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))

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

    private val liveRun = Runnable { engine.refresh(); timeline.invalidate() }

    /** Slider changes: wait a moment so the preview is not rebuilt on every pixel of movement. */
    private fun live() { ui.removeCallbacks(liveRun); ui.postDelayed(liveRun, 180) }

    // ============================================================ bottom tool row (CapCut style)

    private class T(val icon: Int, val name: String, val act: () -> Unit)

    private var toolbarKey = "?"

    private fun toolItem(t: T, back: Boolean = false): View {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.gravity = Gravity.CENTER
        l.isClickable = true
        l.isFocusable = true
        l.layoutParams = LinearLayout.LayoutParams(if (back) dp(46) else dp(66), ViewGroup.LayoutParams.MATCH_PARENT)
        l.setOnClickListener { t.act() }
        val iv = ImageView(this)
        iv.setImageResource(t.icon)
        if (back) iv.rotation = 90f
        iv.layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        l.addView(iv)
        if (!back) {
            val tv = TextView(this)
            tv.text = t.name
            tv.textSize = 10f
            tv.setTextColor(0xFFE6E6EA.toInt())
            tv.gravity = Gravity.CENTER
            tv.maxLines = 1
            tv.setPadding(0, dp(4), 0, 0)
            l.addView(tv)
        }
        return l
    }

    private fun mainTools(): List<T> = listOf(
        T(R.drawable.ic_cut, "Edit") { val c = need(); if (c != null) syncUi() },
        T(R.drawable.ic_audio, "Audio") { audioPicker.launch(arrayOf("audio/*")) },
        T(R.drawable.ic_mic, "Voiceover") { startVoiceover() },
        T(R.drawable.ic_text, "Text") { textDialog() },
        T(R.drawable.ic_effects, "Effects") { effectsDialog() },
        T(R.drawable.ic_overlay, "Overlay") { if (project.isEmpty()) toast("Import a video first") else imagePicker.launch(arrayOf("image/*")) },
        T(R.drawable.ic_captions, "Captions") { srtPicker.launch(arrayOf("*/*")) },
        T(R.drawable.ic_filters, "Filters") { filtersDialog() },
        T(R.drawable.ic_adjust, "Adjust") { adjustDialog() },
        T(R.drawable.ic_stickers, "Stickers") { stickerDialog() },
        T(R.drawable.ic_canvas, "Canvas") { canvasDialog() },
        T(R.drawable.ic_autocap, "Auto captions") { soon("Auto captions") },
        T(R.drawable.ic_star, "Templates") { soon("Templates") }
    )

    private fun clipTools(c: Clip): List<T> {
        val split = T(R.drawable.ic_split, "Split") { doCut() }
        val dup = T(R.drawable.ic_dup, "Duplicate") { engine.pause(); result(project.duplicate()) }
        val del = T(R.drawable.ic_delete, "Delete") { engine.pause(); result(project.delete()) }
        val trimS = T(R.drawable.ic_trim, "Trim start") { doTrim(true) }
        val trimE = T(R.drawable.ic_trim, "Trim end") { doTrim(false) }
        return when (c.kind) {
            LaneKind.VIDEO -> listOf(
                split,
                T(R.drawable.ic_speed, "Speed") { speedDialog() },
                T(R.drawable.ic_animation, "Animations") { animationDialog() },
                del,
                T(R.drawable.ic_volume, "Volume") { volumeDialog() },
                T(R.drawable.ic_fade, "Fade") { fadeDialog() },
                T(R.drawable.ic_transitions, "Transition") { transitionDialog() },
                T(R.drawable.ic_effects, "Effects") { effectsDialog() },
                T(R.drawable.ic_filters, "Filters") { filtersDialog() },
                T(R.drawable.ic_adjust, "Adjust") { adjustDialog() },
                T(R.drawable.ic_crop, "Crop") { cropDialog() },
                T(R.drawable.ic_rotate, "Rotate") { rotate() },
                T(R.drawable.ic_flip, "Mirror") { flipDialog() },
                T(R.drawable.ic_resize, "Transform") { transformDialog() },
                T(R.drawable.ic_keyframe, "Keyframe") { keyframeDialog() },
                T(R.drawable.ic_chroma, "Chroma key") { chromaDialog() },
                T(R.drawable.ic_hsl, "HSL") { hslDialog() },
                T(R.drawable.ic_blur, "Blur") { blurDialog() },
                T(R.drawable.ic_extract, "Extract audio") { extractAudio() },
                trimS, trimE,
                T(R.drawable.ic_merge, "Merge") { engine.pause(); result(project.merge()) },
                dup,
                T(R.drawable.ic_frameback, "Frame back") { step(-1) },
                T(R.drawable.ic_frameforward, "Frame next") { step(1) },
                T(R.drawable.ic_color, "Reset look") { resetLook() },
                T(R.drawable.ic_help, "Info") { clipInfo() },
                T(R.drawable.ic_reverse, "Reverse") { soon("Reverse") },
                T(R.drawable.ic_freeze, "Freeze") { soon("Freeze frame") },
                T(R.drawable.ic_mask, "Mask") { maskDialog() },
                T(R.drawable.ic_removebg, "Remove BG") { soon("Remove background") },
                T(R.drawable.ic_stabilize, "Stabilize") { soon("Stabilize") }
            )
            LaneKind.AUDIO -> listOf(
                split,
                T(R.drawable.ic_volume, "Volume") { volumeDialog() },
                T(R.drawable.ic_fade, "Fade") { fadeDialog() },
                T(R.drawable.ic_speed, "Speed") { speedDialog() },
                trimS, trimE, dup, del
            )
            LaneKind.TEXT -> listOf(
                T(R.drawable.ic_text, "Edit") { textDialog() },
                split, dup, del
            )
            LaneKind.OVERLAY -> listOf(
                T(R.drawable.ic_resize, "Size") { overlaySizeDialog() },
                split, dup, del
            )
        }
    }

    private fun fadeDialog() {
        val c = need() ?: return
        if (!c.isMedia) { toast("Fade works on video and audio clips"); return }
        val maxMs = min(5000f, c.lengthMs / 2f).coerceAtLeast(200f)
        lookSheet("Fade", c,
            slider("Fade in", 0f, maxMs, min(c.fadeInMs.toFloat(), maxMs), { "%.1fs".format(it / 1000f) }) { c.fadeInMs = it.toLong(); live() },
            slider("Fade out", 0f, maxMs, min(c.fadeOutMs.toFloat(), maxMs), { "%.1fs".format(it / 1000f) }) { c.fadeOutMs = it.toLong(); live() },
            label("Video fades from / to black and the sound fades with it."))
    }

    private fun transitionDialog() {
        val c = need(true) ?: return
        val next = project.main.getOrNull(project.main.indexOf(c) + 1)
        if (next == null) { toast("Add another clip after this one first"); return }
        val maxMs = min(2000f, min(c.lengthMs, next.lengthMs) / 2f).coerceAtLeast(200f)
        lookSheet("Transition", c,
            slider("Dip to black", 0f, maxMs, min(c.fadeOutMs.toFloat(), maxMs), { "%.1fs".format(it / 1000f) }) {
                c.fadeOutMs = it.toLong(); next.fadeInMs = it.toLong(); live()
            },
            label("The clip fades out and the next one fades in, picture and sound. A cross-dissolve is not supported yet."))
    }

    private fun transformDialog() {
        val c = need(true) ?: return
        val cur = if (c.keyframes.isEmpty()) Keyframe(0) else c.keyframes.first()
        var scale = cur.scale; var x = cur.x; var y = cur.y; var rot = cur.rot
        fun put() { c.keyframes = mutableListOf(Keyframe(0, scale, x, y, rot)); live() }
        lookSheet("Transform", c,
            slider("Scale", 0.2f, 3f, scale, { "%d%%".format((it * 100).toInt()) }) { scale = it; put() },
            slider("Position X", -1f, 1f, x, { "%+d".format((it * 100).toInt()) }) { x = it; put() },
            slider("Position Y", -1f, 1f, y, { "%+d".format((it * 100).toInt()) }) { y = it; put() },
            slider("Rotation", -180f, 180f, rot, { "${it.toInt()}°" }) { rot = it; put() },
            button("Reset") { c.keyframes = mutableListOf(); scale = 1f; x = 0f; y = 0f; rot = 0f; live() },
            label("Sets one position for the whole clip. For movement over time use Keyframe."))
    }

    private fun resetLook() {
        val c = need(true) ?: return
        project.checkpoint()
        val d = Clip(id = c.id, kind = c.kind)
        c.filter = d.filter; c.brightness = d.brightness; c.contrast = d.contrast; c.saturation = d.saturation
        c.blur = d.blur; c.temp = d.temp; c.hue = d.hue; c.lum = d.lum; c.cropRatio = d.cropRatio
        c.rotation = d.rotation; c.flipH = false; c.flipV = false
        c.chroma = Chroma(); c.keyframes = mutableListOf(); c.fadeInMs = 0; c.fadeOutMs = 0
        c.fx = 0; c.maskShape = 0; c.maskInvert = false; c.maskX = 0f; c.maskY = 0f
        toast("Look reset")
        afterEdit()
    }

    private fun clipInfo() {
        val c = need() ?: return
        val v = TextView(this)
        v.setTextColor(0xFFDAD7EA.toInt())
        v.textSize = 13f
        v.text = buildString {
            append("Name: ${c.label.ifBlank { "-" }}\n")
            append("On timeline: ${fmt(c.startMs)} – ${fmt(c.endMs)} (${"%.1f".format(c.lengthMs / 1000f)} s)\n")
            if (c.isMedia) {
                append("Source range: ${"%.1f".format(c.srcInMs / 1000f)} – ${"%.1f".format(c.srcOutMs / 1000f)} s of ${"%.1f".format(c.srcTotalMs / 1000f)} s\n")
                append("Speed: ${"%.2f".format(c.speed)}x   Volume: ${(c.volume * 100).toInt()}%\n")
            }
            if (c.kind == LaneKind.VIDEO) append("Size: ${c.width}×${c.height}   ${"%.0f".format(c.fps)} fps\n")
            if (c.keyframes.isNotEmpty()) append("Keyframes: ${c.keyframes.size}\n")
        }
        sheet("Clip info", v)
    }

    // ============================================================ voiceover

    private var recorder: android.media.MediaRecorder? = null

    private fun startVoiceover() {
        if (project.isEmpty()) { toast("Import a video first"); return }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        } else voiceoverDialog()
    }

    private fun voiceoverDialog() {
        engine.pause()
        val startMs = min(engine.playheadMs, max(0L, project.totalMs() - 100))
        val status = TextView(this)
        status.setTextColor(0xFFDAD7EA.toInt())
        status.textSize = 14f
        status.text = "Tap Record. The video plays while you speak — use headphones to avoid echo."
        var file: File? = null
        var began = 0L
        var recording = false
        var dlg: Dialog? = null
        val timer = object : Runnable {
            override fun run() {
                if (!recording) return
                status.text = "Recording…  ${"%.1f".format((System.currentTimeMillis() - began) / 1000f)} s"
                ui.postDelayed(this, 200)
            }
        }
        fun stopRec(save: Boolean) {
            if (!recording) return
            recording = false
            engine.pause()
            val r = recorder
            recorder = null
            var ok = true
            try { r?.stop() } catch (_: Exception) { ok = false }
            try { r?.release() } catch (_: Exception) {}
            val f = file
            if (save && ok && f != null && f.exists() && f.length() > 0) {
                Thread {
                    val info = MediaProbe.probe(this, Uri.fromFile(f))
                    runOnUiThread {
                        val dur = info?.durationMs ?: (System.currentTimeMillis() - began)
                        project.addFree(Clip(id = project.newId(), kind = LaneKind.AUDIO, startMs = startMs, uri = Uri.fromFile(f).toString(),
                            srcInMs = 0, srcOutMs = dur, srcTotalMs = dur, label = "Voiceover"))
                        afterEdit()
                        toast("Voiceover added")
                    }
                }.start()
            } else if (f != null) f.delete()
        }
        val rec = button("●  Record") {
            if (!recording) {
                try {
                    val dir = File(filesDir, "voice").apply { mkdirs() }
                    val f = File(dir, "VO_" + System.currentTimeMillis() + ".m4a")
                    file = f
                    val r = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(this) else @Suppress("DEPRECATION") android.media.MediaRecorder()
                    r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                    r.setAudioSamplingRate(44100)
                    r.setAudioEncodingBitRate(128000)
                    r.setOutputFile(f.absolutePath)
                    r.prepare()
                    r.start()
                    recorder = r
                    recording = true
                    began = System.currentTimeMillis()
                    engine.seekTo(startMs)
                    engine.play()
                    ui.post(timer)
                } catch (e: Exception) {
                    recording = false
                    toast("Could not start recording: ${e.message}")
                }
            } else {
                stopRec(true)
                dlg?.dismiss()
            }
        }
        dlg = sheet("Voiceover", status, rec, onClose = { stopRec(false) })
    }

    private fun overlaySizeDialog() {
        val c = project.selected() ?: return
        project.checkpoint()
        sheet("Size", slider("Size", 0.05f, 1f, c.sizeFrac, { "%d%%".format((it * 100).toInt()) }) { c.sizeFrac = it; overlayView.invalidate() },
            label("Drag the picture in the preview to move it."), onClose = { afterEdit() })
    }

    private fun buildToolbar() {
        val row = findViewById<LinearLayout>(R.id.toolbarRow)
        row.removeAllViews()
        val sel = project.selected()
        if (sel == null) {
            mainTools().forEach { row.addView(toolItem(it)) }
        } else {
            row.addView(toolItem(T(R.drawable.ic_chevron, "Back") { project.selectedId = -1L; syncUi() }, back = true))
            clipTools(sel).forEach { row.addView(toolItem(it)) }
        }
        toolbarKey = toolbarKeyNow()
        findViewById<View>(R.id.toolbarScroll).scrollTo(0, 0)
    }

    private fun toolbarKeyNow(): String = project.selected()?.kind?.name ?: "none"

    private fun refreshRes() {
        val base = when (resShort) { 720 -> "720P"; 1080 -> "1080P"; else -> "4K" }
        findViewById<TextView>(R.id.resChip).text = "$base ▾"
    }

    private fun exportSettingsDialog() {
        val sizes = listOf(720, 1080, 2160)
        val opts = listOf(24, 30, 60)
        sheet("Export settings",
            label("Resolution"),
            chips(listOf("720P", "1080P", "4K"), sizes.indexOf(resShort)) { resShort = sizes[it]; refreshRes() },
            label("Frame rate"),
            chips(opts.map { "$it fps" }, opts.indexOf(fps)) { fps = opts[it]; refreshRes() },
            label("Frames are only dropped, never invented: a 30 fps video stays 30 fps even if you pick 60."),
            label("HDR export is coming soon."))
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
        lookSheet("Volume", c, slider("Volume", 0f, 2f, c.volume, { "${(it * 100).toInt()}%" }) { c.volume = it; live() },
            label("Above 100% is applied when you export; the preview plays at most 100%."))
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

    private fun filtersDialog() = colorPanel(0)

    private fun adjustDialog() = colorPanel(1)

    private class Param(val name: String, val icon: Int, val min: Int, val max: Int, val get: () -> Float, val set: (Float) -> Unit)

    private fun filterMatrix(i: Int): android.graphics.ColorFilter? {
        val cm = android.graphics.ColorMatrix()
        when (i) {
            1 -> cm.setSaturation(1.5f)
            2 -> cm.setScale(1.12f, 1f, 0.85f, 1f)
            3 -> cm.setScale(0.88f, 1f, 1.15f, 1f)
            4 -> cm.setSaturation(0f)
            5 -> cm.set(floatArrayOf(.393f, .769f, .189f, 0f, 0f, .349f, .686f, .168f, 0f, 0f, .272f, .534f, .131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            6 -> cm.set(floatArrayOf(.8f, 0f, 0f, 0f, 34f, 0f, .8f, 0f, 0f, 34f, 0f, 0f, .8f, 0f, 34f, 0f, 0f, 0f, 1f, 0f))
            7 -> cm.set(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
            else -> return null
        }
        return android.graphics.ColorMatrixColorFilter(cm)
    }

    private val CYAN = 0xFF25F4EE.toInt()

    /** CapCut-style bottom panel with "Filters" and "Adjust" tabs. */
    private fun colorPanel(startTab: Int) {
        val c = need(true) ?: return
        project.checkpoint()
        val before = c.paramSig()
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val rbg = GradientDrawable()
        rbg.setColor(0xFF1E1E21.toInt())
        rbg.cornerRadii = floatArrayOf(dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), 0f, 0f, 0f, 0f)
        root.background = rbg

        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(14), 0, dp(10), 0)
        val content = FrameLayout(this)
        val tabViews = ArrayList<Pair<TextView, View>>()
        fun show(tab: Int) {
            tabViews.forEachIndexed { i, (t, u) ->
                t.setTextColor(if (i == tab) Color.WHITE else 0xFF8E8E96.toInt())
                u.visibility = if (i == tab) View.VISIBLE else View.INVISIBLE
            }
            content.removeAllViews()
            content.addView(if (tab == 0) filtersContent(c) else adjustContent(c))
        }
        listOf("Filters", "Adjust").forEachIndexed { i, name ->
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            col.setPadding(0, 0, dp(26), 0)
            val t = TextView(this)
            t.text = name
            t.textSize = 17f
            t.setTypeface(null, Typeface.BOLD)
            t.setPadding(0, dp(14), 0, dp(8))
            val u = View(this)
            u.setBackgroundColor(CYAN)
            col.addView(t)
            col.addView(u, LinearLayout.LayoutParams(dp(30), dp(2)))
            col.setOnClickListener { show(i) }
            tabViews.add(t to u)
            head.addView(col)
        }
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val ok = ImageView(this)
        ok.setImageResource(R.drawable.ic_check)
        ok.scaleType = ImageView.ScaleType.CENTER
        ok.setOnClickListener { d.dismiss() }
        head.addView(ok, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(head)
        val line = View(this)
        line.setBackgroundColor(0xFF343438.toInt())
        root.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(236)))
        d.setContentView(root)
        d.setOnDismissListener {
            if (c.paramSig() == before) project.discardCheckpoint()
            afterEdit()
        }
        d.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setDimAmount(0f)
        }
        show(startTab)
        d.show()
    }

    private fun filtersContent(c: Clip): View {
        val scroll = android.widget.ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.setPadding(dp(10), dp(10), dp(10), dp(10))
        val tiles = ArrayList<FrameLayout>()
        val thumbs = ArrayList<ImageView>()
        fun mark() {
            tiles.forEachIndexed { i, f ->
                val g = GradientDrawable()
                g.cornerRadius = dp(8).toFloat()
                g.setColor(Color.TRANSPARENT)
                if (i == c.filter) g.setStroke(dp(2), Color.WHITE)
                f.foreground = g
            }
        }
        var row: LinearLayout? = null
        Fx.FILTER_NAMES.forEachIndexed { i, name ->
            if (i % 4 == 0) {
                row = LinearLayout(this)
                grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)))
            }
            val f = FrameLayout(this)
            val bg = GradientDrawable()
            bg.cornerRadius = dp(8).toFloat()
            bg.setColor(0xFF3A3A3F.toInt())
            f.background = bg
            f.clipToOutline = true
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.colorFilter = filterMatrix(i)
            f.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            thumbs.add(iv)
            if (i == 0) {
                val none = TextView(this)
                none.text = "⊘"
                none.textSize = 30f
                none.gravity = Gravity.CENTER
                none.setTextColor(0xFFCCCCD2.toInt())
                f.addView(none, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val lab = TextView(this)
            lab.text = if (i == 0) "None" else name
            lab.textSize = 11f
            lab.gravity = Gravity.CENTER
            lab.setTextColor(0xFFF2F2F5.toInt())
            lab.setBackgroundColor(0x99000000.toInt())
            lab.setPadding(0, dp(3), 0, dp(3))
            f.addView(lab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            f.setOnClickListener { c.filter = i; mark(); live() }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            row!!.addView(f, lp)
            tiles.add(f)
        }
        // pad the last row so tiles keep their width
        val r = row
        if (r != null) while (r.childCount < 4) r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        mark()
        // preview picture of the clip, coloured by each filter
        val bmp = Thumbs.get(this, c.uri, c.srcInMs + max(0L, engine.playheadMs - c.startMs)) {
            val b2 = Thumbs.get(this, c.uri, c.srcInMs + max(0L, engine.playheadMs - c.startMs)) {}
            if (b2 != null) thumbs.forEachIndexed { i, v -> if (i > 0) v.setImageBitmap(b2) else v.setImageBitmap(b2) }
        }
        if (bmp != null) thumbs.forEach { it.setImageBitmap(bmp) }
        scroll.addView(grid)
        return scroll
    }

    private fun adjustContent(c: Clip): View {
        val params = listOf(
            Param("Brightness", R.drawable.ic_hdr, -100, 100, { c.brightness * 200f }, { c.brightness = it / 200f }),
            Param("Contrast", R.drawable.ic_adjust, -100, 100, { (c.contrast - 1f) * 200f }, { c.contrast = 1f + it / 200f }),
            Param("Saturation", R.drawable.ic_color, -100, 100, { (c.saturation - 1f) * 100f }, { c.saturation = 1f + it / 100f }),
            Param("Temperature", R.drawable.ic_effects, -100, 100, { c.temp * 100f }, { c.temp = it / 100f }),
            Param("Hue", R.drawable.ic_hsl, -100, 100, { (if (c.hue > 180f) c.hue - 360f else c.hue) / 1.8f }, { val h = it * 1.8f; c.hue = if (h < 0f) h + 360f else h }),
            Param("Lightness", R.drawable.ic_lut, -100, 100, { c.lum }, { c.lum = it }),
            Param("Blur", R.drawable.ic_blur, 0, 100, { c.blur * 4f }, { c.blur = it / 4f })
        )
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        var sel = 0
        // Smart / Customize switch
        val sw = LinearLayout(this)
        sw.setPadding(dp(18), dp(12), dp(18), dp(4))
        val body = FrameLayout(this)
        val swViews = ArrayList<TextView>()
        val valueText = TextView(this)
        val seek = SeekBar(this)
        val circleViews = ArrayList<Pair<LinearLayout, TextView>>()
        var seekBusy = false
        fun loadSeek() {
            val p = params[sel]
            seekBusy = true
            seek.max = p.max - p.min
            seek.progress = (p.get().toInt() - p.min).coerceIn(0, p.max - p.min)
            valueText.text = p.get().toInt().toString()
            seekBusy = false
            circleViews.forEachIndexed { i, (circ, lab) ->
                val g = GradientDrawable()
                g.shape = GradientDrawable.OVAL
                g.setColor(0xFF2B2B2F.toInt())
                g.setStroke(dp(1), if (i == sel) CYAN else 0xFF55555B.toInt())
                circ.background = g
                lab.setTextColor(if (i == sel) CYAN else 0xFFE6E6EA.toInt())
            }
        }
        fun customize(): View {
            val v = LinearLayout(this)
            v.orientation = LinearLayout.VERTICAL
            val hs = HorizontalScrollView(this)
            hs.isHorizontalScrollBarEnabled = false
            val row = LinearLayout(this)
            row.setPadding(dp(10), dp(6), dp(10), 0)
            circleViews.clear()
            params.forEachIndexed { i, p ->
                val col = LinearLayout(this)
                col.orientation = LinearLayout.VERTICAL
                col.gravity = Gravity.CENTER_HORIZONTAL
                col.setPadding(dp(7), 0, dp(7), 0)
                val circ = LinearLayout(this)
                circ.gravity = Gravity.CENTER
                val iv = ImageView(this)
                iv.setImageResource(p.icon)
                circ.addView(iv, LinearLayout.LayoutParams(dp(24), dp(24)))
                col.addView(circ, LinearLayout.LayoutParams(dp(48), dp(48)))
                val lab = TextView(this)
                lab.text = p.name
                lab.textSize = 11f
                lab.setPadding(0, dp(5), 0, 0)
                col.addView(lab)
                col.setOnClickListener { sel = i; loadSeek() }
                circleViews.add(circ to lab)
                row.addView(col)
            }
            hs.addView(row)
            v.addView(hs)
            valueText.setTextColor(Color.WHITE)
            valueText.textSize = 12f
            valueText.gravity = Gravity.CENTER
            (valueText.parent as? ViewGroup)?.removeView(valueText)
            v.addView(valueText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            (seek.parent as? ViewGroup)?.removeView(seek)
            seek.progressTintList = android.content.res.ColorStateList.valueOf(CYAN)
            seek.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, pr: Int, fromUser: Boolean) {
                    if (seekBusy || !fromUser) return
                    val p = params[sel]
                    val value = (pr + p.min).toFloat()
                    p.set(value)
                    valueText.text = value.toInt().toString()
                    live()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            v.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(24), 0, dp(24), 0) })
            val reset = TextView(this)
            reset.text = "↺  Reset"
            reset.textSize = 14f
            reset.setTextColor(0xFFE6E6EA.toInt())
            reset.setPadding(dp(18), dp(14), dp(18), dp(6))
            reset.setOnClickListener {
                val p = params[sel]
                p.set(if (p.min == 0) 0f else 0f)
                loadSeek(); live()
            }
            v.addView(reset)
            loadSeek()
            return v
        }
        fun smart(): View {
            val row = LinearLayout(this)
            row.setPadding(dp(14), dp(10), dp(14), 0)
            fun item(icon: Int, name: String, act: () -> Unit) {
                val col = LinearLayout(this)
                col.orientation = LinearLayout.VERTICAL
                col.gravity = Gravity.CENTER_HORIZONTAL
                col.setPadding(dp(8), 0, dp(8), 0)
                val circ = LinearLayout(this)
                circ.gravity = Gravity.CENTER
                val g = GradientDrawable()
                g.shape = GradientDrawable.OVAL
                g.setColor(0xFF2B2B2F.toInt())
                g.setStroke(dp(1), 0xFF55555B.toInt())
                circ.background = g
                val iv = ImageView(this)
                iv.setImageResource(icon)
                circ.addView(iv, LinearLayout.LayoutParams(dp(24), dp(24)))
                col.addView(circ, LinearLayout.LayoutParams(dp(48), dp(48)))
                val lab = TextView(this)
                lab.text = name
                lab.textSize = 11f
                lab.setTextColor(0xFFE6E6EA.toInt())
                lab.setPadding(0, dp(5), 0, 0)
                col.addView(lab)
                col.setOnClickListener { act() }
                row.addView(col)
            }
            item(R.drawable.ic_ai, "Auto adjust") {
                c.brightness = 0.03f; c.contrast = 1.1f; c.saturation = 1.15f
                live(); toast("Auto adjust applied — fine-tune it in Customize")
            }
            item(R.drawable.ic_lut, "Color match") { soon("Color match") }
            item(R.drawable.ic_color, "Color correction") { soon("Color correction") }
            return row
        }
        listOf("Smart", "Customize").forEachIndexed { i, name ->
            val t = TextView(this)
            t.text = name
            t.textSize = 16f
            t.setTypeface(null, Typeface.BOLD)
            t.setPadding(0, 0, dp(26), 0)
            t.setOnClickListener {
                swViews.forEachIndexed { j, v -> v.setTextColor(if (j == i) Color.WHITE else 0xFF8E8E96.toInt()) }
                body.removeAllViews()
                body.addView(if (i == 0) smart() else customize())
            }
            swViews.add(t)
            sw.addView(t)
        }
        box.addView(sw)
        box.addView(body)
        swViews[1].performClick()
        return box
    }

    private fun searchDialog() {
        val all = (mainTools() + clipTools(Clip(id = -1, kind = LaneKind.VIDEO))).distinctBy { it.name }
        val e = EditText(this)
        e.hint = "Search for features and assets here"
        e.setTextColor(Color.WHITE)
        e.setHintTextColor(0xFF8E8E96.toInt())
        e.setSingleLine()
        val results = LinearLayout(this)
        results.orientation = LinearLayout.VERTICAL
        var dlg: Dialog? = null
        fun fill(q: String) {
            results.removeAllViews()
            all.filter { q.isBlank() || it.name.contains(q, true) }.take(8).forEach { t ->
                val r = TextView(this)
                r.text = t.name
                r.textSize = 15f
                r.setTextColor(Color.WHITE)
                r.setPadding(dp(4), dp(11), 0, dp(11))
                r.setOnClickListener { dlg?.dismiss(); t.act() }
                results.addView(r)
            }
        }
        e.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { fill(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c1: Int) {}
        })
        fill("")
        dlg = sheet("Search", e, results)
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
            chips(GlFxNames.EFFECTS, c.fx) { c.fx = it; live() },
            slider("Intensity", 0f, 1f, c.fxAmount, { "%d".format((it * 100).toInt()) }) { c.fxAmount = it; live() },
            slider("Blur", 0f, 25f, c.blur, { "%.0f".format(it) }) { c.blur = it; live() })
    }

    private fun maskDialog() {
        val c = need(true) ?: return
        lookSheet("Mask", c,
            chips(GlFxNames.MASKS, c.maskShape) { c.maskShape = it; live() },
            slider("Size", 0.1f, 2f, c.maskSize, { "%d".format((it * 100).toInt()) }) { c.maskSize = it; live() },
            slider("Feather", 0f, 1f, c.maskFeather, { "%d".format((it * 100).toInt()) }) { c.maskFeather = it; live() },
            slider("Position X", -1f, 1f, c.maskX, { "%+d".format((it * 100).toInt()) }) { c.maskX = it; live() },
            slider("Position Y", -1f, 1f, c.maskY, { "%+d".format((it * 100).toInt()) }) { c.maskY = it; live() },
            chips(listOf("Show inside", "Show outside"), if (c.maskInvert) 1 else 0) { c.maskInvert = it == 1; live() },
            label("Everything outside the shape turns black."))
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
        sheet("GatsMod Cut", label("Version ${versionName()}"), button("Check for updates") { updater.check(false) },
            button("Project info") { projectDialog() })
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        val vis = if (fullscreen) View.GONE else View.VISIBLE
        listOf(R.id.topBar, R.id.timelineBlock, R.id.toolbarScroll).forEach { findViewById<View>(it).visibility = vis }
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

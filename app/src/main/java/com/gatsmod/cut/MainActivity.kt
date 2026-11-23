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
import android.widget.ScrollView
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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import java.io.File
import kotlin.math.abs
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
        EditorTheme.apply(this)
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
        overlayView.onDelete = { engine.pause(); result(project.delete()) }
        var gScale = 1f; var gDx = 0f; var gDy = 0f; var gRot = 0f
        overlayView.onVideoGestureStart = {
            gScale = 1f; gDx = 0f; gDy = 0f; gRot = 0f
            engine.pause()
            project.checkpoint()
        }
        overlayView.onAutoSelect = { syncUi() }
        overlayView.onVideoGesture = { ts, dx, dy, dr ->
            gScale = ts; gDx = dx; gDy = dy; gRot = dr
            pv.scaleX = gScale; pv.scaleY = gScale; pv.translationX = gDx; pv.translationY = gDy; pv.rotation = gRot
        }
        overlayView.onVideoGestureEnd = {
            pv.scaleX = 1f; pv.scaleY = 1f; pv.translationX = 0f; pv.translationY = 0f; pv.rotation = 0f
            val c = project.selected()
            val moved = abs(gDx) + abs(gDy) > dp(4) || abs(gScale - 1f) > 0.02f || abs(gRot) > 1f
            if (c == null || c.kind != LaneKind.VIDEO || !moved) project.discardCheckpoint()
            else {
                val rc = overlayView.canvasRect()
                val local = (engine.playheadMs - c.startMs).coerceAtLeast(0L)
                val cur = if (c.keyframes.isEmpty()) Keyframe(0) else Keyframes.valueAt(c.keyframes, local)
                val k = Keyframe(
                    if (c.keyframes.size <= 1) 0L else local,
                    (cur.scale * gScale).coerceIn(0.1f, 8f),
                    cur.x + 2f * gDx / max(1f, rc.width()),
                    cur.y - 2f * gDy / max(1f, rc.height()),
                    cur.rot + gRot
                )
                if (c.keyframes.size <= 1) c.keyframes = mutableListOf(k) else project.setKeyframe(c, k)
                live()
                afterEdit()
            }
        }

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
        timeline.onAdd = { k -> when (k) { LaneKind.AUDIO -> audioPicker.launch(arrayOf("audio/*")); LaneKind.OVERLAY -> imagePicker.launch(arrayOf("image/*")); else -> textDialog() } }
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
                dropMissingMedia()
                engine.refresh()
                if (!project.isEmpty()) engine.seekTo(0)
            }
        } else if (intent.getBooleanExtra("pick", false)) {
            ui.postDelayed({ pickVideo() }, 250)
        }
        syncUi()
        tickLoop()
    }

    /** Removes clips whose source file can no longer be opened (e.g. access to a picked video was lost). */
    private fun dropMissingMedia() {
        fun ok(c: Clip): Boolean {
            if (!c.isMedia || c.uri.isEmpty()) return true
            val u = Uri.parse(c.uri)
            return try {
                if (u.scheme == "file") File(u.path ?: "").exists()
                else contentResolver.openFileDescriptor(u, "r")?.use { true } ?: false
            } catch (e: Exception) { false }
        }
        val bad = (project.main + project.audio).filter { !ok(it) }
        if (bad.isEmpty()) return
        project.main.removeAll(bad.toSet()); project.audio.removeAll(bad.toSet())
        project.relayout()
        Toast.makeText(this, "${bad.size} clip(s) were removed because their original video can no longer be opened. Import the video again.", Toast.LENGTH_LONG).show()
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

    /** Photo-picker links stop working after the app is closed, so a video whose access cannot be kept is copied into the app. */
    private fun keepAccess(u: Uri, name: String): Uri {
        persist(u)
        if (contentResolver.persistedUriPermissions.any { it.uri == u }) return u
        return try {
            val dir = File(filesDir, "media"); dir.mkdirs()
            val ext = name.substringAfterLast('.', "mp4").take(5).filter { it.isLetterOrDigit() }.ifEmpty { "mp4" }
            val f = File(dir, "V_" + System.currentTimeMillis() + "." + ext)
            contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o, 256 * 1024) } } ?: return u
            Uri.fromFile(f)
        } catch (e: Exception) { u }
    }

    private fun loadVideo(src: Uri) {
        toast("Importing video…")
        Thread {
            val name0 = MediaProbe.displayName(this, src)
            val u = keepAccess(src, name0)
            val info = MediaProbe.probe(this, u)
            val name = name0
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
    private fun sheet(title: String, vararg views: View, onClose: (() -> Unit)? = null, showHeader: Boolean = true, heightPx: Int = 0): Dialog {
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
        if (showHeader) root.addView(head)
        views.forEach { root.addView(it) }
        d.setContentView(root)
        d.setOnDismissListener { onClose?.invoke() }
        d.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, if (heightPx > 0) heightPx else WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setDimAmount(0.0f)
            // tapping or pinching the video above the panel must not close the tool
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
        d.setCanceledOnTouchOutside(false)
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
        l.layoutParams = LinearLayout.LayoutParams(if (back) dp(46) else dp(60), ViewGroup.LayoutParams.MATCH_PARENT)
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
        T(R.drawable.ic_text, "Text") { textDialog() },
        T(R.drawable.ic_effects, "Effects") { effectsDialog() },
        T(R.drawable.ic_overlay, "Overlay") { if (project.isEmpty()) toast("Import a video first") else imagePicker.launch(arrayOf("image/*")) },
        T(R.drawable.ic_captions, "Captions") { srtPicker.launch(arrayOf("*/*")) },
        T(R.drawable.ic_animation, "Animations") { animationDialog() },
        T(R.drawable.ic_mic, "Voiceover") { startVoiceover() },
        T(R.drawable.ic_filters, "Filters") { filtersDialog() },
        T(R.drawable.ic_adjust, "Adjust") { adjustDialog() },
        T(R.drawable.ic_stickers, "Stickers") { stickerDialog() },
        T(R.drawable.ic_canvas, "Canvas") { canvasDialog() },
        T(R.drawable.ic_autocap, "Auto captions") { autoCaptionsDialog() },
        T(R.drawable.ic_star, "Templates") { templatesDialog() }
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
                T(R.drawable.ic_noise, "Denoise") { denoiseDialog() },
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
                T(R.drawable.ic_speed, "Beats") { beatsDialog() },
                trimS, trimE,
                T(R.drawable.ic_merge, "Merge") { engine.pause(); result(project.merge()) },
                dup,
                T(R.drawable.ic_frameback, "Frame back") { step(-1) },
                T(R.drawable.ic_frameforward, "Frame next") { step(1) },
                T(R.drawable.ic_color, "Reset look") { resetLook() },
                T(R.drawable.ic_help, "Info") { clipInfo() },
                T(R.drawable.ic_reverse, "Reverse") { reverseClip() },
                T(R.drawable.ic_freeze, "Freeze") { freezeFrame() },
                T(R.drawable.ic_mask, "Mask") { maskDialog() },
                T(R.drawable.ic_removebg, "Remove BG") { removeBgDialog() },
                T(R.drawable.ic_stabilize, "Stabilize") { stabilizeDialog() }
            )
            LaneKind.AUDIO -> listOf(
                split,
                T(R.drawable.ic_volume, "Volume") { volumeDialog() },
                T(R.drawable.ic_noise, "Denoise") { denoiseDialog() },
                T(R.drawable.ic_fade, "Fade") { fadeDialog() },
                T(R.drawable.ic_speed, "Speed") { speedDialog() },
                T(R.drawable.ic_speed, "Beats") { beatsDialog() },
                trimS, trimE, dup, del
            )
            LaneKind.TEXT -> listOf(
                T(R.drawable.ic_text, "Edit") { textDialog() },
                T(R.drawable.ic_animation, "Animations") { animationsFor(c) },
                split, dup, del
            )
            LaneKind.OVERLAY -> listOf(
                T(R.drawable.ic_resize, "Size") { overlaySizeDialog() },
                T(R.drawable.ic_animation, "Animations") { animationsFor(c) },
                split, dup, del
            )
        }
    }

    private fun beatsDialog() {
        val c = need() ?: return
        if (!c.isMedia || (c.kind == LaneKind.VIDEO && !c.hasAudio)) { toast("Beats need a clip with sound"); return }
        val id = c.id
        val info = TextView(this).apply { setTextColor(0xFFDAD7EA.toInt()); textSize = 14f }
        fun refresh() {
            val cur = project.byId(id) ?: return
            val n = project.beatTimes(cur).size
            info.text = if (cur.beats.isEmpty()) "No beats found yet. Tap Detect to listen to this clip."
                        else "$n beats in the used part (yellow marks on the clip)."
        }
        refresh()
        var dlg: Dialog? = null
        val detect = button("Detect beats") {
            dlg?.dismiss()
            val cur = project.byId(id) ?: return@button
            val uri = cur.uri; val a = cur.srcInMs; val b = cur.srcOutMs
            runWork("Finding beats") { progress ->
                val res = BeatDecode.detect(this, uri, a, b, progress) ?: return@runWork null
                return@runWork {
                    project.byId(id)?.let { cl ->
                        project.checkpoint()
                        cl.beats = cl.beats.filter { it < a || it >= b } + res
                        afterEdit()
                        toast(if (res.isEmpty()) "No clear beats found in this clip" else "${res.size} beats found")
                    }
                }
            }
        }
        val prev = button("◀ Previous beat") { jumpBeat(id, -1) }
        val next = button("Next beat ▶") { jumpBeat(id, 1) }
        val cut = button("Cut video at every beat") {
            val cur = project.byId(id) ?: return@button
            val times = project.beatTimes(cur)
            if (times.isEmpty()) { toast("Detect beats first"); return@button }
            engine.pause()
            // audio-lane beats cut the main video track; video-clip beats cut that clip
            result(project.splitAll(times))
            dlg?.dismiss()
        }
        val every2 = button("Cut at every 2nd beat") {
            val cur = project.byId(id) ?: return@button
            val times = project.beatTimes(cur).filterIndexed { i, _ -> i % 2 == 1 }
            if (times.isEmpty()) { toast("Detect beats first"); return@button }
            engine.pause()
            result(project.splitAll(times))
            dlg?.dismiss()
        }
        val clear = button("Clear beats") {
            project.byId(id)?.let { project.checkpoint(); it.beats = emptyList(); afterEdit(); refresh() }
        }
        dlg = sheet("Beats", info, detect, prev, next, cut, every2, clear,
            label("Beats are found by listening for sudden loud hits, so it works best on music with a clear drum. Cutting on beats splits the video track."))
    }

    private fun jumpBeat(id: Long, dir: Int) {
        val c = project.byId(id) ?: return
        val times = project.beatTimes(c)
        val now = engine.playheadMs
        val t = if (dir > 0) times.firstOrNull { it > now + 30 } else times.lastOrNull { it < now - 30 }
        if (t == null) { toast("No more beats that way"); return }
        engine.pause(); engine.seekTo(t); syncPlayhead()
    }

    private fun stabilizeDialog() {
        val c = need(true) ?: return
        val id = c.id
        val srcLen = c.srcOutMs - c.srcInMs
        val info = TextView(this).apply { setTextColor(0xFFDAD7EA.toInt()); textSize = 14f }
        info.text = if (c.stab.isEmpty()) "Not stabilized yet." else "Stabilized. You can analyze again with another strength."
        var level = 1
        var dlg: Dialog? = null
        val lv = chips(listOf("Light", "Normal", "Strong"), level) { level = it }
        val go = button(if (c.stab.isEmpty()) "Analyze & stabilize" else "Analyze again") {
            if (srcLen > 60000) { toast("Stabilize works on clips up to 60 s — cut the clip shorter first"); return@button }
            dlg?.dismiss()
            val cur = project.byId(id) ?: return@button
            val uri = cur.uri; val a = cur.srcInMs; val b = cur.srcOutMs
            val window = intArrayOf(8, 15, 30)[level]
            runWork("Stabilizing") { progress ->
                val r = FrameVideo.openRetriever(this, uri) ?: return@runWork null
                val step = 66L
                val n = ((b - a) / step).toInt().coerceAtLeast(3)
                var prev: IntArray? = null
                var w = 0; var h = 0
                val moves = ArrayList<FloatArray>()
                for (i in 0 until n) {
                    val bmp = FrameVideo.frameAt(r, a + i * step, 160)
                    if (bmp == null) { if (prev != null) moves.add(floatArrayOf(0f, 0f)); continue }
                    w = bmp.width; h = bmp.height
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    bmp.recycle()
                    val g = IntArray(w * h) { val p = px[it]; (((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10 }
                    val pv = prev
                    if (pv != null) {
                        val s = StabMath.shift(pv, g, w, h, max(4, w / 10))
                        moves.add(floatArrayOf(s[0] / w, s[1] / h))
                    }
                    prev = g
                    if (!progress(i * 100 / n)) { try { r.release() } catch (_: Exception) {}; return@runWork null }
                }
                try { r.release() } catch (_: Exception) {}
                if (moves.size < 3) return@runWork null
                val keys = StabMath.plan(moves, step, window).map { it.copy(tMs = a + it.tMs) }
                return@runWork {
                    project.byId(id)?.let { cl ->
                        project.checkpoint()
                        cl.stab = keys
                        afterEdit()
                        toast("Stabilized — the picture is zoomed in slightly to hide the edges")
                    }
                }
            }
        }
        val off = button("Remove stabilization") {
            project.byId(id)?.let { project.checkpoint(); it.stab = emptyList(); afterEdit(); toast("Stabilization removed") }
            dlg?.dismiss()
        }
        dlg = sheet("Stabilize", info, label("Strength"), lv, go, off,
            label("Measures how the picture shakes (up/down/left/right only, no rotation) and cancels it, with a small zoom to hide the edges. It is a simple method, not AI: it works best on handheld shots with a detailed background, and not when something big moves in front of the camera. Analysis takes a while on long clips."))
    }

    private fun denoiseDialog() {
        val c = need() ?: return
        if (!c.isMedia) { toast("Noise reduction works on video and audio clips"); return }
        lookSheet("Noise reduction", c,
            slider("Strength", 0f, 1f, c.denoise, { "%d%%".format((it * 100).toInt()) }) { c.denoise = it; live() },
            label("Quiets steady background hiss and hum between words and removes low rumble. It is a simple filter, not AI, so very high strength can make speech sound slightly choppy."))
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

    // ============================================================ reverse / freeze

    private var workCancel = false

    /** Shows a progress sheet and runs [work] on a background thread. [work] calls progress(0..100) and returns the result action. */
    private fun runWork(title: String, work: (progress: (Int) -> Boolean) -> (() -> Unit)?) {
        engine.pause()
        workCancel = false
        val status = TextView(this)
        status.setTextColor(0xFFDAD7EA.toInt())
        status.textSize = 14f
        status.text = "Working… 0%"
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        val cancel = button("Cancel") { workCancel = true }
        val dlg = sheet(title, status, bar, cancel)
        dlg.setCancelable(false)
        dlg.setCanceledOnTouchOutside(false)
        Thread {
            val done = try {
                work { p ->
                    runOnUiThread { bar.progress = p; status.text = "Working… $p%" }
                    !workCancel
                }
            } catch (e: Throwable) { null }
            runOnUiThread {
                dlg.dismiss()
                if (done != null) done() else if (workCancel) toast("Cancelled") else toast("Could not finish this on your phone")
            }
        }.start()
    }

    private fun reverseClip() {
        val c = need(true) ?: return
        val srcLen = c.srcOutMs - c.srcInMs
        if (srcLen > 20000) { toast("Reverse works on clips up to 20 s — cut the clip shorter first"); return }
        val inMs = c.srcInMs
        val outMs = c.srcOutMs
        val uri = c.uri
        val id = c.id
        runWork("Reversing clip") { progress ->
            val r = FrameVideo.openRetriever(this, uri) ?: return@runWork null
            val fps = 30
            val n = max(1, (srcLen * fps / 1000L).toInt())
            val out = File(File(filesDir, "reversed"), "R_" + System.currentTimeMillis() + ".mp4")
            val res = FrameVideo.write(out, fps, n, 6_000_000, { i ->
                val t = (outMs - (i + 0.5f) * 1000f / fps).toLong().coerceIn(inMs, outMs - 1)
                FrameVideo.frameAt(r, t, 1280)
            }, progress)
            try { r.release() } catch (_: Exception) {}
            if (res == null) return@runWork null
            return@runWork {
                val cur = project.byId(id)
                if (cur != null) {
                    project.checkpoint()
                    cur.uri = Uri.fromFile(res.file).toString()
                    cur.srcInMs = 0
                    cur.srcOutMs = res.durationMs
                    cur.srcTotalMs = res.durationMs
                    cur.hasAudio = false
                    cur.stab = emptyList()
                    cur.label = "Reversed"
                    cur.keyframes = cur.keyframes.map { it.copy(tMs = max(0L, cur.lengthMs - it.tMs)) }.sortedBy { it.tMs }.toMutableList()
                    project.relayout()
                    engine.pause()
                    afterEdit()
                    toast("Clip reversed (the reversed clip has no sound)")
                }
            }
        }
    }

    private fun removeBgDialog() {
        val c = need(true) ?: return
        val srcLen = c.srcOutMs - c.srcInMs
        val names = listOf("Blur", "Black", "White", "Green", "Blue", "Grey")
        val cols = intArrayOf(0, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF00B140.toInt(), 0xFF1E5BFF.toInt(), 0xFF808080.toInt())
        var pick = 0
        var dlg: Dialog? = null
        val chipRow = chips(names, 0) { pick = it }
        val go = button("Remove background") {
            if (srcLen > 20000) { toast("Remove BG works on clips up to 20 s — cut the clip shorter first"); return@button }
            dlg?.dismiss()
            val id = c.id
            val uri = c.uri; val inMs = c.srcInMs; val outMs = c.srcOutMs
            val blur = pick == 0; val color = cols[pick]
            val oldCopy = c.deepCopy(project.newId())
            runWork("Removing background") { progress ->
                val r = FrameVideo.openRetriever(this, uri) ?: return@runWork null
                val seg = RemoveBg(blur, color)
                val fps = 30
                val n = max(1, (srcLen * fps / 1000L).toInt())
                val out = File(File(filesDir, "reversed"), "B_" + System.currentTimeMillis() + ".mp4")
                var last: Bitmap? = null
                val res = FrameVideo.write(out, fps, n, 6_000_000, { i ->
                    val t = (inMs + (i + 0.5f) * 1000f / fps).toLong().coerceIn(inMs, outMs - 1)
                    val f = FrameVideo.frameAt(r, t, 960) ?: return@write last
                    val o = seg.process(f)
                    f.recycle()
                    if (o != null) last = o
                    o ?: last
                }, progress)
                seg.close()
                try { r.release() } catch (_: Exception) {}
                if (res == null) return@runWork null
                return@runWork {
                    val cur = project.byId(id)
                    if (cur != null) {
                        project.checkpoint()
                        if (oldCopy.hasAudio) {
                            // keep the original sound on its own audio track
                            oldCopy.kind = LaneKind.AUDIO
                            oldCopy.keyframes = mutableListOf()
                            oldCopy.stab = emptyList()
                            oldCopy.beats = emptyList()
                            oldCopy.startMs = cur.startMs
                            project.audio.add(oldCopy)
                        }
                        cur.uri = Uri.fromFile(res.file).toString()
                        cur.srcInMs = 0
                        cur.srcOutMs = res.durationMs
                        cur.srcTotalMs = res.durationMs
                        cur.hasAudio = false
                        cur.stab = emptyList()
                        cur.beats = emptyList()
                        cur.label = "No BG"
                        project.relayout()
                        engine.pause()
                        afterEdit()
                        toast("Background replaced" + if (oldCopy.hasAudio) " — the original sound is on the audio track" else "")
                    }
                }
            }
        }
        dlg = sheet("Remove BG", label("New background"), chipRow, go,
            label("Finds the person in every frame and replaces everything behind them with a blur or a plain colour. Works best on one person filmed from the chest up (like a selfie) with a clearly different background. It is a simple on-device model: hair and hands can have rough edges, and it will not cut out objects or animals. Works on clips up to 20 s and takes a while. A new video is made, so you can Undo."))
    }

    private fun freezeFrame() {
        val c = need(true) ?: return
        val ph = engine.playheadMs
        if (ph <= c.startMs + Project.MIN_MS || ph >= c.endMs - Project.MIN_MS) { toast("Move the playhead inside the clip to freeze a frame"); return }
        val srcMs = c.srcInMs + ((ph - c.startMs) * c.speed).toLong()
        val uri = c.uri
        val id = c.id
        runWork("Freezing frame") { progress ->
            val r = FrameVideo.openRetriever(this, uri) ?: return@runWork null
            val bmp = FrameVideo.frameAt(r, srcMs, 1920)
            try { r.release() } catch (_: Exception) {}
            if (bmp == null) return@runWork null
            val fps = 30
            val n = 3 * fps
            val out = File(File(filesDir, "freeze"), "F_" + System.currentTimeMillis() + ".mp4")
            val res = FrameVideo.write(out, fps, n, 4_000_000, { i -> if (i == 0) bmp else null }, progress)
            if (res == null) return@runWork null
            return@runWork {
                val cur = project.byId(id)
                if (cur != null) {
                    project.selectedId = cur.id
                    val err = project.split(ph)
                    if (err != null) toast(err) else {
                        val fz = Clip(
                            id = project.newId(), kind = LaneKind.VIDEO, uri = Uri.fromFile(res.file).toString(),
                            srcInMs = 0, srcOutMs = res.durationMs, srcTotalMs = res.durationMs, label = "Freeze",
                            width = if (cur.rotation % 180 != 0) cur.height else cur.width, height = if (cur.rotation % 180 != 0) cur.width else cur.height,
                            hasAudio = false, fps = fps.toFloat()
                        )
                        val idx = project.main.indexOf(cur) + 1
                        project.main.add(idx, fz)
                        project.selectedId = fz.id
                        project.relayout()
                        engine.pause()
                        afterEdit()
                        toast("Frozen for 3 seconds — trim it to change the length")
                    }
                }
            }
        }
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

    // ============================================================ templates

    private class Tpl(val name: String, val desc: String, val c1: Int, val c2: Int, val apply: (Clip) -> Unit)

    private fun clearLook(c: Clip) {
        val d = Clip(id = c.id, kind = c.kind)
        c.filter = d.filter; c.brightness = d.brightness; c.contrast = d.contrast; c.saturation = d.saturation
        c.blur = d.blur; c.temp = d.temp; c.hue = d.hue; c.lum = d.lum
        c.chroma = Chroma(); c.fx = 0; c.fxAmount = 0.5f; c.maskShape = 0
    }

    private fun templatesDialog() {
        if (project.isEmpty()) { toast("Import a video first"); return }
        val list = listOf(
            Tpl("Cinematic", "Moody contrast, cool tone, dark corners", 0xFF1F3A4D.toInt(), 0xFF0B1620.toInt()) { c ->
                c.contrast = 1.15f; c.saturation = 0.9f; c.temp = -0.12f; c.brightness = -0.02f; c.fx = 1; c.fxAmount = 0.55f },
            Tpl("Vintage", "Sepia colours with film grain", 0xFF8B6B3E.toInt(), 0xFF3E2C14.toInt()) { c ->
                c.filter = 5; c.contrast = 1.05f; c.fx = 5; c.fxAmount = 0.45f },
            Tpl("Vlog bright", "Warm, bright and sharp", 0xFFFFB74D.toInt(), 0xFFE65100.toInt()) { c ->
                c.brightness = 0.04f; c.contrast = 1.08f; c.saturation = 1.2f; c.temp = 0.15f; c.fx = 2; c.fxAmount = 0.4f },
            Tpl("Noir", "Black and white drama", 0xFF616161.toInt(), 0xFF111111.toInt()) { c ->
                c.filter = 4; c.contrast = 1.25f; c.fx = 1; c.fxAmount = 0.6f },
            Tpl("Dreamy", "Soft glow and light colours", 0xFFF48FB1.toInt(), 0xFF7E57C2.toInt()) { c ->
                c.fx = 8; c.fxAmount = 0.6f; c.saturation = 1.1f; c.brightness = 0.03f },
            Tpl("Retro VHS", "Glitchy tape look", 0xFF26C6DA.toInt(), 0xFF6A1B9A.toInt()) { c ->
                c.fx = 4; c.fxAmount = 0.25f; c.saturation = 1.25f; c.temp = 0.1f },
            Tpl("Neon pop", "Vivid colours, punchy contrast", 0xFFFF4081.toInt(), 0xFF00E5FF.toInt()) { c ->
                c.filter = 1; c.contrast = 1.15f; c.saturation = 1.5f; c.fx = 2; c.fxAmount = 0.3f },
            Tpl("Clean sharp", "Crisp and natural", 0xFF90A4AE.toInt(), 0xFF455A64.toInt()) { c ->
                c.fx = 2; c.fxAmount = 0.6f; c.contrast = 1.05f; c.saturation = 1.1f }
        )
        var addTitle = false
        var dlg: Dialog? = null
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        var row: LinearLayout? = null
        list.forEachIndexed { i, t ->
            if (i % 2 == 0) {
                row = LinearLayout(this)
                grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)))
            }
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.gravity = Gravity.BOTTOM
            card.setPadding(dp(10), dp(8), dp(10), dp(8))
            val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.c1, t.c2))
            g.cornerRadius = dp(10).toFloat()
            card.background = g
            val n = TextView(this)
            n.text = t.name
            n.textSize = 15f
            n.setTypeface(null, Typeface.BOLD)
            n.setTextColor(Color.WHITE)
            val d = TextView(this)
            d.text = t.desc
            d.textSize = 10f
            d.setTextColor(0xFFEDEDF2.toInt())
            card.addView(n)
            card.addView(d)
            card.setOnClickListener {
                engine.pause()
                project.checkpoint()
                for (c in project.main) { clearLook(c); t.apply(c); c.fadeInMs = 0; c.fadeOutMs = 0 }
                project.main.firstOrNull()?.let { it.fadeInMs = min(600L, it.lengthMs / 3) }
                project.main.lastOrNull()?.let { it.fadeOutMs = min(800L, it.lengthMs / 3) }
                if (addTitle && project.text.none { it.label == "Title" }) {
                    project.text.add(Clip(id = project.newId(), kind = LaneKind.TEXT, startMs = 0, text = "My Video", label = "Title",
                        durMs = min(3000L, project.totalMs()), sizeFrac = 0.09f, posY = 0.5f))
                }
                afterEdit()
                toast("${t.name} applied to ${project.main.size} clip(s)")
                dlg?.dismiss()
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            row!!.addView(card, lp)
        }
        dlg = sheet("Templates", chips(listOf("No title", "Add title"), 0) { addTitle = it == 1 }, grid,
            label("A template sets the look of every clip and fades the start and end. Undo brings your old look back."))
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
            item(R.drawable.ic_ai, "Auto adjust") { analyzeColors(c, 0) }
            item(R.drawable.ic_lut, "Color match") { analyzeColors(c, 2) }
            item(R.drawable.ic_color, "Color correction") { analyzeColors(c, 1) }
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

    /** mode 0 = auto adjust, 1 = color correction, 2 = match the neighbouring clip. */
    private fun analyzeColors(c: Clip, mode: Int) {
        val ref = if (mode == 2) project.main.let { l -> val i = l.indexOf(c); l.getOrNull(i - 1) ?: l.getOrNull(i + 1) } else null
        if (mode == 2 && ref == null) { toast("Add another clip to match colours with"); return }
        toast("Looking at the picture…")
        Thread {
            fun grab(k: Clip): ColorMath.Stats? {
                val r = FrameVideo.openRetriever(this, k.uri) ?: return null
                try {
                    val bmp = FrameVideo.frameAt(r, k.srcInMs + (k.srcOutMs - k.srcInMs) / 2, 320) ?: return null
                    return ColorMath.stats(bmp)
                } finally { try { r.release() } catch (_: Exception) {} }
            }
            val s = grab(c)
            val rs = if (ref != null) grab(ref) else null
            runOnUiThread {
                if (s == null || (ref != null && rs == null)) { toast("Could not read the picture"); return@runOnUiThread }
                when (mode) { 0 -> ColorMath.auto(c, s, true); 1 -> ColorMath.auto(c, s, false); else -> ColorMath.match(c, s, rs!!) }
                live()
                toast("Applied — fine-tune it in Customize")
            }
        }.start()
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
        engine.pause()
        val uri = c.uri; val at = c.srcInMs + min(400L, (c.srcOutMs - c.srcInMs) / 2)
        Thread {
            var pic: Bitmap? = null
            try {
                val r = FrameVideo.openRetriever(this, uri)
                val f = r?.let { FrameVideo.frameAt(it, at, 240) }
                try { r?.release() } catch (_: Exception) {}
                if (f != null) {
                    val side = min(f.width, f.height)
                    pic = Bitmap.createBitmap(f, (f.width - side) / 2, (f.height - side) / 2, side, side)
                }
            } catch (_: Throwable) {}
            runOnUiThread { showAnimationPanel(c, pic) }
        }.start()
    }

    private fun showAnimationPanel(c: Clip, pic: Bitmap?) {
        if (project.byId(c.id) == null) return
        project.checkpoint()
        val before = c.paramSig()
        var tab = 0
        var cat = 0
        var query = ""
        val white = 0xFFFFFFFF.toInt()
        val grey = 0xFF8E8E93.toInt()
        val tileW = (resources.displayMetrics.widthPixels - dp(24)) / 4
        var dlg: Dialog? = null

        // the panel starts right under the play button row, like CapCut's
        val loc = IntArray(2)
        controlsRow().getLocationOnScreen(loc)
        val content = findViewById<View>(android.R.id.content)
        val cl = IntArray(2); content.getLocationOnScreen(cl)
        val panelH = (cl[1] + content.height - (loc[1] + controlsRow().height)).coerceIn(dp(260), dp(520))

        val grid = android.widget.GridLayout(this)
        grid.columnCount = 4
        val sliderBox = LinearLayout(this)
        sliderBox.orientation = LinearLayout.VERTICAL
        sliderBox.setPadding(dp(8), 0, dp(8), 0)
        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        inner.addView(sliderBox)
        inner.addView(grid)
        val scroll = ScrollView(this)
        scroll.addView(inner)
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

        fun selectedId() = when (tab) { 0 -> c.animIn; 1 -> c.animOut; else -> c.animCombo }

        fun previewIt() {
            ui.postDelayed({
                val t = when (tab) { 1 -> max(c.startMs, c.endMs - c.animOutMs - 400); else -> c.startMs }
                engine.seekTo(t); syncPlayhead(); engine.play()
            }, 650)
        }

        fun refreshSlider() {
            sliderBox.removeAllViews()
            if (tab == 2 || selectedId() == 0) return
            val maxMs = min(3000f, c.lengthMs * 0.9f).coerceAtLeast(300f)
            val cur = (if (tab == 0) c.animInMs else c.animOutMs).toFloat().coerceIn(100f, maxMs)
            sliderBox.addView(slider("Duration", 100f, maxMs, cur, { "%.1fs".format(it / 1000f) }) {
                if (tab == 0) c.animInMs = it.toLong() else c.animOutMs = it.toLong()
                live()
            })
        }

        fun items(): List<Pair<Int, AnimDef>> {
            val src: List<Pair<Int, AnimDef>> = when (tab) {
                0 -> Anim.IN.map { it.id to it }
                1 -> Anim.OUT.map { it.def.id to it.def }
                else -> Anim.COMBO.map { it.id to it }
            }
            if (query.isNotBlank()) return src.filter { it.second.name.contains(query.trim(), ignoreCase = true) && (c.kind == LaneKind.VIDEO || !Anim.isMask(it.first)) }
            val key = Anim.CATS[cat]
            return src.filter { key in it.second.cats.split(",") && (c.kind == LaneKind.VIDEO || !Anim.isMask(it.first)) }
        }

        fun tile(id: Int, name: String): View {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER_HORIZONTAL
            box.setPadding(dp(6), dp(6), dp(6), dp(4))
            val on = selectedId() == id
            val side = tileW - dp(12)
            val holder = FrameLayout(this)
            val bg = android.graphics.drawable.GradientDrawable()
            bg.cornerRadius = dp(10).toFloat()
            bg.setColor(if (id == 0) 0xFF3A3A3F.toInt() else 0xFF26262B.toInt())
            if (on) bg.setStroke(dp(2), white)
            holder.background = bg
            holder.layoutParams = LinearLayout.LayoutParams(side, side)
            if (id == 0) {
                val no = ImageView(this)
                no.setImageResource(R.drawable.ic_close)
                no.alpha = 0.7f
                no.setPadding(side / 3, side / 3, side / 3, side / 3)
                holder.addView(no, FrameLayout.LayoutParams(side, side))
            } else {
                val inset = if (on) dp(2) else 0
                val tv = AnimTile(this, pic, tab, id, dp(10).toFloat())
                holder.addView(tv, FrameLayout.LayoutParams(side - inset * 2, side - inset * 2).apply { setMargins(inset, inset, inset, inset) })
            }
            val nm = TextView(this)
            nm.text = name
            nm.textSize = 12f
            nm.maxLines = 1
            nm.ellipsize = android.text.TextUtils.TruncateAt.END
            nm.gravity = Gravity.CENTER
            nm.setTextColor(if (on) white else grey)
            nm.setPadding(0, dp(5), 0, 0)
            box.addView(holder)
            box.addView(nm, LinearLayout.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT))
            box.layoutParams = android.widget.GridLayout.LayoutParams().apply { width = tileW; height = ViewGroup.LayoutParams.WRAP_CONTENT }
            box.setOnClickListener {
                engine.pause()
                when (tab) { 0 -> c.animIn = id; 1 -> c.animOut = id; else -> c.animCombo = id }
                live()
                refreshGridHolder?.invoke()
                if (id != 0) previewIt()
            }
            return box
        }

        fun fillGrid() {
            grid.removeAllViews()
            grid.addView(tile(0, "None"))
            for ((id, d) in items()) grid.addView(tile(id, d.name))
            refreshSlider()
        }
        refreshGridHolder = ::fillGrid

        val catRow = LinearLayout(this)
        catRow.orientation = LinearLayout.HORIZONTAL
        catRow.setPadding(dp(8), dp(6), 0, dp(6))
        val catViews = ArrayList<TextView>()
        Anim.CATS.forEachIndexed { i, n ->
            val t = TextView(this)
            t.text = n
            t.textSize = 17f
            t.setPadding(dp(10), dp(6), dp(14), dp(6))
            t.setTextColor(if (i == cat) white else grey)
            t.setTypeface(null, if (i == cat) Typeface.BOLD else Typeface.NORMAL)
            t.setOnClickListener {
                cat = i
                query = ""
                catViews.forEachIndexed { j, v -> v.setTextColor(if (j == cat) white else grey); v.setTypeface(null, if (j == cat) Typeface.BOLD else Typeface.NORMAL) }
                fillGrid()
            }
            catViews.add(t)
            catRow.addView(t)
        }
        val catScroll = HorizontalScrollView(this)
        catScroll.isHorizontalScrollBarEnabled = false
        catScroll.addView(catRow)

        val searchBox = EditText(this)
        searchBox.hint = "Search animations"
        searchBox.setHintTextColor(grey)
        searchBox.setTextColor(white)
        searchBox.textSize = 15f
        searchBox.maxLines = 1
        searchBox.inputType = android.text.InputType.TYPE_CLASS_TEXT
        searchBox.visibility = View.GONE
        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { query = e?.toString() ?: ""; fillGrid() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
        })

        val tabRow = LinearLayout(this)
        tabRow.orientation = LinearLayout.HORIZONTAL
        tabRow.gravity = Gravity.CENTER_VERTICAL
        val magnifier = ImageView(this)
        magnifier.setImageResource(R.drawable.ic_search)
        val circle = android.graphics.drawable.GradientDrawable()
        circle.shape = android.graphics.drawable.GradientDrawable.OVAL
        circle.setColor(0xFF2C2C31.toInt())
        magnifier.background = circle
        magnifier.setPadding(dp(9), dp(9), dp(9), dp(9))
        magnifier.setOnClickListener {
            val show = searchBox.visibility != View.VISIBLE
            searchBox.visibility = if (show) View.VISIBLE else View.GONE
            catScroll.visibility = if (show) View.GONE else View.VISIBLE
            if (!show) { searchBox.setText(""); query = ""; fillGrid() } else searchBox.requestFocus()
        }
        tabRow.addView(magnifier, LinearLayout.LayoutParams(dp(36), dp(36)).apply { setMargins(dp(6), 0, dp(10), 0) })
        val div = View(this)
        div.setBackgroundColor(0xFF3A3A3F.toInt())
        tabRow.addView(div, LinearLayout.LayoutParams(dp(1), dp(20)).apply { setMargins(0, 0, dp(6), 0) })
        val tabViews = ArrayList<TextView>()
        val bars = ArrayList<View>()
        listOf("In", "Out", "Combo").forEachIndexed { i, n ->
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            val t = TextView(this)
            t.text = n
            t.textSize = 19f
            t.setPadding(dp(14), dp(8), dp(14), dp(6))
            t.setTextColor(if (i == tab) white else grey)
            t.setTypeface(null, if (i == tab) Typeface.BOLD else Typeface.NORMAL)
            val bar = View(this)
            bar.setBackgroundColor(0xFF25F4EE.toInt())
            bar.visibility = if (i == tab) View.VISIBLE else View.INVISIBLE
            bar.layoutParams = LinearLayout.LayoutParams(dp(36), dp(2))
            col.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(bar)
            col.setOnClickListener {
                tab = i
                tabViews.forEachIndexed { j, v -> v.setTextColor(if (j == tab) white else grey); v.setTypeface(null, if (j == tab) Typeface.BOLD else Typeface.NORMAL) }
                bars.forEachIndexed { j, v -> v.visibility = if (j == tab) View.VISIBLE else View.INVISIBLE }
                fillGrid()
            }
            tabViews.add(t); bars.add(bar)
            tabRow.addView(col)
        }
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        tabRow.addView(spacer)
        val tick = ImageView(this)
        tick.setImageResource(R.drawable.ic_check)
        tick.setPadding(dp(10), dp(8), dp(10), dp(8))
        tick.setOnClickListener { dlg?.dismiss() }
        tabRow.addView(tick, LinearLayout.LayoutParams(dp(48), dp(44)))
        val line = View(this)
        line.setBackgroundColor(0xFF2C2C31.toInt())
        line.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)

        fillGrid()
        dlg = sheet("", tabRow, line, catScroll, searchBox, scroll, showHeader = false, heightPx = panelH, onClose = {
            if (c.paramSig() == before) project.discardCheckpoint()
            afterEdit()
        })
    }

    private fun controlsRow(): View = findViewById(R.id.controls)

    private var refreshGridHolder: (() -> Unit)? = null

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

    // ============================================================ docked panels (text / stickers)

    private fun dockHeight(): Int {
        val loc = IntArray(2)
        controlsRow().getLocationOnScreen(loc)
        val content = findViewById<View>(android.R.id.content)
        val cl = IntArray(2); content.getLocationOnScreen(cl)
        return (cl[1] + content.height - (loc[1] + controlsRow().height)).coerceIn(dp(260), dp(560))
    }

    private fun tabBar(names: List<String>, start: Int, textSp: Float, onPick: (Int) -> Unit): View {
        val white = 0xFFFFFFFF.toInt(); val grey = 0xFF8E8E93.toInt()
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val tvs = ArrayList<TextView>(); val bars = ArrayList<View>()
        names.forEachIndexed { i, n ->
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            val t = TextView(this)
            t.text = n
            t.textSize = textSp
            t.setPadding(dp(12), dp(8), dp(12), dp(6))
            t.setTextColor(if (i == start) white else grey)
            t.setTypeface(null, if (i == start) Typeface.BOLD else Typeface.NORMAL)
            val bar = View(this)
            bar.setBackgroundColor(0xFF25F4EE.toInt())
            bar.visibility = if (i == start) View.VISIBLE else View.INVISIBLE
            col.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(bar, LinearLayout.LayoutParams(dp(32), dp(2)))
            col.setOnClickListener {
                tvs.forEachIndexed { j, v -> v.setTextColor(if (j == i) white else grey); v.setTypeface(null, if (j == i) Typeface.BOLD else Typeface.NORMAL) }
                bars.forEachIndexed { j, v -> v.visibility = if (j == i) View.VISIBLE else View.INVISIBLE }
                onPick(i)
            }
            tvs.add(t); bars.add(bar)
            row.addView(col)
        }
        hs.addView(row)
        return hs
    }

    /** One grid cell: a dark rounded square holding [content], with a name below; white border when [on]. */
    private fun gridCell(w: Int, content: View, name: String, on: Boolean, onClick: () -> Unit): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(dp(6), dp(6), dp(6), dp(4))
        val side = w - dp(12)
        val holder = FrameLayout(this)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(10).toFloat()
        bg.setColor(0xFF26262B.toInt())
        if (on) bg.setStroke(dp(2), 0xFFFFFFFF.toInt())
        holder.background = bg
        holder.addView(content, FrameLayout.LayoutParams(side, side))
        box.addView(holder, LinearLayout.LayoutParams(side, side))
        if (name.isNotEmpty()) {
            val nm = TextView(this)
            nm.text = name; nm.textSize = 11f; nm.maxLines = 1
            nm.ellipsize = android.text.TextUtils.TruncateAt.END
            nm.gravity = Gravity.CENTER
            nm.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt())
            nm.setPadding(0, dp(4), 0, 0)
            box.addView(nm, LinearLayout.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        box.layoutParams = android.widget.GridLayout.LayoutParams().apply { width = w; height = ViewGroup.LayoutParams.WRAP_CONTENT }
        box.setOnClickListener { onClick() }
        return box
    }

    private fun pictureCell(bmp: Bitmap?): View {
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setPadding(dp(4), dp(4), dp(4), dp(4))
        if (bmp != null) iv.setImageBitmap(bmp)
        return iv
    }

    // ---------------------------------------------------------------- text

    private class TextTpl(val name: String, val cat: String, val font: Int, val color: Int, val stroke: Int, val strokeW: Float,
                          val bg: Int, val shadow: Boolean, val fx: Int, val bold: Boolean = true, val italic: Boolean = false)

    private val textTemplates = listOf(
        TextTpl("Neon blue", "Trending", 6, 0xFF40C4FF.toInt(), 0, 0.08f, 0, false, 1),
        TextTpl("Neon pink", "Trending", 6, 0xFFFF4081.toInt(), 0, 0.08f, 0, false, 1),
        TextTpl("Yellow pop", "Trending", 6, 0xFFFFEB3B.toInt(), 0xFF000000.toInt(), 0.12f, 0, false, 0),
        TextTpl("Red impact", "Trending", 6, 0xFFFFFFFF.toInt(), 0xFFD50000.toInt(), 0.12f, 0, false, 0),
        TextTpl("Glitch", "Trending", 6, 0xFFFFFFFF.toInt(), 0, 0.08f, 0, false, 3),
        TextTpl("Gradient", "Trending", 6, 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0.06f, 0, false, 5),
        TextTpl("Outline", "Trending", 6, 0xFFFFFFFF.toInt(), 0, 0.08f, 0, false, 4),
        TextTpl("Long shadow", "Trending", 6, 0xFFFFFFFF.toInt(), 0, 0.08f, 0, false, 2),
        TextTpl("Classic", "Basic", 0, 0xFFFFFFFF.toInt(), 0, 0.08f, 0, true, 0),
        TextTpl("Black on white", "Basic", 8, 0xFF000000.toInt(), 0, 0.08f, 0xFFFFFFFF.toInt(), false, 0),
        TextTpl("White on black", "Basic", 8, 0xFFFFFFFF.toInt(), 0, 0.08f, 0xCC000000.toInt(), false, 0),
        TextTpl("Subtitle", "Basic", 8, 0xFFFFEB3B.toInt(), 0xFF000000.toInt(), 0.08f, 0, false, 0),
        TextTpl("Typewriter", "Basic", 2, 0xFFFFFFFF.toInt(), 0, 0.08f, 0x99000000.toInt(), false, 0, false),
        TextTpl("Handwritten", "Basic", 3, 0xFFFFFFFF.toInt(), 0, 0.08f, 0, true, 0, false),
        TextTpl("Elegant", "Fancy", 1, 0xFFFFFFFF.toInt(), 0, 0.08f, 0, true, 0, false, true),
        TextTpl("Retro", "Fancy", 5, 0xFFFFC107.toInt(), 0xFF6A1B9A.toInt(), 0.12f, 0, false, 0),
        TextTpl("Mint", "Fancy", 6, 0xFF69F0AE.toInt(), 0xFF004D40.toInt(), 0.1f, 0, false, 0),
        TextTpl("Comic", "Fancy", 5, 0xFFFFEB3B.toInt(), 0xFF000000.toInt(), 0.15f, 0, false, 0)
    )

    private fun styleSample(base: Clip, text: String): Bitmap? {
        val t = base.deepCopy(-1L)
        t.text = text; t.sizeFrac = 0.16f; t.tilt = 0f
        return try { OverlayRenderer.renderText(t, 360, 360) } catch (_: Throwable) { null }
    }

    private fun applyTpl(c: Clip, t: TextTpl) {
        c.font = t.font; c.color = t.color; c.strokeColor = t.stroke; c.strokeW = t.strokeW
        c.bgColor = t.bg; c.shadow = t.shadow; c.textFx = t.fx; c.bold = t.bold; c.italic = t.italic
    }

    private fun textDialog() {
        var c = project.selected()?.takeIf { it.kind == LaneKind.TEXT }
        val fresh = c == null
        if (c == null) c = newFree(LaneKind.TEXT, "Your text") ?: return else project.checkpoint()
        val clip = c
        engine.pause()
        val white = 0xFFFFFFFF.toInt(); val grey = 0xFF8E8E93.toInt()
        val tileW = (resources.displayMetrics.widthPixels - dp(24)) / 4
        var dlg: Dialog? = null
        var tab = 0
        var tplCat = 0

        fun changed() { overlayView.invalidate(); timeline.invalidate() }

        val edit = EditText(this)
        edit.setText(clip.text)
        edit.setTextColor(white)
        edit.setHintTextColor(grey)
        edit.hint = "Enter text"
        edit.textSize = 16f
        edit.setSelection(edit.text.length)
        val eb = GradientDrawable(); eb.cornerRadius = dp(10).toFloat(); eb.setColor(0xFF2C2C31.toInt())
        edit.background = eb
        edit.setPadding(dp(14), dp(8), dp(14), dp(8))
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { clip.text = s?.toString() ?: ""; changed() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c1: Int) {}
        })
        val tick = ImageView(this)
        tick.setImageResource(R.drawable.ic_check)
        tick.setPadding(dp(10), dp(8), dp(10), dp(8))
        tick.setOnClickListener { dlg?.dismiss() }
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(edit, LinearLayout.LayoutParams(0, dp(44), 1f))
        top.addView(tick, LinearLayout.LayoutParams(dp(52), dp(44)))

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(content)
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

        fun grid(): android.widget.GridLayout = android.widget.GridLayout(this).also { it.columnCount = 4 }

        fun showTab() {
            content.removeAllViews()
            when (tab) {
                0 -> { // templates
                    val cats = listOf("Trending", "Basic", "Fancy")
                    val g = grid()
                    fun fill() {
                        g.removeAllViews()
                        val none = TextView(this).apply { text = "Default"; setTextColor(white); textSize = 14f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD) }
                        g.addView(gridCell(tileW, none, "", clip.textFx == 0 && clip.strokeColor == 0 && clip.bgColor == 0 && clip.font == 0) {
                            applyTpl(clip, TextTpl("", "", 0, white, 0, 0.08f, 0, true, 0)); changed(); fill()
                        })
                        for (t in textTemplates.filter { it.cat == cats[tplCat] }) {
                            val probe = Clip(id = -1, kind = LaneKind.TEXT)
                            applyTpl(probe, t)
                            val on = clip.font == t.font && clip.color == t.color && clip.textFx == t.fx && clip.strokeColor == t.stroke && clip.bgColor == t.bg
                            g.addView(gridCell(tileW, pictureCell(styleSample(probe, "Text")), t.name, on) { applyTpl(clip, t); changed(); fill() })
                        }
                    }
                    content.addView(tabBar(cats, tplCat, 15f) { tplCat = it; fill() })
                    content.addView(g)
                    fill()
                }
                1 -> { // fonts
                    val g = grid()
                    fun fill() {
                        g.removeAllViews()
                        Fx.FONT_NAMES.forEachIndexed { i, n ->
                            val probe = clip.deepCopy(-1L); probe.font = i; probe.textFx = 0; probe.strokeColor = 0; probe.bgColor = 0
                            g.addView(gridCell(tileW, pictureCell(styleSample(probe, "Aa")), n, clip.font == i) { clip.font = i; changed(); fill() })
                        }
                    }
                    content.addView(g)
                    fill()
                }
                2 -> { // styles
                    val pad = dp(10)
                    fun row(v: View) { v.setPadding(pad, 0, pad, 0); content.addView(v) }
                    val bigPalette = palette + listOf(0xFFFF9800.toInt(), 0xFF9C27B0.toInt(), 0xFF00E676.toInt(), 0xFFE91E63.toInt(), 0xFF795548.toInt())
                    row(label("Colour"))
                    row(colorChips(bigPalette) { clip.color = it; changed() })
                    row(label("Stroke"))
                    val strokeCols = listOf(0, 0xFF000000.toInt(), white, 0xFFD50000.toInt(), 0xFFFFEB3B.toInt(), 0xFF1E5BFF.toInt())
                    row(chips(listOf("None", "Black", "White", "Red", "Yellow", "Blue"), strokeCols.indexOf(clip.strokeColor)) { clip.strokeColor = strokeCols[it]; changed() })
                    row(slider("Stroke width", 0.02f, 0.3f, clip.strokeW, { "%d".format((it * 100).toInt()) }) { clip.strokeW = it; changed() })
                    row(label("Background"))
                    val bgCols = listOf(0, 0xCC000000.toInt(), white, 0xFFD50000.toInt(), 0xFFFFEB3B.toInt(), 0xFF1E5BFF.toInt())
                    row(chips(listOf("None", "Black", "White", "Red", "Yellow", "Blue"), bgCols.indexOf(clip.bgColor)) { clip.bgColor = bgCols[it]; changed() })
                    row(slider("Size", 0.02f, 0.4f, clip.sizeFrac, { "%d".format((it * 100).toInt()) }) { clip.sizeFrac = it; changed() })
                    row(slider("Tilt", -90f, 90f, clip.tilt.coerceIn(-90f, 90f), { "${it.toInt()}°" }) { clip.tilt = it; changed() })
                    val tg = LinearLayout(this); tg.orientation = LinearLayout.HORIZONTAL
                    fun tog(name: String, get: () -> Boolean, set: (Boolean) -> Unit) {
                        val b = button(name + if (get()) "  ✓" else "") {}
                        b.setOnClickListener { set(!get()); b.text = name + if (get()) "  ✓" else ""; changed() }
                        tg.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, dp(6), dp(6), 0) })
                    }
                    tog("Bold", { clip.bold }) { clip.bold = it }
                    tog("Italic", { clip.italic }) { clip.italic = it }
                    tog("Shadow", { clip.shadow }) { clip.shadow = it }
                    row(tg)
                }
                3 -> { // effects
                    val names = listOf("None", "Neon glow", "Long shadow", "Glitch", "Outline", "Gradient")
                    val g = grid()
                    fun fill() {
                        g.removeAllViews()
                        names.forEachIndexed { i, n ->
                            val probe = clip.deepCopy(-1L); probe.textFx = i; probe.bgColor = 0
                            if (i == 1 && probe.color == 0xFFFFFFFF.toInt()) probe.color = 0xFF40C4FF.toInt()
                            g.addView(gridCell(tileW, pictureCell(styleSample(probe, "Aa")), n, clip.textFx == i) {
                                clip.textFx = i
                                if (i == 1 && clip.color == 0xFFFFFFFF.toInt()) clip.color = 0xFF40C4FF.toInt()
                                changed(); fill()
                            })
                        }
                    }
                    content.addView(g)
                    fill()
                }
            }
        }

        val tabs = tabBar(listOf("Templates", "Fonts", "Styles", "Effects", "Stickers", "Animations"), 0, 17f) { i ->
            if (i == 4) {
                dlg?.dismiss()
                ui.postDelayed({ stickerDialog() }, 150)
            } else if (i == 5) {
                dlg?.dismiss()
                ui.postDelayed({ animationsFor(clip) }, 150)
            } else { tab = i; showTab() }
        }
        val line = View(this)
        line.setBackgroundColor(0xFF2C2C31.toInt())
        line.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        showTab()
        dlg = sheet("", top, tabs, line, scroll, showHeader = false, heightPx = dockHeight(), onClose = {
            if (clip.text.isBlank()) { project.selectedId = clip.id; project.delete() }
            afterEdit()
        })
        dlg?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    /** Opens the In / Out / Combo animation panel for a text or sticker clip. */
    private fun animationsFor(c: Clip) {
        if (project.byId(c.id) == null) return
        val side = 240
        var pic: Bitmap? = null
        try {
            val r = OverlayRenderer.render(this, c.deepCopy(-1L).also { it.tilt = 0f }, 480, 480)
            if (r != null) {
                val sq = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                val cv = Canvas(sq)
                cv.drawColor(0xFF3A3F4B.toInt())
                val k = min(side * 0.8f / r.width, side * 0.8f / r.height)
                val w = r.width * k; val h = r.height * k
                cv.drawBitmap(r, null, RectF((side - w) / 2f, (side - h) / 2f, (side + w) / 2f, (side + h) / 2f), null)
                pic = sq
            }
        } catch (_: Throwable) {}
        showAnimationPanel(c, pic)
    }

    // ---------------------------------------------------------------- stickers

    private val gifThumbCache = HashMap<String, Bitmap?>()

    private fun stickerDialog() {
        if (project.isEmpty()) { toast("Import a video first"); return }
        engine.pause()
        val white = 0xFFFFFFFF.toInt(); val grey = 0xFF8E8E93.toInt()
        val tileW = (resources.displayMetrics.widthPixels - dp(24)) / 4
        var dlg: Dialog? = null
        var cat = 0
        var query = ""
        var gQuery = ""
        var tabIdx = 0

        fun newGrid() = android.widget.GridLayout(this).also { it.columnCount = 4 }
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

        fun searchBox(hint: String, onText: (String) -> Unit): EditText {
            val search = EditText(this)
            search.hint = hint
            search.setHintTextColor(grey); search.setTextColor(white); search.textSize = 15f
            search.maxLines = 1
            search.inputType = android.text.InputType.TYPE_CLASS_TEXT
            search.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            val sb = GradientDrawable(); sb.cornerRadius = dp(10).toFloat(); sb.setColor(0xFF2C2C31.toInt())
            search.background = sb
            search.setPadding(dp(14), dp(8), dp(14), dp(8))
            search.setOnEditorActionListener { v, _, _ -> onText(v.text.toString()); true }
            return search
        }

        fun stickersBody() {
            body.removeAllViews()
            val g = newGrid()
            val scroll = ScrollView(this)
            scroll.addView(g)
            scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            fun fill() {
                g.removeAllViews()
                val list = if (query.isNotBlank()) StickerArt.ALL.filter { (it.label + " " + it.tags).contains(query.trim(), ignoreCase = true) }
                           else StickerArt.ALL.filter { it.cat == StickerArt.CATS[cat] }
                for (s in list) {
                    val bmp = try { StickerArt.render(s, 200) } catch (_: Throwable) { null }
                    val anim = StickerArt.animOf(s.id)
                    val cell: View = if (bmp != null) AnimTile(this, bmp, 2, anim, 0f, true) else View(this)
                    g.addView(gridCell(tileW, cell, "", false) {
                        val c = newFree(LaneKind.OVERLAY, s.label) ?: return@gridCell
                        c.uri = "sticker:" + s.id
                        c.sizeFrac = when (s.kind) { "badge" -> 0.5f; "banner" -> 0.6f; "emoji" -> 0.22f; else -> if (s.glyph == "bar") 0.9f else 0.26f }
                        c.posX = 0.5f; c.posY = 0.5f
                        c.animCombo = anim
                        afterEdit()
                    })
                }
            }
            val search = searchBox("Search stickers") { query = it; fill() }
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { query = e?.toString() ?: ""; fill() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            })
            val catBar = tabBar(StickerArt.CATS, cat, 15f) { cat = it; query = ""; search.setText(""); fill() }
            body.addView(search)
            body.addView(catBar)
            body.addView(scroll)
            fill()
        }

        fun giphyBody(problem: String = "") {
            body.removeAllViews()
            val key = Giphy.key(this)
            if (problem.isNotBlank()) {
                val pad = dp(10)
                val info = label(problem + "\n\nTo fix it once and for all, get a free key from GIPHY:\n1. Open developers.giphy.com and create an account\n2. Tap Create an App, choose API, copy the key\n3. Paste it here")
                info.setPadding(pad, dp(10), pad, dp(6))
                val keyBox = searchBox("Paste your GIPHY API key") { }
                val save = button("Save key") {
                    val k = keyBox.text.toString().trim()
                    if (k.length < 10) toast("That does not look like a GIPHY key") else { Giphy.setKey(this, k); giphyBody() }
                }
                val retry = button("Try again") { giphyBody() }
                body.addView(info); body.addView(keyBox); body.addView(save); body.addView(retry)
                return
            }
            val g = newGrid()
            val scroll = ScrollView(this)
            scroll.addView(g)
            scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            val status = TextView(this).apply { setTextColor(grey); textSize = 13f; setPadding(dp(10), dp(6), dp(10), dp(6)) }
            fun load() {
                status.text = "Loading…"
                g.removeAllViews()
                val q = gQuery
                Thread {
                    val items = try { Giphy.load(key, q) } catch (e: Throwable) { null }
                    runOnUiThread {
                        if (items == null) {
                            if (Giphy.ownKey(this).isBlank()) giphyBody("GIPHY did not answer (no internet, or the hourly limit of the app key was reached).")
                            else status.text = "Could not load GIPHY. Check the internet and your key."
                            return@runOnUiThread
                        }
                        status.text = if (items.isEmpty()) "Nothing found" else if (q.isBlank()) "Trending — tap one to add it" else "Results for \"$q\""
                        for (it in items) {
                            val iv = ImageView(this)
                            iv.scaleType = ImageView.ScaleType.FIT_CENTER
                            iv.setPadding(dp(6), dp(6), dp(6), dp(6))
                            val cached = gifThumbCache[it.thumbUrl]
                            if (cached != null) iv.setImageBitmap(cached) else Thread {
                                val b = Giphy.thumb(it.thumbUrl)
                                if (b != null) { gifThumbCache[it.thumbUrl] = b; runOnUiThread { iv.setImageBitmap(b) } }
                            }.start()
                            g.addView(gridCell(tileW, iv, "", false) {
                                toast("Downloading…")
                                Thread {
                                    val f = Giphy.download(this, it)
                                    runOnUiThread {
                                        if (f == null) { toast("Could not download this sticker"); return@runOnUiThread }
                                        val c = newFree(LaneKind.OVERLAY, "GIF") ?: return@runOnUiThread
                                        c.uri = Uri.fromFile(f).toString()
                                        c.sizeFrac = 0.35f; c.posX = 0.5f; c.posY = 0.5f
                                        afterEdit()
                                    }
                                }.start()
                            })
                        }
                    }
                }.start()
            }
            val search = searchBox("Search GIPHY") { gQuery = it; load() }
            val change = TextView(this).apply { text = "Change key"; setTextColor(grey); textSize = 12f; setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener { Giphy.setKey(this@MainActivity, ""); giphyBody() } }
            body.addView(search); body.addView(status); body.addView(scroll); body.addView(change)
            load()
        }

        val tick = ImageView(this)
        tick.setImageResource(R.drawable.ic_check)
        tick.setPadding(dp(10), dp(8), dp(10), dp(8))
        tick.setOnClickListener { dlg?.dismiss() }
        val tabs = tabBar(listOf("Stickers", "GIPHY"), 0, 17f) { i -> tabIdx = i; if (i == 0) stickersBody() else giphyBody() }
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(tabs, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(tick, LinearLayout.LayoutParams(dp(52), dp(44)))
        stickersBody()
        dlg = sheet("", head, body, showHeader = false, heightPx = dockHeight(), onClose = { afterEdit() })
        dlg?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private var capLang = 0

    private fun autoCaptionsDialog() {
        val sel = project.selected()
        val c = (sel?.takeIf { it.isMedia && (it.kind == LaneKind.AUDIO || it.hasAudio) }
            ?: project.mainClipAt(engine.playheadMs)?.takeIf { it.hasAudio })
        if (c == null) { toast("Select a clip that has speech (a video with sound, or an audio clip)"); return }
        val id = c.id
        val info = TextView(this).apply { setTextColor(0xFFDAD7EA.toInt()); textSize = 13f }
        fun refreshInfo() {
            val l = AutoCaptions.LANGS[capLang]
            info.text = if (AutoCaptions.hasModel(this, l)) "${l.label} model is on your phone — works offline."
                        else "${l.label} needs a one-time download of about ${l.sizeMb} MB (internet needed only for this)."
        }
        refreshInfo()
        var dlg: Dialog? = null
        val langs = chips(AutoCaptions.LANGS.map { it.label }, capLang) { capLang = it; refreshInfo() }
        val go = button("Generate captions") {
            val cur = project.byId(id) ?: return@button
            if (cur.srcOutMs - cur.srcInMs > 300000) { toast("Auto captions works on clips up to 5 minutes — cut the clip shorter first"); return@button }
            dlg?.dismiss()
            val l = AutoCaptions.LANGS[capLang]
            val uri = cur.uri; val a = cur.srcInMs; val b = cur.srcOutMs
            var failedDownload = false
            runWork("Auto captions (${l.label})") { progress ->
                var from = 0
                if (!AutoCaptions.hasModel(this, l)) {
                    if (!AutoCaptions.download(this, l, progress)) { failedDownload = true; return@runWork null }
                    from = 70
                }
                val words = AutoCaptions.transcribe(this, uri, a, b, l, from, progress) ?: return@runWork null
                val cues = CaptionMath.cues(words, if (l.spaced) " " else "")
                return@runWork {
                    val cl = project.byId(id)
                    if (cl == null) toast("The clip was removed") else if (cues.isEmpty()) toast("No speech found in this clip")
                    else {
                        project.checkpoint()
                        val t0 = cl.startMs; val t1 = cl.endMs
                        project.text.removeAll { it.label == "autocap" && it.startMs < t1 && it.endMs > t0 }
                        for (q in cues) {
                            val s = (cl.startMs + ((q.s - cl.srcInMs) / cl.speed).toLong()).coerceIn(t0, t1)
                            val e = (cl.startMs + ((q.e - cl.srcInMs) / cl.speed).toLong()).coerceIn(t0, t1)
                            if (e - s < 100) continue
                            project.text.add(Clip(id = project.newId(), kind = LaneKind.TEXT, startMs = s, text = q.text, durMs = e - s,
                                sizeFrac = 0.05f, posY = 0.88f, label = "autocap"))
                        }
                        afterEdit()
                        toast("${cues.size} caption lines added — tap a line on the text track to edit it")
                    }
                }
            }
        }
        val clear = button("Remove auto captions") {
            val n = project.text.count { it.label == "autocap" }
            if (n == 0) toast("No auto captions to remove") else {
                project.checkpoint(); project.text.removeAll { it.label == "autocap" }; afterEdit(); toast("$n lines removed")
            }
            dlg?.dismiss()
        }
        dlg = sheet("Auto captions", label("Language spoken in the clip"), langs, info, go, clear,
            label("Listens to the speech on your phone and adds subtitle lines to the text track. It is a small offline model, not a cloud AI: it works best with clear speech and little music, and it will make mistakes, so check and edit the lines. Bengali is not available yet. Adds roughly 30 MB to the app."))
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
        sheet("Get Cut", label("Version ${versionName()}"), button("Check for updates") { updater.check(false) },
            button("Project info") { projectDialog() }, EditorTheme.picker(this) { EditorTheme.apply(this) })
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

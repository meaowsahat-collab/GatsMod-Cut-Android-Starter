package com.gatsmod.cut

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var timeline: TimelineView
    private var pendingVideoUri: Uri? = null

    private data class Cue(val start: Long, val end: Long, val text: String)
    private val cues = mutableListOf<Cue>()
    private var textColor = Color.WHITE
    private var textSize = 24f
    private var position = 0
    private var animationStyle = 0

    private var brightness = 0f
    private var contrast = 1f
    private var saturation = 1f
    private var blurRadius = 0f
    private var keyframeCount = 0
    private var chromaEnabled = false
    private var exportSize = "1080p"
    private var exportFps = 30
    private var fullscreen = false

    private val handler = Handler(Looper.getMainLooper())
    private val updater by lazy { Updater(this) }
    private val tick = object : Runnable {
        override fun run() { onTick(); handler.postDelayed(this, 80) }
    }

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { persist(it); loadVideo(it, LaneKind.VIDEO) } }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { persist(it); loadVideo(it, LaneKind.AUDIO) } }
    private val srtPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { persist(it); readSrt(it) } }

    private fun persist(u: Uri) {
        try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        timeline = findViewById(R.id.timeline)
        setupPlayer()
        setupTimeline()
        setupTopBar()
        setupControls()
        buildClipTools()
        buildToolbar()
        updater.cleanup()
        updater.check(true)
        handler.post(tick)
    }

    // ---------------- setup ----------------

    private fun setupPlayer() {
        val p = ExoPlayer.Builder(this).build()
        player = p
        findViewById<PlayerView>(R.id.playerView).player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    pendingVideoUri?.let { u ->
                        val d = p.duration.takeIf { it > 0 } ?: 3000L
                        timeline.setDuration(maxOf(timeline.durationMs, d))
                        timeline.addClip(LaneKind.VIDEO, "Video", u.toString(), d)
                        pendingVideoUri = null
                    }
                }
                refreshTime()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                findViewById<ImageButton>(R.id.playButton)
                    .setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            }
        })
    }

    private fun setupTimeline() {
        timeline.onSeek = { player?.seekTo(it); refreshTime() }
        timeline.onSelection = { c ->
            info(c?.let { "Selected: ${it.label} • ${it.kind} • ${fmt(it.startMs)}–${fmt(it.endMs)}" } ?: "No selection")
        }
        findViewById<View>(R.id.addClipButton).setOnClickListener { videoPicker.launch(arrayOf("video/*")) }
        findViewById<View>(R.id.emptyPreview).setOnClickListener { videoPicker.launch(arrayOf("video/*")) }
    }

    private fun setupTopBar() {
        findViewById<View>(R.id.closeButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Close editor?")
                .setMessage("Unsaved timeline changes will be lost.")
                .setPositiveButton("Close") { _, _ -> finish() }
                .setNegativeButton("Stay", null)
                .show()
        }
        findViewById<View>(R.id.helpButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("GatsMod Cut")
                .setItems(arrayOf("Check for update", "Tips")) { _, i ->
                    when (i) {
                        0 -> updater.check(false)
                        1 -> AlertDialog.Builder(this)
                            .setTitle("Tips")
                            .setMessage("• Tap + to import a video\n• Tap a clip to select it, drag to move\n• Tap the ruler or empty space to move the playhead\n• Cut splits the selected clip at the playhead")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
                .show()
        }
        findViewById<View>(R.id.exportButton).setOnClickListener { exportSettingsDialog() }
        findViewById<View>(R.id.resChip).setOnClickListener { exportSettingsDialog() }
    }

    private fun setupControls() {
        findViewById<View>(R.id.playButton).setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.mediaItemCount == 0) { toast("Import a video first"); return@setOnClickListener }
            if (p.isPlaying) p.pause() else {
                if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                p.play()
            }
        }
        findViewById<View>(R.id.undoButton).setOnClickListener { timeline.undo() }
        findViewById<View>(R.id.redoButton).setOnClickListener { timeline.redo() }
        findViewById<View>(R.id.fullscreenButton).setOnClickListener { toggleFullscreen() }
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        val vis = if (fullscreen) View.GONE else View.VISIBLE
        findViewById<View>(R.id.topBar).visibility = vis
        findViewById<View>(R.id.bottomPanel).visibility = vis
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---------------- bottom tool grid (like CapCut) ----------------

    private fun buildToolbar() {
        val bar = findViewById<LinearLayout>(R.id.toolbar)
        val rows = listOf(
            listOf(
                Tool("Cut", R.drawable.ic_cut) { timeline.split() },
                Tool("Crop", R.drawable.ic_crop) { soon("Crop") },
                Tool("Speed", R.drawable.ic_speed) { cycleSpeed() },
                Tool("Filters", R.drawable.ic_filters) { filterDialog() },
                Tool("Text", R.drawable.ic_text) { editText(LaneKind.TEXT) },
                Tool("Audio", R.drawable.ic_audio) { audioPicker.launch(arrayOf("audio/*")) }
            ),
            listOf(
                Tool("Overlay", R.drawable.ic_overlay) { editText(LaneKind.OVERLAY) },
                Tool("Effects", R.drawable.ic_effects) { effectsDialog() },
                Tool("Transitions", R.drawable.ic_transitions) { soon("Transitions") },
                Tool("Stickers", R.drawable.ic_stickers) { soon("Stickers") },
                Tool("Captions", R.drawable.ic_captions) { srtPicker.launch(arrayOf("text/*", "application/x-subrip")) },
                Tool("Export", R.drawable.ic_export, accent = true) { exportSettingsDialog() }
            )
        )
        rows.forEach { row ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            row.forEach { line.addView(tile(it)) }
            bar.addView(line)
        }
    }

    private class Tool(val label: String, val icon: Int, val accent: Boolean = false, val action: () -> Unit)

    private fun tile(t: Tool): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@MainActivity, if (t.accent) R.drawable.bg_tile_accent else R.drawable.bg_tile)
            layoutParams = LinearLayout.LayoutParams(0, dp(62), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
            isClickable = true
            isFocusable = true
            setOnClickListener { t.action() }
        }
        box.addView(ImageView(this).apply {
            setImageResource(t.icon)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        })
        box.addView(TextView(this).apply {
            text = t.label
            setTextColor(Color.WHITE)
            textSize = 10f
            maxLines = 1
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        return box
    }

    // ---------------- clip tools row ----------------

    private fun buildClipTools() {
        val bar = findViewById<LinearLayout>(R.id.clipTools)
        val chips = listOf<Pair<String, () -> Unit>>(
            "Trim Start" to { timeline.trimStart() },
            "Trim End" to { timeline.trimEnd() },
            "Duplicate" to { timeline.duplicate() },
            "Delete" to { timeline.delete() },
            "Merge" to { timeline.merge() },
            "◀ Frame" to { timeline.nudgeFrames(-1) },
            "Frame ▶" to { timeline.nudgeFrames(1) },
            "Zoom +" to { timeline.zoomIn() },
            "Zoom −" to { timeline.zoomOut() },
            "+ Video Track" to { timeline.addLane(LaneKind.VIDEO) },
            "+ Audio Track" to { timeline.addLane(LaneKind.AUDIO) },
            "Keyframe" to { keyframeDialog() },
            "Chroma Key" to { chromaDialog() },
            "Text Style" to { styleDialog() }
        )
        chips.forEach { (label, action) ->
            bar.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(dp(3), 0, dp(3), 0) }
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            })
        }
    }

    // ---------------- tool actions (same behaviour as before) ----------------

    private fun cycleSpeed() {
        val p = player ?: return
        val v = if (p.playbackParameters.speed >= 1.9f) 1f else p.playbackParameters.speed + .5f
        p.setPlaybackSpeed(v)
        toast("Preview speed: ${v}x")
    }

    private fun filterDialog() {
        AlertDialog.Builder(this)
            .setTitle("Color Filter")
            .setItems(arrayOf("None", "Warm", "Cool", "Mono", "Sepia")) { _, j -> info("Filter preset $j selected • render integration pending") }
            .show()
    }

    private fun effectsDialog() {
        val labels = arrayOf("Brightness: $brightness", "Contrast: $contrast", "Saturation: $saturation", "Blur: $blurRadius", "Color Filter: presets")
        AlertDialog.Builder(this)
            .setTitle("Effects & Color")
            .setItems(labels) { _, i ->
                when (i) {
                    0 -> adjustValue("Brightness", brightness, -1f, 1f) { brightness = it }
                    1 -> adjustValue("Contrast", contrast, 0f, 2f) { contrast = it }
                    2 -> adjustValue("Saturation", saturation, 0f, 2f) { saturation = it }
                    3 -> adjustValue("Blur", blurRadius, 0f, 20f) { blurRadius = it }
                    4 -> filterDialog()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun adjustValue(title: String, value: Float, minV: Float, maxV: Float, apply: (Float) -> Unit) {
        val seek = SeekBar(this)
        seek.max = 100
        seek.progress = ((value - minV) / (maxV - minV) * 100).toInt().coerceIn(0, 100)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(seek)
            .setPositiveButton("Apply") { _, _ ->
                val v = minV + (maxV - minV) * seek.progress / 100f
                apply(v)
                info("$title set to $v • effect rendering integration pending")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun keyframeDialog() {
        keyframeCount++
        info("Keyframe marker #$keyframeCount at ${player?.currentPosition ?: 0}ms • animation engine pending")
        toast("Keyframe marker added")
    }

    private fun chromaDialog() {
        chromaEnabled = !chromaEnabled
        info("Chroma Key " + (if (chromaEnabled) "enabled" else "disabled") + " • processing integration pending")
        toast("Chroma Key " + (if (chromaEnabled) "on" else "off"))
    }

    private fun exportSettingsDialog() {
        val options = arrayOf("720p", "1080p", "4K", "FPS: 24", "FPS: 30", "FPS: 60", "MP4 H.264")
        AlertDialog.Builder(this)
            .setTitle("Advanced Export • $exportSize • $exportFps FPS")
            .setItems(options) { _, i ->
                when (i) {
                    0, 1, 2 -> exportSize = options[i]
                    3 -> exportFps = 24
                    4 -> exportFps = 30
                    5 -> exportFps = 60
                    6 -> toast("MP4 H.264 selected")
                }
                findViewById<TextView>(R.id.resChip).text = exportSize.uppercase() + " ▾"
                info("Preset: $exportSize • $exportFps FPS • MP4 • encoding engine pending")
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun editText(kind: LaneKind) {
        val e = EditText(this)
        e.hint = "Enter text"
        AlertDialog.Builder(this)
            .setTitle("Add ${kind.name.lowercase()}")
            .setView(e)
            .setPositiveButton("Add") { _, _ ->
                val s = e.text.toString().trim()
                if (s.isNotEmpty()) {
                    timeline.addClip(kind, s, duration = 3000)
                    val now = player?.currentPosition ?: 0
                    cues.add(Cue(now, now + 3000, s))
                    updateSubtitle()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun styleDialog() {
        val options = arrayOf("White", "Yellow", "Cyan", "Green", "Pink", "Small (18)", "Medium (24)", "Large (36)", "Bottom", "Center", "Top", "Fade", "Slide", "No animation")
        AlertDialog.Builder(this)
            .setTitle("Text Style")
            .setItems(options) { _, i ->
                when (i) {
                    0 -> textColor = Color.WHITE
                    1 -> textColor = Color.YELLOW
                    2 -> textColor = Color.CYAN
                    3 -> textColor = Color.GREEN
                    4 -> textColor = Color.MAGENTA
                    5 -> textSize = 18f
                    6 -> textSize = 24f
                    7 -> textSize = 36f
                    8 -> position = 0
                    9 -> position = 1
                    10 -> position = 2
                    11 -> animationStyle = 1
                    12 -> animationStyle = 2
                    13 -> animationStyle = 0
                }
                updateSubtitle()
            }
            .show()
    }

    private fun soon(name: String) = toast("$name is coming in the next step")

    // ---------------- subtitles / SRT ----------------

    private fun readSrt(u: Uri) {
        try {
            val s = contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } ?: return
            var count = 0
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
                            cues.add(Cue(st, en, text))
                            timeline.addClip(LaneKind.TEXT, text, duration = en - st)
                            count++
                        }
                    }
                }
            }
            toast("Loaded $count subtitle cues")
            updateSubtitle()
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

    private fun updateSubtitle() {
        val v = findViewById<TextView>(R.id.textPreview)
        val now = player?.currentPosition ?: 0
        val cue = cues.lastOrNull { now >= it.start && now < it.end }
        v.text = cue?.text ?: ""
        v.visibility = if (cue == null) View.GONE else View.VISIBLE
        v.setTextColor(textColor)
        v.textSize = textSize
        v.gravity = Gravity.CENTER
        val lp = v.layoutParams as FrameLayout.LayoutParams
        lp.gravity = when (position) {
            2 -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            1 -> Gravity.CENTER
            else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        v.layoutParams = lp
        v.alpha = 1f
        v.translationY = 0f
        if (cue != null && animationStyle == 1) v.alpha = .85f
        if (cue != null && animationStyle == 2) v.translationY = -12f
    }

    // ---------------- media ----------------

    private fun loadVideo(u: Uri, k: LaneKind) {
        val p = player ?: return
        if (k == LaneKind.VIDEO) {
            pendingVideoUri = u
            p.setMediaItem(MediaItem.fromUri(u))
            p.prepare()
            p.playWhenReady = true
            findViewById<View>(R.id.emptyPreview).visibility = View.GONE
        } else {
            timeline.addClip(LaneKind.AUDIO, "Audio", u.toString(), timeline.durationMs.coerceAtLeast(3000))
        }
        info("Imported ${k.name.lowercase()}")
    }

    // ---------------- helpers ----------------

    private fun onTick() {
        updateSubtitle()
        val p = player ?: return
        if (p.isPlaying) timeline.setPlayhead(p.currentPosition)
        refreshTime()
    }

    private fun fmt(ms: Long): String { val s = ms / 1000; return "%02d:%02d".format(s / 60, s % 60) }

    private fun refreshTime() {
        findViewById<TextView>(R.id.timeLabel).text = "${fmt(timeline.playheadMs)} / ${fmt(timeline.durationMs)}"
    }

    private fun info(s: String) { findViewById<TextView>(R.id.clipInfo).text = s }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onResume() { super.onResume(); updater.resumePendingInstall() }
    override fun onStop() { super.onStop(); player?.pause() }
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        player?.release()
        player = null
        super.onDestroy()
    }
}

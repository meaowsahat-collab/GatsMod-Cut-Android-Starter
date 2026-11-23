package com.gatsmod.cut

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/** Teleprompter: the script scrolls over the camera picture while you record. The finished video can be opened in the editor. */
class TeleprompterActivity : AppCompatActivity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private lateinit var preview: PreviewView
    private lateinit var script: EditText
    private lateinit var prompt: TextView
    private lateinit var promptBox: FrameLayout
    private lateinit var recBtn: TextView
    private lateinit var modeBtn: TextView
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var front = true
    private var running = false
    private var speed = 40f      // dp per second
    private var offset = 0f
    private var last = 0L
    private var mirror = false
    private var provider: ProcessCameraProvider? = null

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) startCamera() else toast("Camera and microphone permission are needed to record")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        preview = PreviewView(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        // scrolling script
        promptBox = FrameLayout(this)
        promptBox.setBackgroundColor(0x88000000.toInt())
        promptBox.clipChildren = true
        prompt = TextView(this)
        prompt.setTextColor(Color.WHITE)
        prompt.textSize = 30f
        prompt.gravity = Gravity.CENTER_HORIZONTAL
        prompt.setTypeface(null, Typeface.BOLD)
        prompt.setPadding(dp(20), 0, dp(20), 0)
        promptBox.addView(prompt, FrameLayout.LayoutParams(-1, -2))
        promptBox.visibility = View.GONE
        root.addView(promptBox, FrameLayout.LayoutParams(-1, dp(300), Gravity.TOP).also { it.topMargin = dp(60) })

        // script editor
        script = EditText(this)
        script.setText("Type or paste your script here.\n\nTap Start, then press the red button to record while the text scrolls.")
        script.setTextColor(Color.WHITE)
        script.setHintTextColor(0xFF999999.toInt())
        script.setBackgroundColor(0xCC000000.toInt())
        script.gravity = Gravity.TOP
        script.setPadding(dp(16), dp(16), dp(16), dp(16))
        root.addView(script, FrameLayout.LayoutParams(-1, dp(330), Gravity.TOP).also { it.topMargin = dp(50) })

        // controls
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(0xCC000000.toInt())
        panel.setPadding(dp(14), dp(8), dp(14), dp(14))
        fun lbl(t: String) = TextView(this).apply { text = t; setTextColor(0xFFCCCCCC.toInt()); textSize = 12f }
        panel.addView(lbl("Scroll speed"))
        val sp = SeekBar(this); sp.max = 100; sp.progress = 40
        sp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { speed = 10f + p * 1.2f }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        panel.addView(sp)
        panel.addView(lbl("Text size"))
        val sz = SeekBar(this); sz.max = 40; sz.progress = 14
        sz.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { prompt.textSize = 16f + p }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        panel.addView(sz)
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        fun chip(t: String, click: () -> Unit): TextView = TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(14), dp(10), dp(14), dp(10))
            val g = GradientDrawable(); g.cornerRadius = dp(18).toFloat(); g.setColor(0xFF2C2C31.toInt()); background = g
            setOnClickListener { click() }
        }
        modeBtn = chip("Start") { toggleScroll() }
        row.addView(modeBtn)
        row.addView(chip("Flip camera") { front = !front; startCamera() }, LinearLayout.LayoutParams(-2, -2).also { it.marginStart = dp(8) })
        row.addView(chip("Mirror text") { mirror = !mirror; prompt.scaleX = if (mirror) -1f else 1f }, LinearLayout.LayoutParams(-2, -2).also { it.marginStart = dp(8) })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        recBtn = TextView(this)
        recBtn.text = "●"
        recBtn.textSize = 30f
        recBtn.gravity = Gravity.CENTER
        recBtn.setTextColor(Color.WHITE)
        val rg = GradientDrawable(); rg.shape = GradientDrawable.OVAL; rg.setColor(0xFFE53935.toInt()); recBtn.background = rg
        recBtn.setOnClickListener { toggleRecord() }
        row.addView(recBtn, LinearLayout.LayoutParams(dp(62), dp(62)))
        panel.addView(row, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (need.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startCamera() else perms.launch(need)
        tick()
    }

    private fun toggleScroll() {
        running = !running
        if (running) {
            prompt.text = script.text.toString()
            promptBox.visibility = View.VISIBLE
            script.visibility = View.GONE
            offset = promptBox.height.toFloat().coerceAtLeast(dp(300).toFloat())
            last = 0L
            modeBtn.text = "Edit script"
        } else {
            promptBox.visibility = View.GONE
            script.visibility = View.VISIBLE
            modeBtn.text = "Start"
        }
    }

    private fun tick() {
        preview.postOnAnimation {
            val now = System.nanoTime()
            if (running) {
                if (last != 0L) offset -= speed * dp(1) * (now - last) / 1e9f
                prompt.translationY = offset
                if (offset < -prompt.height) offset = promptBox.height.toFloat()
            }
            last = now
            tick()
        }
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                val p = f.get(); provider = p
                val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val rec = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
                val vc = VideoCapture.withOutput(rec)
                videoCapture = vc
                p.unbindAll()
                p.bindToLifecycle(this, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, pv, vc)
            } catch (e: Exception) {
                toast("Could not start the camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleRecord() {
        val r = recording
        if (r != null) { r.stop(); return }
        val vc = videoCapture ?: run { toast("Camera is not ready yet"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { toast("Microphone permission is needed"); return }
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "GetCut_" + System.currentTimeMillis())
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Get Cut")
        }
        val out = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(cv).build()
        recording = vc.output.prepareRecording(this, out).withAudioEnabled().start(ContextCompat.getMainExecutor(this)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                recording = null
                recBtn.text = "●"
                if (ev.hasError()) toast("Recording failed (code ${ev.error})") else {
                    val u: Uri = ev.outputResults.outputUri
                    startActivity(Intent(this, MainActivity::class.java).putExtra("videoUri", u.toString()))
                    finish()
                }
            }
        }
        recBtn.text = "■"
        if (!running) toggleScroll()
    }

    override fun onDestroy() {
        recording?.stop()
        super.onDestroy()
    }
}

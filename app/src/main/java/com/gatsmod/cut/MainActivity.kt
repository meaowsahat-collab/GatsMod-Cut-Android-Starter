package com.gatsmod.cut

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var selectedVideo: Uri? = null

    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            loadVideo(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val playerView = findViewById<PlayerView>(R.id.playerView)
        player = ExoPlayer.Builder(this).build().also { playerView.player = it }

        findViewById<Button>(R.id.importButton).setOnClickListener {
            videoPicker.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.trimButton).setOnClickListener {
            showInfo("Trim tool", "Timeline-based trimming is planned for the next build.")
        }
        findViewById<Button>(R.id.splitButton).setOnClickListener {
            showInfo("Split tool", "Clip splitting is planned for the next build.")
        }
        findViewById<Button>(R.id.textButton).setOnClickListener {
            showInfo("Text tool", "Text overlays are planned for the next build.")
        }
        findViewById<Button>(R.id.musicButton).setOnClickListener {
            showInfo("Music tool", "Audio tracks are planned for the next build.")
        }
        findViewById<Button>(R.id.speedButton).setOnClickListener {
            val exo = player ?: return@setOnClickListener
            val current = exo.playbackParameters.speed
            val next = if (current >= 1.9f) 1.0f else current + 0.5f
            exo.setPlaybackSpeed(next)
            Toast.makeText(this, "Preview speed: ${next}x", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.filterButton).setOnClickListener {
            showInfo("Filters", "Color filters are planned for the next build.")
        }
        findViewById<Button>(R.id.exportButton).setOnClickListener {
            showInfo("Export", "Export is not enabled in this starter build yet.")
        }
    }

    private fun loadVideo(uri: Uri) {
        selectedVideo = uri
        val exo = player ?: return
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = true

        findViewById<TextView>(R.id.emptyPreview).visibility = android.view.View.GONE
        findViewById<TextView>(R.id.clipInfo).text =
            "Selected clip: ${uri.lastPathSegment ?: "Video"}"
        findViewById<Button>(R.id.exportButton).isEnabled = true
        Toast.makeText(this, "Video loaded", Toast.LENGTH_SHORT).show()
    }

    private fun showInfo(title: String, message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}

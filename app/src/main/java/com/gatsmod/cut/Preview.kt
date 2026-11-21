@file:OptIn(UnstableApi::class)

package com.gatsmod.cut

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs
import kotlin.math.max

/**
 * Plays the project the way it will be exported: the main track is a playlist of trimmed pieces,
 * each piece gets its own effects, and the audio lane plays in a second player kept in sync.
 */
class PreviewEngine(private val ctx: Context, private val project: Project) {

    interface Listener {
        fun onPlayingChanged(playing: Boolean)
    }

    var listener: Listener? = null
    val player: ExoPlayer = ExoPlayer.Builder(ctx).build()
    private val audioPlayer: ExoPlayer = ExoPlayer.Builder(ctx).build()

    /** Playhead position on the timeline, in milliseconds. */
    var playheadMs = 0L
        private set

    private var mainSig = ""
    private var appliedKey = ""
    private var audioClipId = -1L

    init {
        player.setVideoEffects(emptyList())
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) audioPlayer.pause()
                listener?.onPlayingChanged(isPlaying)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    playheadMs = project.totalMs()
                    audioPlayer.pause()
                    listener?.onPlayingChanged(false)
                }
            }
        })
    }

    val isPlaying: Boolean get() = player.isPlaying

    // ---------------- control ----------------

    fun play() {
        if (project.main.isEmpty()) return
        if (playheadMs >= project.totalMs() - 50) seekTo(0)
        player.play()
        syncAudio(true)
    }

    fun pause() {
        player.pause()
        audioPlayer.pause()
    }

    fun seekTo(ms: Long) {
        val total = project.totalMs()
        if (project.main.isEmpty() || total <= 0) { playheadMs = 0; return }
        val t = ms.coerceIn(0L, total - 1)
        playheadMs = t
        val idx = project.main.indexOfFirst { t >= it.startMs && t < it.endMs }.let { if (it < 0) project.main.lastIndex else it }
        val c = project.main[idx]
        applyLook(true)
        if (player.mediaItemCount > idx) player.seekTo(idx, ((t - c.startMs) * c.speed).toLong())
        syncAudio(true)
    }

    /** Call after the project changed (cut, trim, effect, ...). Keeps the playhead where it was. */
    fun refresh() {
        val sig = project.mainSignature()
        if (sig != mainSig) {
            mainSig = sig
            val items = project.main.map { mediaItem(it) }
            if (items.isEmpty()) {
                player.clearMediaItems()
                playheadMs = 0
            } else {
                val keep = playheadMs
                player.setMediaItems(items, false)
                player.prepare()
                appliedKey = ""
                seekTo(min(keep, project.totalMs() - 1))
            }
        } else {
            applyLook(true)
            // re-render the current frame so effect changes are visible while paused
            if (project.main.isNotEmpty() && !player.isPlaying) seekTo(playheadMs)
        }
        playheadMs = playheadMs.coerceIn(0L, max(0L, project.totalMs()))
        syncAudio(true)
    }

    private fun min(a: Long, b: Long) = if (a < b) a else b

    /** Periodic update (every ~80 ms) from the UI. */
    fun tick() {
        if (project.main.isEmpty()) return
        if (player.isPlaying) {
            val c = project.main.getOrNull(player.currentMediaItemIndex)
            if (c != null) playheadMs = (c.startMs + player.currentPosition / c.speed).toLong().coerceIn(0L, project.totalMs())
            applyLook(false)
            syncAudio(false)
        }
    }

    fun release() {
        player.release()
        audioPlayer.release()
    }

    // ---------------- internals ----------------

    private fun mediaItem(c: Clip): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.parse(c.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.srcInMs).setEndPositionMs(c.srcOutMs).build()
            )
            .build()

    /** Applies speed, volume and effects of the clip under the playhead. */
    private fun applyLook(force: Boolean) {
        val c = project.mainClipAt(playheadMs) ?: return
        val key = "${c.id}|${c.paramSig()}"
        if (!force && key == appliedKey) return
        appliedKey = key
        player.setVideoEffects(Fx.previewEffects(c) { playheadMs - c.startMs })
        player.playbackParameters = PlaybackParameters(c.speed)
        player.volume = c.volume
    }

    private fun syncAudio(force: Boolean) {
        val a = project.audio.firstOrNull { playheadMs >= it.startMs && playheadMs < it.endMs }
        if (a == null || !player.isPlaying) {
            if (audioPlayer.isPlaying) audioPlayer.pause()
            if (a == null) audioClipId = -1L
            return
        }
        val expected = ((playheadMs - a.startMs) * a.speed).toLong()
        if (force || audioClipId != a.id) {
            audioClipId = a.id
            audioPlayer.setMediaItem(
                MediaItem.Builder()
                    .setUri(Uri.parse(a.uri))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(a.srcInMs).setEndPositionMs(a.srcOutMs).build()
                    )
                    .build()
            )
            audioPlayer.prepare()
            audioPlayer.seekTo(expected)
        } else if (abs(audioPlayer.currentPosition - expected) > 300) {
            audioPlayer.seekTo(expected)
        }
        audioPlayer.playbackParameters = PlaybackParameters(a.speed)
        audioPlayer.volume = a.volume
        audioPlayer.playWhenReady = true
    }
}

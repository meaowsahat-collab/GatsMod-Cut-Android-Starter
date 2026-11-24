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
        fun onProblem(message: String) {}
    }

    private var effectsOk = true
    private var failedId: Any? = null
    private var appliedFx = "\u0000"

    var listener: Listener? = null
    var player: ExoPlayer = ExoPlayer.Builder(ctx).build()
        private set

    /** Called when the underlying player was replaced (the view must attach to the new one). */
    var onPlayerReplaced: ((ExoPlayer) -> Unit)? = null
    private val audioPlayer: ExoPlayer = ExoPlayer.Builder(ctx).build()

    /** Playhead position on the timeline, in milliseconds. */
    var playheadMs = 0L
        private set

    private var mainSig = ""
    private var appliedKey = ""
    private var audioClipId = -1L

    /** ExoPlayer whose audio output runs through the noise reducer when [denoise] > 0. */
    private fun buildPlayer(denoise: Float): ExoPlayer {
        if (denoise <= 0f) return ExoPlayer.Builder(ctx).build()
        val factory = object : androidx.media3.exoplayer.DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink? =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<androidx.media3.common.audio.AudioProcessor>(DenoiseProcessor(denoise)))
                    .build()
        }
        return ExoPlayer.Builder(ctx, factory).build()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) audioPlayer.pause()
            listener?.onPlayingChanged(isPlaying)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // Never leave the preview dead: report it, drop the preview effects and restart playback.
            val why = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.distinct().joinToString(" / ").take(160)
            listener?.onProblem("Preview problem: ${error.errorCodeName} $why".trim())
            if (effectsOk) {
                failedId = project.mainClipAt(playheadMs)?.id
                effectsOk = false
                appliedFx = "off"
                rebuild(emptyList())
            } else {
                try { player.prepare() } catch (_: Exception) {}
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                playheadMs = project.totalMs()
                audioPlayer.pause()
                listener?.onPlayingChanged(false)
            }
        }
    }

    init {
        player.addListener(playerListener)
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
                appliedFx = "\u0000"
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
            val cur = project.mainClipAt(playheadMs)
            if (cur != null && (cur.fadeInMs > 0 || cur.fadeOutMs > 0)) {
                player.volume = cur.volume.coerceAtMost(1f) * Fade.gain(playheadMs - cur.startMs, cur.lengthMs, cur.fadeInMs, cur.fadeOutMs)
            }
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

    /**
     * Effects can only be changed safely before a player is prepared, so a new look means a fresh player
     * that continues from the current position.
     */
    private fun rebuild(effects: List<androidx.media3.common.Effect>) {
        val items = project.main.map { mediaItem(it) }
        if (items.isEmpty()) return
        val c = project.mainClipAt(playheadMs) ?: project.main.last()
        val idx = project.main.indexOf(c).coerceAtLeast(0)
        val pos = ((playheadMs - c.startMs).coerceAtLeast(0L) * c.speed).toLong()
        val play = player.playWhenReady
        val old = player
        val fresh = buildPlayer(c.denoise)
        // no effects -> do not touch the GL frame pipeline at all (plain, most compatible playback path)
        if (effects.isNotEmpty()) try {
            fresh.setVideoEffects(effects)
        } catch (e: Exception) {
            listener?.onProblem("Effect not supported: ${e.message}")
            effectsOk = false
            appliedFx = "off"
        }
        fresh.addListener(playerListener)
        fresh.setMediaItems(items, idx, pos)
        fresh.playbackParameters = PlaybackParameters(c.speed)
        fresh.volume = c.volume
        fresh.prepare()
        fresh.playWhenReady = play
        player = fresh
        onPlayerReplaced?.invoke(fresh)
        old.removeListener(playerListener)
        old.release()
    }

    /** Applies speed, volume and effects of the clip under the playhead. */
    private fun applyLook(force: Boolean) {
        val c = project.mainClipAt(playheadMs) ?: return
        if (!effectsOk && c.id != failedId) { effectsOk = true; appliedFx = "\u0000" }
        val key = "${c.id}|${c.paramSig()}"
        if (!force && key == appliedKey) return
        appliedKey = key
        val fxKey = if (effectsOk) "${c.id}|${c.filter}|${c.brightness}|${c.contrast}|${c.saturation}|${c.blur}|${c.cropRatio}|${c.rotation}|${c.flipH}|${c.flipV}|${c.temp}|${c.hue}|${c.lum}|${c.fx}|${c.fxAmount}|${c.maskShape}|${c.maskSize}|${c.maskFeather}|${c.maskInvert}|${c.maskX}|${c.maskY}|${c.denoise}|${c.stab.hashCode()}|${c.animIn}|${c.animInMs}|${c.animOut}|${c.animOutMs}|${c.animCombo}|${c.textMotion}|${c.exposure}|${c.tint}|${c.highlights}|${c.shadows}|${c.vibrance}|${c.fade}|${c.sharpen}|${c.vignette}|${c.grain}|${if (c.hasAnim) c.lengthMs else 0L}|${c.fadeInMs}|${c.fadeOutMs}|${if (c.fadeInMs > 0 || c.fadeOutMs > 0) c.lengthMs else 0L}|" +
            "${c.chroma.enabled}|${c.chroma.key}|${c.chroma.similarity}|${c.chroma.smooth}|${c.chroma.bg}|" +
            c.keyframes.joinToString(",") { "${it.tMs}:${it.scale}:${it.x}:${it.y}:${it.rot}" } else "off"
        if (fxKey != appliedFx) {
            val hadAny = appliedFx != "\u0000"
            appliedFx = fxKey
            val fx = if (effectsOk) {
                try { Fx.previewEffects(c) { playheadMs - c.startMs } } catch (e: Exception) { effectsOk = false; emptyList() }
            } else emptyList()
            // empty -> empty needs no rebuild
            if (hadAny || fx.isNotEmpty() || c.denoise > 0f) rebuild(fx)
        }
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

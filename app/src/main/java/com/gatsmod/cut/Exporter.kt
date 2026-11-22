@file:OptIn(UnstableApi::class)

package com.gatsmod.cut

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.FileProvider
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ExportSettings(val shortSide: Int, val fps: Int)

/** Turns the project into a real MP4 using Media3 Transformer (same effects as the preview). */
class Exporter(private val ctx: Context) {

    interface Callback {
        fun onDone(file: File)
        fun onError(message: String)
    }

    private var transformer: Transformer? = null
    private val holder = ProgressHolder()

    val running: Boolean get() = transformer != null

    /** 0..100, or -1 when not known yet. */
    fun progress(): Int {
        val t = transformer ?: return -1
        return if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
    }

    fun outputSize(p: Project, shortSide: Int): Size {
        val aspect = p.effectiveAspect()
        var w: Int
        var h: Int
        if (aspect >= 1f) { h = shortSide; w = (shortSide * aspect).roundToInt() } else { w = shortSide; h = (shortSide / aspect).roundToInt() }
        w = max(2, w / 2 * 2)
        h = max(2, h / 2 * 2)
        return Size(w, h)
    }

    fun start(p: Project, s: ExportSettings, cb: Callback) {
        if (p.main.isEmpty()) { cb.onError("Import a video first"); return }
        val out = File(ctx.cacheDir, "export").apply { mkdirs() }
            .let { File(it, "GatsMod_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4") }
        val composition = try {
            build(p, s)
        } catch (e: Exception) {
            cb.onError("Could not prepare export: ${e.message}")
            return
        }
        val bitrate = ((when {
            s.shortSide >= 2000 -> 35_000_000
            s.shortSide >= 1000 -> 10_000_000
            else -> 5_000_000
        }) * (s.fps / 30f).coerceIn(0.8f, 2f)).toInt()
        val encoder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .build()
        val t = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    cb.onDone(out)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    transformer = null
                    out.delete()
                    cb.onError(exportException.message ?: "Export failed")
                }
            })
            .build()
        transformer = t
        t.start(composition, out.absolutePath)
    }

    private fun build(p: Project, s: ExportSettings): Composition {
        val size = outputSize(p, s.shortSide)
        val totalMs = p.totalMs()

        // ---- main video track ----
        val videoItems = p.main.map { c ->
            val audioProcs = mutableListOf<AudioProcessor>()
            val fx = mutableListOf<Effect>()
            fx.addAll(Fx.lookEffects(c))
            if (c.hasAudio && c.denoise > 0f) audioProcs.add(DenoiseProcessor(c.denoise))
            if (c.speed != 1f) {
                if (c.hasAudio) {
                    val pair = Effects.createExperimentalSpeedChangingEffect(ConstantSpeed(c.speed))
                    audioProcs.add(pair.first)
                    fx.add(pair.second)
                } else {
                    fx.add(SpeedChangeEffect(c.speed))
                }
            }
            if (c.hasAudio && (c.fadeInMs > 0 || c.fadeOutMs > 0)) audioProcs.add(FadeProcessor(c.volume, c.fadeInMs, c.fadeOutMs, c.lengthMs))
            else if (c.hasAudio && c.volume != 1f) audioProcs.add(VolumeProcessor(c.volume))
            if (c.stab.isNotEmpty()) {
                var firstS = Long.MIN_VALUE
                fx.add(KeyframeTransform(c.stab) { t ->
                    if (firstS == Long.MIN_VALUE) firstS = t
                    c.srcInMs + ((t - firstS) / 1000L * c.speed).toLong()
                })
            }
            if (c.hasAnim) {
                var firstA = Long.MIN_VALUE
                val clock: (Long) -> Long = { t -> if (firstA == Long.MIN_VALUE) firstA = t; (t - firstA) / 1000L }
                fx.add(AnimTransform(c.animIn, c.animInMs, c.animOut, c.animOutMs, c.animCombo, c.lengthMs, clock))
                fx.add(AnimGain(c.animIn, c.animInMs, c.animOut, c.animOutMs, c.animCombo, c.lengthMs, clock))
            }
            if (c.keyframes.isNotEmpty()) {
                var first = Long.MIN_VALUE
                fx.add(KeyframeTransform(c.keyframes) { t ->
                    if (first == Long.MIN_VALUE) first = t
                    (t - first) / 1000L
                })
            }
            if (c.fadeInMs > 0 || c.fadeOutMs > 0) {
                var first0 = Long.MIN_VALUE
                fx.add(FadeMatrix(c.fadeInMs, c.fadeOutMs, c.lengthMs) { t ->
                    if (first0 == Long.MIN_VALUE) first0 = t
                    (t - first0) / 1000L
                })
            }
            fx.add(Presentation.createForWidthAndHeight(size.width, size.height, Presentation.LAYOUT_SCALE_TO_FIT))
            EditedMediaItem.Builder(clipped(c.uri, c.srcInMs, c.srcOutMs))
                .setEffects(Effects(audioProcs, fx))
                .build()
        }
        val sequences = mutableListOf(EditedMediaItemSequence.Builder(videoItems).build())

        // ---- audio lane: clips that overlap each other go on separate mixing tracks ----
        val tracks = mutableListOf<MutableList<Clip>>()
        for (a in p.audio.sortedBy { it.startMs }) {
            if (a.startMs >= totalMs) continue
            val track = tracks.firstOrNull { it.last().endMs <= a.startMs } ?: mutableListOf<Clip>().also { tracks.add(it) }
            track.add(a)
        }
        for (track in tracks) {
            val seq = EditedMediaItemSequence.Builder()
            var added = false
            var cursor = 0L
            for (a in track) {
                if (a.startMs > cursor) seq.addGap((a.startMs - cursor) * 1000L)
                val lenMs = min(a.lengthMs, totalMs - a.startMs)
                val srcOut = a.srcInMs + (lenMs * a.speed).toLong()
                val procs = mutableListOf<AudioProcessor>()
                if (a.denoise > 0f) procs.add(DenoiseProcessor(a.denoise))
                if (a.speed != 1f) procs.add(Effects.createExperimentalSpeedChangingEffect(ConstantSpeed(a.speed)).first)
                if (a.fadeInMs > 0 || a.fadeOutMs > 0) procs.add(FadeProcessor(a.volume, a.fadeInMs, a.fadeOutMs, lenMs))
                else if (a.volume != 1f) procs.add(VolumeProcessor(a.volume))
                seq.addItem(
                    EditedMediaItem.Builder(clipped(a.uri, a.srcInMs, srcOut))
                        .setRemoveVideo(true)
                        .setEffects(Effects(procs, emptyList()))
                        .build()
                )
                added = true
                cursor = a.startMs + lenMs
            }
            if (added) sequences.add(seq.build())
        }

        // ---- whole-video effects: frame rate + text / sticker overlays ----
        val whole = mutableListOf<Effect>()
        val maxFps = p.main.maxOf { it.fps }
        if (s.fps < maxFps - 0.5f) whole.add(FrameDropEffect.createDefaultFrameDropEffect(s.fps.toFloat()))
        val overlays = mutableListOf<TextureOverlay>()
        for (o in p.overlay + p.text) {
            if (o.startMs >= totalMs) continue
            overlays.add(TimedOverlay(ctx, o.deepCopy(), o.startMs * 1000L, min(o.endMs, totalMs) * 1000L))
        }
        if (overlays.isNotEmpty()) whole.add(OverlayEffect(overlays))

        val mixedAudio = p.main.any { it.hasAudio } && p.main.any { !it.hasAudio }
        return Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), whole))
            .experimentalSetForceAudioTrack(mixedAudio)
            .build()
    }

    private fun clipped(uri: String, inMs: Long, outMs: Long): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(inMs).setEndPositionMs(outMs).build()
            )
            .build()

    companion object {
        /** Copies the finished file into Movies/GatsMod Cut. Returns a content Uri that can be opened or shared. */
        fun saveToGallery(ctx: Context, file: File): Uri? {
            return try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/GatsMod Cut")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val resolver = ctx.contentResolver
                    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                    resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    file.delete()
                    uri
                } else {
                    val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "GatsMod Cut").apply { mkdirs() }
                    val dst = File(dir, file.name)
                    file.copyTo(dst, overwrite = true)
                    file.delete()
                    FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", dst)
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

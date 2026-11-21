@file:OptIn(UnstableApi::class)

package com.gatsmod.cut

import android.graphics.Matrix
import android.util.Size
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * All video effects live here and are used by BOTH the live preview and the exporter,
 * so what you see in the editor is what gets exported.
 */
object Fx {
    val FILTER_NAMES = listOf("None", "Vivid", "Warm", "Cool", "Mono", "Sepia", "Fade", "Invert")
    val CROP_NAMES = listOf("Original", "1:1", "9:16", "16:9", "4:5", "4:3")
    val CROP_RATIOS = listOf(0f, 1f, 9f / 16f, 16f / 9f, 4f / 5f, 4f / 3f)

    val FONT_NAMES = listOf("Sans", "Serif", "Mono", "Cursive", "Condensed", "Casual")

    /** Rotate/flip, crop, chroma key, colour adjustments, filter preset and blur for one clip. */
    fun lookEffects(c: Clip): List<Effect> {
        val l = mutableListOf<Effect>()
        if (c.rotation % 360 != 0 || c.flipH || c.flipV) {
            l.add(
                ScaleAndRotateTransformation.Builder()
                    .setScale(if (c.flipH) -1f else 1f, if (c.flipV) -1f else 1f)
                    .setRotationDegrees(((360 - c.rotation % 360) % 360).toFloat())
                    .build()
            )
        }
        if (c.cropRatio > 0f) l.add(AspectCrop(c.cropRatio))
        if (c.chroma.enabled) l.add(ChromaLut.create(c.chroma))
        if (abs(c.brightness) > 0.001f) l.add(Brightness(c.brightness.coerceIn(-1f, 1f)))
        if (abs(c.contrast - 1f) > 0.001f) l.add(Contrast((c.contrast - 1f).coerceIn(-1f, 1f)))
        if (abs(c.temp) > 0.01f) {
            l.add(RgbAdjustment.Builder().setRedScale(1f + 0.25f * c.temp).setBlueScale(1f - 0.25f * c.temp).build())
        }
        val sat = (c.saturation - 1f) * 100f
        if (abs(sat) > 0.1f || abs(c.hue) > 0.1f || abs(c.lum) > 0.1f) {
            l.add(
                HslAdjustment.Builder()
                    .adjustHue(c.hue)
                    .adjustSaturation(sat.coerceIn(-100f, 100f))
                    .adjustLightness(c.lum.coerceIn(-100f, 100f))
                    .build()
            )
        }
        when (c.filter) {
            1 -> { l.add(HslAdjustment.Builder().adjustSaturation(35f).build()); l.add(Contrast(0.12f)) }
            2 -> l.add(RgbAdjustment.Builder().setRedScale(1.12f).setBlueScale(0.85f).build())
            3 -> l.add(RgbAdjustment.Builder().setRedScale(0.88f).setBlueScale(1.15f).build())
            4 -> l.add(RgbFilter.createGrayscaleFilter())
            5 -> l.add(SepiaMatrix())
            6 -> { l.add(Contrast(-0.2f)); l.add(Brightness(0.06f)); l.add(HslAdjustment.Builder().adjustSaturation(-25f).build()) }
            7 -> l.add(RgbFilter.createInvertedFilter())
        }
        if (c.blur > 0.1f) l.add(GaussianBlur(c.blur.coerceAtMost(25f)))
        return l
    }

    /** Effects for the live preview. [localMs] returns the playhead position inside this clip. */
    fun previewEffects(c: Clip, localMs: () -> Long): List<Effect> {
        val l = lookEffects(c).toMutableList()
        if (c.keyframes.isNotEmpty()) l.add(KeyframeTransform(c.keyframes) { localMs() })
        return l
    }
}

/** Sepia as a colour matrix (column-major, like RgbFilter). */
class SepiaMatrix : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = M

    companion object {
        private val M = floatArrayOf(
            0.393f, 0.349f, 0.272f, 0f,
            0.769f, 0.686f, 0.534f, 0f,
            0.189f, 0.168f, 0.131f, 0f,
            0f, 0f, 0f, 1f
        )
    }
}

/** Centre-crops the frame to a given width/height ratio. */
class AspectCrop(private val ratio: Float) : MatrixTransformation {
    private var sx = 1f
    private var sy = 1f

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val inAspect = inputWidth.toFloat() / max(1, inputHeight)
        return if (inAspect > ratio) {
            sx = inAspect / ratio; sy = 1f
            Size(max(2, (inputHeight * ratio).toInt() / 2 * 2), inputHeight)
        } else {
            sx = 1f; sy = ratio / inAspect
            Size(inputWidth, max(2, (inputWidth / ratio).toInt() / 2 * 2))
        }
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix = Matrix().apply { setScale(sx, sy) }
}

/** Animated scale / position / rotation driven by [Keyframe]s (linear interpolation). */
class KeyframeTransform(frames: List<Keyframe>, private val localMs: (Long) -> Long) : MatrixTransformation {
    private val frames = frames.map { it.copy() }.sortedBy { it.tMs }
    private var aspect = 1f

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        aspect = inputWidth.toFloat() / max(1, inputHeight)
        return Size(inputWidth, inputHeight)
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val k = Keyframes.valueAt(frames, localMs(presentationTimeUs))
        val m = Matrix()
        // rotate in pixel space (not NDC) so the picture does not shear on non-square frames
        m.setScale(aspect, 1f)
        m.postRotate(-k.rot)
        m.postScale(1f / aspect, 1f)
        m.postScale(k.scale, k.scale)
        m.postTranslate(k.x, k.y)
        return m
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = frames.isEmpty()
}

/** Chroma key built as a 3D colour lookup table: pixels close to the key colour become the background colour. */
object ChromaLut {
    private const val N = 33
    private var lastKey: String? = null
    private var lastCube: Array<Array<IntArray>>? = null

    fun create(ch: Chroma): SingleColorLut = SingleColorLut.createFromCube(cube(ch))

    @Synchronized
    private fun cube(ch: Chroma): Array<Array<IntArray>> {
        val key = "${ch.key}|${ch.similarity}|${ch.smooth}|${ch.bg}"
        lastCube?.let { if (key == lastKey) return it }
        fun cb(r: Float, g: Float, b: Float) = -0.169f * r - 0.331f * g + 0.5f * b
        fun cr(r: Float, g: Float, b: Float) = 0.5f * r - 0.419f * g - 0.081f * b
        val kr = ((ch.key shr 16) and 0xFF) / 255f
        val kg = ((ch.key shr 8) and 0xFF) / 255f
        val kb = (ch.key and 0xFF) / 255f
        val kCb = cb(kr, kg, kb)
        val kCr = cr(kr, kg, kb)
        val br = ((ch.bg shr 16) and 0xFF) / 255f
        val bgG = ((ch.bg shr 8) and 0xFF) / 255f
        val bb = (ch.bg and 0xFF) / 255f
        val inner = ch.similarity.coerceIn(0.01f, 1f) * 0.5f
        val outer = inner + ch.smooth.coerceIn(0.01f, 1f) * 0.5f
        val out = Array(N) { r ->
            Array(N) { g ->
                IntArray(N) { b ->
                    val rf = r / (N - 1f)
                    val gf = g / (N - 1f)
                    val bf = b / (N - 1f)
                    val dCb = cb(rf, gf, bf) - kCb
                    val dCr = cr(rf, gf, bf) - kCr
                    val d = sqrt(dCb * dCb + dCr * dCr)
                    val t = ((d - inner) / (outer - inner)).coerceIn(0f, 1f)
                    val m = t * t * (3f - 2f * t) // keep original colour where m = 1, background where m = 0
                    fun mix(bgV: Float, orig: Float) = ((bgV + (orig - bgV) * m) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    (0xFF shl 24) or (mix(br, rf) shl 16) or (mix(bgG, gf) shl 8) or mix(bb, bf)
                }
            }
        }
        lastKey = key
        lastCube = out
        return out
    }
}

class ConstantSpeed(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

/** Simple volume control for 16-bit PCM audio. */
class VolumeProcessor(private val volume: Float) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())
        val inp = inputBuffer.order(ByteOrder.nativeOrder())
        while (inp.remaining() >= 2) {
            val s = (inp.getShort() * volume).toInt().coerceIn(-32768, 32767)
            out.putShort(s.toShort())
        }
        out.flip()
    }
}

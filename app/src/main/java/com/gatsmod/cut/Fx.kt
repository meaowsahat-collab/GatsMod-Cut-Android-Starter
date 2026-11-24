@file:OptIn(UnstableApi::class)

package com.gatsmod.cut

import android.graphics.Matrix
import androidx.media3.common.util.Size
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
    /** sat: HSL saturation change (-100..100), con: contrast (-1..1), br: brightness (-1..1), r/g/b: channel scales, hue: degrees, mode 1 grey / 2 sepia / 3 invert. */
    class FilterDef(val name: String, val sat: Float = 0f, val con: Float = 0f, val br: Float = 0f,
                    val r: Float = 1f, val g: Float = 1f, val b: Float = 1f, val hue: Float = 0f, val mode: Int = 0)

    val FILTERS = listOf(
        FilterDef("None"),
        FilterDef("Vivid", sat = 35f, con = .12f), FilterDef("Warm", r = 1.12f, b = .85f), FilterDef("Cool", r = .88f, b = 1.15f),
        FilterDef("Mono", mode = 1), FilterDef("Sepia", mode = 2), FilterDef("Fade", sat = -25f, con = -.2f, br = .06f), FilterDef("Invert", mode = 3),
        FilterDef("Clear", sat = 15f, con = .08f, br = .02f), FilterDef("Natural", sat = 8f, con = .04f, br = .03f),
        FilterDef("Fresh", sat = 20f, con = .05f, br = .06f, r = .98f, g = 1.04f, b = 1.04f),
        FilterDef("Sunny", sat = 20f, con = .05f, br = .05f, r = 1.12f, g = 1.05f, b = .82f),
        FilterDef("Golden", sat = 15f, con = .06f, br = .03f, r = 1.15f, g = 1.02f, b = .78f),
        FilterDef("Sunset", sat = 25f, con = .1f, r = 1.18f, g = .95f, b = .8f),
        FilterDef("Peach", sat = 10f, con = -.04f, br = .08f, r = 1.1f, g = .98f, b = .95f),
        FilterDef("Rose", sat = 15f, con = -.02f, br = .05f, r = 1.1f, g = .95f, b = 1.02f),
        FilterDef("Candy", sat = 40f, con = -.05f, br = .08f, r = 1.05f, g = .98f, b = 1.08f),
        FilterDef("Pastel", sat = -15f, con = -.12f, br = .14f, r = 1.02f, g = 1.02f, b = 1.04f),
        FilterDef("Dreamy", sat = -10f, con = -.15f, br = .12f, r = 1.04f, b = 1.08f),
        FilterDef("Film", sat = -12f, con = .1f, r = 1.06f, g = 1.02f, b = .9f),
        FilterDef("Kodak", sat = 10f, con = .12f, br = .02f, r = 1.1f, b = .88f),
        FilterDef("Vintage", sat = -25f, con = .05f, br = .04f, r = 1.12f, g = 1.02f, b = .82f),
        FilterDef("Retro", sat = -18f, con = .12f, r = 1.08f, b = .88f, hue = 8f),
        FilterDef("Old photo", sat = -5f, con = .1f, mode = 2),
        FilterDef("Noir", con = .35f, br = -.05f, mode = 1),
        FilterDef("B&W Soft", con = -.1f, br = .05f, mode = 1),
        FilterDef("B&W High", con = .5f, mode = 1),
        FilterDef("Silver", con = .15f, r = .98f, b = 1.04f, mode = 1),
        FilterDef("Cinema", sat = 10f, con = .2f, br = -.02f, r = 1.06f, b = .94f),
        FilterDef("Teal Orange", sat = 22f, con = .16f, r = 1.12f, g = .98f, b = .88f),
        FilterDef("Moody", sat = -20f, con = .25f, br = -.06f, r = .96f, b = 1.06f),
        FilterDef("Dark", sat = -10f, con = .3f, br = -.12f),
        FilterDef("Matte", sat = -20f, con = -.18f, br = .1f, b = 1.03f),
        FilterDef("Cold", sat = -5f, con = .05f, r = .86f, b = 1.18f),
        FilterDef("Ice", sat = -10f, br = .06f, r = .82f, b = 1.2f),
        FilterDef("Ocean", sat = 15f, con = .08f, r = .85f, g = 1.02f, b = 1.15f),
        FilterDef("Forest", sat = 18f, con = .08f, br = -.02f, r = .92f, g = 1.1f, b = .88f),
        FilterDef("Spring", sat = 25f, con = .04f, br = .06f, r = .96f, g = 1.1f, b = .95f),
        FilterDef("Autumn", sat = 20f, con = .1f, r = 1.15f, b = .8f),
        FilterDef("Cyber", sat = 40f, con = .2f, r = .9f, b = 1.25f, hue = 320f),
        FilterDef("Neon", sat = 60f, con = .2f, b = 1.1f, hue = 20f),
        FilterDef("Cherry", sat = 30f, con = .1f, br = -.02f, r = 1.2f, g = .92f, b = .95f),
        FilterDef("Violet", sat = 25f, con = .08f, r = 1.05f, g = .92f, b = 1.2f),
        FilterDef("Haze", sat = -25f, con = -.2f, br = .15f),
        FilterDef("HDR", sat = 45f, con = .3f),
        FilterDef("Punch", sat = 30f, con = .22f),
        FilterDef("Gloom", sat = -40f, con = .15f, br = -.05f, r = .95f, b = 1.05f),
        FilterDef("Lomo", sat = 35f, con = .25f, br = -.05f, r = 1.1f, b = .9f)
    )
    val FILTER_NAMES get() = FILTERS.map { it.name }
    val CROP_NAMES = listOf("Original", "1:1", "9:16", "16:9", "4:5", "4:3")
    val CROP_RATIOS = listOf(0f, 1f, 9f / 16f, 16f / 9f, 4f / 5f, 4f / 3f)

    val FONT_NAMES = listOf("Sans", "Serif", "Mono", "Cursive", "Condensed", "Casual", "Black", "Light", "Medium", "Thin")

    /** Rotate/flip, crop, chroma key, colour adjustments, filter preset and blur for one clip. */
    fun lookEffects(c: Clip, live: Boolean = false): List<Effect> {
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
        if (!live && abs(c.brightness) > 0.001f) l.add(Brightness(c.brightness.coerceIn(-1f, 1f)))
        if (!live && abs(c.contrast - 1f) > 0.001f) l.add(Contrast((c.contrast - 1f).coerceIn(-1f, 1f)))
        if (!live && abs(c.temp) > 0.01f) {
            l.add(RgbAdjustment.Builder().setRedScale(1f + 0.25f * c.temp).setBlueScale(1f - 0.25f * c.temp).build())
        }
        val sat = (c.saturation - 1f) * 100f
        if (!live && (abs(sat) > 0.1f || abs(c.hue) > 0.1f || abs(c.lum) > 0.1f)) {
            l.add(
                HslAdjustment.Builder()
                    .adjustHue(c.hue)
                    .adjustSaturation(sat.coerceIn(-100f, 100f))
                    .adjustLightness(c.lum.coerceIn(-100f, 100f))
                    .build()
            )
        }
        val fd = FILTERS.getOrNull(c.filter)
        if (fd != null && c.filter != 0) {
            when (fd.mode) { 1 -> l.add(RgbFilter.createGrayscaleFilter()); 2 -> l.add(SepiaMatrix()); 3 -> l.add(RgbFilter.createInvertedFilter()) }
            if (fd.sat != 0f || fd.hue != 0f) l.add(HslAdjustment.Builder().adjustHue(fd.hue).adjustSaturation(fd.sat).build())
            if (fd.con != 0f) l.add(Contrast(fd.con))
            if (fd.br != 0f) l.add(Brightness(fd.br))
            if (fd.r != 1f || fd.g != 1f || fd.b != 1f) l.add(RgbAdjustment.Builder().setRedScale(fd.r).setGreenScale(fd.g).setBlueScale(fd.b).build())
        }
        if (!live && (c.exposure != 0f || c.tint != 0f)) {
            val e = Math.pow(2.0, c.exposure.toDouble() * 0.8).toFloat()
            l.add(RgbAdjustment.Builder().setRedScale(e * (1f + 0.12f * c.tint)).setGreenScale(e * (1f - 0.2f * c.tint)).setBlueScale(e * (1f + 0.12f * c.tint)).build())
        }
        if (live) l.add(ToneGlEffect({ floatArrayOf(c.highlights, c.shadows, c.vibrance, c.fade, c.sharpen, c.vignette, c.grain) }, true))
        else if (c.highlights != 0f || c.shadows != 0f || c.vibrance != 0f || c.fade != 0f || c.sharpen != 0f || c.vignette != 0f || c.grain != 0f) {
            l.add(ToneGlEffect(floatArrayOf(c.highlights, c.shadows, c.vibrance, c.fade, c.sharpen, c.vignette, c.grain)))
        }
        if (!(live && android.os.Build.VERSION.SDK_INT >= 31) && c.blur > 0.1f) l.add(GaussianBlur(c.blur.coerceAtMost(25f)))
        if (live) {
            l.add(LookGlEffect(LookParams(0, 0f, 0, 1f, 0.08f, false, 0f, 0f, c)))
        } else if (c.fx != 0 || c.maskShape != 0) {
            l.add(LookGlEffect(LookParams(c.fx, c.fxAmount, c.maskShape, c.maskSize, c.maskFeather, c.maskInvert, c.maskX, c.maskY)))
        }
        return l
    }

    /** Effects for the live preview. [localMs] returns the playhead position inside this clip. */
    fun previewEffects(c: Clip, live: Boolean = false, localMs: () -> Long): List<Effect> {
        val l = lookEffects(c, live).toMutableList()
        if (c.stab.isNotEmpty()) l.add(KeyframeTransform(c.stab) { c.srcInMs + (localMs() * c.speed).toLong() })
        if (c.hasAnim || live) {
            if (Anim.isMask(c.animIn) || Anim.isMask(c.animOut)) {
                l.add(LookGlEffect(LookParams(0, 0f, 0, 1f, 0.08f, false, 0f, 0f) { Anim.maskState(c.animIn, c.animInMs, c.animOut, c.animOutMs, localMs(), c.lengthMs) }))
            }
            if (live) {
                l.add(LiveAnimTransform(c) { localMs() })
                l.add(LiveAnimGain(c) { localMs() })
            } else {
                l.add(AnimTransform(c.animIn, c.animInMs, c.animOut, c.animOutMs, c.animCombo, c.lengthMs) { localMs() })
                l.add(AnimGain(c.animIn, c.animInMs, c.animOut, c.animOutMs, c.animCombo, c.lengthMs) { localMs() })
            }
        }
        if (live) {
            l.add(LiveKeyframeTransform(c) { localMs() })
            l.add(LiveFadeMatrix(c) { localMs() })
        } else {
            if (c.keyframes.isNotEmpty()) l.add(KeyframeTransform(c.keyframes) { localMs() })
            if (c.fadeInMs > 0 || c.fadeOutMs > 0) l.add(FadeMatrix(c.fadeInMs, c.fadeOutMs, c.lengthMs) { localMs() })
        }
        return l
    }
}

/** Fades the picture from / to black. [localMs] gives the position inside the clip (timeline time). */
/** Moves / scales / rotates the picture for the clip's In, Out and Combo animations. */
class AnimTransform(private val ai: Int, private val aim: Long, private val ao: Int, private val aom: Long, private val ac: Int,
                    private val lenMs: Long, private val localMs: (Long) -> Long) : MatrixTransformation {
    private var aspect = 1f
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        aspect = inputWidth.toFloat() / max(1, inputHeight)
        return Size(inputWidth, inputHeight)
    }
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val p = Anim.pose(ai, aim, ao, aom, ac, localMs(presentationTimeUs), lenMs)
        val m = Matrix()
        m.setScale(aspect, 1f)
        m.postRotate(-p.rot)
        m.postScale(1f / aspect, 1f)
        m.postScale(p.sx, p.sy)
        m.postTranslate(p.x, p.y)
        return m
    }
}

/** Brightness part of the animations (fade, flash, blink...). */
class AnimGain(private val ai: Int, private val aim: Long, private val ao: Int, private val aom: Long, private val ac: Int,
               private val lenMs: Long, private val localMs: (Long) -> Long) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val g = Anim.pose(ai, aim, ao, aom, ac, localMs(presentationTimeUs), lenMs).gain
        return floatArrayOf(g, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 1f)
    }
}

class FadeMatrix(private val inMs: Long, private val outMs: Long, private val lenMs: Long, private val localMs: (Long) -> Long) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val g = Fade.gain(localMs(presentationTimeUs), lenMs, inMs, outMs)
        return floatArrayOf(g, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 1f)
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

/** Volume plus fade in / fade out for 16-bit PCM audio. Expects audio already at timeline speed. */
class FadeProcessor(private val volume: Float, private val inMs: Long, private val outMs: Long, private val lenMs: Long) : BaseAudioProcessor() {
    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun onFlush() { frames = 0L }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val ch = max(1, inputAudioFormat.channelCount)
        val rate = max(1, inputAudioFormat.sampleRate)
        val out = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())
        val inp = inputBuffer.order(ByteOrder.nativeOrder())
        while (inp.remaining() >= 2 * ch) {
            val g = volume * Fade.gain(frames * 1000L / rate, lenMs, inMs, outMs)
            for (i in 0 until ch) out.putShort((inp.getShort() * g).toInt().coerceIn(-32768, 32767).toShort())
            frames++
        }
        out.flip()
    }
}

/** Noise reduction for 16-bit PCM audio (see [DenoiseCore]). */
class DenoiseProcessor(private val level: Float) : BaseAudioProcessor() {
    private var core: DenoiseCore? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun onFlush() {
        core = DenoiseCore(max(1, inputAudioFormat.channelCount), max(8000, inputAudioFormat.sampleRate), level)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val ch = max(1, inputAudioFormat.channelCount)
        val cr = core ?: DenoiseCore(ch, max(8000, inputAudioFormat.sampleRate), level).also { core = it }
        val inp = inputBuffer.order(ByteOrder.nativeOrder())
        val frames = size / (2 * ch)
        val data = FloatArray(frames * ch)
        for (i in data.indices) data[i] = inp.getShort() / 32768f
        cr.process(data, frames)
        val out = replaceOutputBuffer(frames * ch * 2).order(ByteOrder.nativeOrder())
        for (v in data) out.putShort((v * 32768f).toInt().coerceIn(-32768, 32767).toShort())
        out.flip()
        // any odd trailing bytes are dropped
        while (inp.hasRemaining()) inp.get()
    }
}


// ---- Live variants (preview only): re-read the clip's values on every frame so sliders act while being dragged.

class LiveAnimTransform(private val c: Clip, private val localMs: (Long) -> Long) : MatrixTransformation {
    private var aspect = 1f
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        aspect = inputWidth.toFloat() / max(1, inputHeight)
        return Size(inputWidth, inputHeight)
    }
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val p = Anim.pose(c.animIn, c.animInMs, c.animOut, c.animOutMs, c.animCombo, localMs(presentationTimeUs), c.lengthMs)
        val m = Matrix()
        m.setScale(aspect, 1f)
        m.postRotate(-p.rot)
        m.postScale(1f / aspect, 1f)
        m.postScale(p.sx, p.sy)
        m.postTranslate(p.x, p.y)
        return m
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

class LiveAnimGain(private val c: Clip, private val localMs: (Long) -> Long) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val g = Anim.pose(c.animIn, c.animInMs, c.animOut, c.animOutMs, c.animCombo, localMs(presentationTimeUs), c.lengthMs).gain
        return floatArrayOf(g, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 1f)
    }
}

class LiveFadeMatrix(private val c: Clip, private val localMs: (Long) -> Long) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val g = Fade.gain(localMs(presentationTimeUs), c.lengthMs, c.fadeInMs, c.fadeOutMs)
        return floatArrayOf(g, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 1f)
    }
}

class LiveKeyframeTransform(private val c: Clip, private val localMs: (Long) -> Long) : MatrixTransformation {
    private var aspect = 1f
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        aspect = inputWidth.toFloat() / max(1, inputHeight)
        return Size(inputWidth, inputHeight)
    }
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val m = Matrix()
        val frames = c.keyframes.toList().sortedBy { it.tMs }
        if (frames.isEmpty()) return m
        val k = Keyframes.valueAt(frames, localMs(presentationTimeUs))
        m.setScale(aspect, 1f)
        m.postRotate(-k.rot)
        m.postScale(1f / aspect, 1f)
        m.postScale(k.scale, k.scale)
        m.postTranslate(k.x, k.y)
        return m
    }
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

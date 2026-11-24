@file:OptIn(UnstableApi::class)

package com.gatsmod.cut

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** Settings for the one custom shader that draws creative effects and shape masks. */
class LookParams(
    val effect: Int,
    val amount: Float,
    val mask: Int,
    val maskSize: Float,
    val maskFeather: Float,
    val maskInvert: Boolean,
    val maskX: Float,
    val maskY: Float,
    /** Optional: gives [shape, size] of the mask for a frame time (microseconds) — used by the reveal animations. */
    val dyn: ((Long) -> FloatArray)? = null
)

object GlFxNames {
    val EFFECTS = listOf("None", "Vignette", "Sharpen", "Pixelate", "Glitch", "Film grain", "Scanlines", "Mirror", "Glow", "Wave", "Poster")
    val MASKS = listOf("None", "Circle", "Rectangle", "Diamond", "Heart")
}

class LookGlEffect(private val p: LookParams) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = LookProgram(p, useHdr)
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = p.effect == 0 && p.mask == 0 && p.dyn == null
}

private class LookProgram(private val p: LookParams, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram
    private var w = 1f
    private var h = 1f

    init {
        try {
            program = GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        val identity = GlUtil.create4x4IdentityMatrix()
        program.setFloatsUniform("uTransformationMatrix", identity)
        program.setFloatsUniform("uTexTransformationMatrix", identity)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        w = inputWidth.toFloat()
        h = inputHeight.toFloat()
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setIntUniform("uEffect", p.effect)
            program.setFloatUniform("uAmount", p.amount)
            val dm = p.dyn?.invoke(presentationTimeUs)
            program.setIntUniform("uMask", dm?.get(0)?.toInt() ?: p.mask)
            program.setFloatUniform("uMaskSize", dm?.get(1) ?: p.maskSize)
            program.setFloatUniform("uMaskFeather", p.maskFeather)
            program.setFloatUniform("uMaskInvert", if (p.maskInvert) 1f else 0f)
            program.setFloatsUniform("uMaskPos", floatArrayOf(p.maskX * 0.5f, p.maskY * 0.5f))
            program.setFloatsUniform("uRes", floatArrayOf(w, h))
            program.setFloatUniform("uTime", ((presentationTimeUs / 1000L) % 100000L) / 1000f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        private const val VERTEX = """#version 100
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
"""

        private const val FRAGMENT = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform int uEffect;
uniform float uAmount;
uniform int uMask;
uniform float uMaskSize;
uniform float uMaskFeather;
uniform float uMaskInvert;
uniform vec2 uMaskPos;
uniform vec2 uRes;
uniform float uTime;
varying vec2 vTexSamplingCoord;

float rand(vec2 co) {
  return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

vec4 tex(vec2 uv) {
  return texture2D(uTexSampler, clamp(uv, 0.0, 1.0));
}

void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 px = 1.0 / uRes;
  if (uEffect == 3) {
    vec2 b = px * max(1.0, uAmount * 48.0);
    uv = (floor(uv / b) + 0.5) * b;
  }
  if (uEffect == 4) {
    float band = step(0.92, rand(vec2(floor(uv.y * 24.0), floor(uTime * 12.0))));
    uv.x += band * uAmount * 0.06;
  }
  if (uEffect == 7) {
    if (uv.x > 0.5) uv.x = 1.0 - uv.x;
  }
  if (uEffect == 9) {
    uv.x += sin(uv.y * 18.0 + uTime * 4.0) * 0.036 * uAmount;
  }
  vec4 col = tex(uv);
  if (uEffect == 1) {
    float r = length((uv - 0.5) * vec2(uRes.x / uRes.y, 1.0));
    col.rgb *= 1.0 - uAmount * smoothstep(0.25, 0.95, r);
  } else if (uEffect == 2) {
    vec3 n = tex(uv + vec2(px.x, 0.0)).rgb + tex(uv - vec2(px.x, 0.0)).rgb
           + tex(uv + vec2(0.0, px.y)).rgb + tex(uv - vec2(0.0, px.y)).rgb;
    float k = uAmount * 0.8;
    col.rgb = col.rgb * (1.0 + 4.0 * k) - k * n;
  } else if (uEffect == 4) {
    float off = uAmount * 0.02;
    col.r = tex(uv + vec2(off, 0.0)).r;
    col.b = tex(uv - vec2(off, 0.0)).b;
  } else if (uEffect == 5) {
    col.rgb += (rand(uv * uRes + uTime) - 0.5) * uAmount * 0.5;
  } else if (uEffect == 6) {
    col.rgb *= 1.0 - uAmount * 0.4 * (0.5 + 0.5 * sin(uv.y * uRes.y * 3.14159));
  } else if (uEffect == 8) {
    vec2 r = px * (2.0 + uAmount * 10.0);
    vec3 s = tex(uv + vec2(r.x, 0.0)).rgb + tex(uv - vec2(r.x, 0.0)).rgb
           + tex(uv + vec2(0.0, r.y)).rgb + tex(uv - vec2(0.0, r.y)).rgb
           + tex(uv + r).rgb + tex(uv - r).rgb
           + tex(uv + vec2(r.x, -r.y)).rgb + tex(uv + vec2(-r.x, r.y)).rgb;
    col.rgb += (s / 8.0) * uAmount * 0.8;
  } else if (uEffect == 10) {
    float levels = mix(16.0, 3.0, uAmount);
    col.rgb = floor(col.rgb * levels + 0.5) / levels;
  }
  if (uMask > 0) {
    vec2 p = vTexSamplingCoord - 0.5 - uMaskPos;
    p.x *= uRes.x / uRes.y;
    float s = max(uMaskSize, 0.02) * 0.5;
    float f = max(uMaskFeather * 0.5, 0.002);
    float d = 0.0;
    if (uMask == 1) {
      d = length(p) - s;
    } else if (uMask == 2) {
      d = max(abs(p.x) - s * 1.3, abs(p.y) - s);
    } else if (uMask == 3) {
      d = abs(p.x) + abs(p.y) - s * 1.2;
    } else {
      vec2 q = vec2(p.x, p.y + s * 0.15) / s;
      float a = q.x * q.x + q.y * q.y - 1.0;
      d = (a * a * a - q.x * q.x * q.y * q.y * q.y) * s * 0.35;
    }
    float inside = 1.0 - smoothstep(-f, f, d);
    if (uMaskInvert > 0.5) inside = 1.0 - inside;
    col.rgb *= inside;
  }
  gl_FragColor = vec4(clamp(col.rgb, 0.0, 1.0), col.a);
}
"""
    }
}


/** Highlights, shadows, vibrance, fade, sharpen, vignette and grain in one pass. [v] = [highlights, shadows, vibrance, fade, sharpen, vignette, grain], each -1..1 (fade, sharpen, grain 0..1). */
class ToneGlEffect(private val src: () -> FloatArray, private val always: Boolean = false) : GlEffect {
    constructor(v: FloatArray) : this({ v })
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = ToneProgram(src, useHdr)
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = !always && src().all { it == 0f }
}

/** Brightness, contrast, saturation, temperature, hue, lightness, exposure and tint in one pass that re-reads the clip's values on every frame, so sliders show their effect instantly while being dragged (preview only). */
class LiveColorEffect(private val c: Clip) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = LiveColorProgram(c, useHdr)
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

private class LiveColorProgram(private val c: Clip, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram

    init {
        try {
            program = GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        val identity = GlUtil.create4x4IdentityMatrix()
        program.setFloatsUniform("uTransformationMatrix", identity)
        program.setFloatsUniform("uTexTransformationMatrix", identity)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uBr", c.brightness)
            program.setFloatUniform("uCon", c.contrast)
            program.setFloatUniform("uSat", c.saturation)
            program.setFloatUniform("uTemp", c.temp)
            program.setFloatUniform("uHue", c.hue)
            program.setFloatUniform("uLum", c.lum)
            program.setFloatUniform("uExp", c.exposure)
            program.setFloatUniform("uTint", c.tint)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        private const val VERTEX = """#version 100
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
"""

        private const val FRAGMENT = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform float uBr;
uniform float uCon;
uniform float uSat;
uniform float uTemp;
uniform float uHue;
uniform float uLum;
uniform float uExp;
uniform float uTint;
varying vec2 vTexSamplingCoord;
void main() {
  vec4 t = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 c = t.rgb + uBr;
  c = (c - 0.5) * uCon + 0.5;
  c.r *= 1.0 + 0.25 * uTemp;
  c.b *= 1.0 - 0.25 * uTemp;
  float l = dot(c, vec3(0.299, 0.587, 0.114));
  c = mix(vec3(l), c, uSat);
  float a = radians(uHue);
  float cs = cos(a);
  float sn = sin(a);
  vec3 k = vec3(0.57735);
  c = c * cs + cross(k, c) * sn + k * dot(k, c) * (1.0 - cs);
  float ll = uLum / 100.0;
  c = mix(c, vec3(ll > 0.0 ? 1.0 : 0.0), abs(ll));
  float e = pow(2.0, uExp * 0.8);
  c *= vec3(e * (1.0 + 0.12 * uTint), e * (1.0 - 0.2 * uTint), e * (1.0 + 0.12 * uTint));
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), t.a);
}
"""
    }
}

private class ToneProgram(private val src: () -> FloatArray, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram
    private var w = 1f
    private var h = 1f

    init {
        try {
            program = GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        val identity = GlUtil.create4x4IdentityMatrix()
        program.setFloatsUniform("uTransformationMatrix", identity)
        program.setFloatsUniform("uTexTransformationMatrix", identity)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        w = inputWidth.toFloat()
        h = inputHeight.toFloat()
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            val v = src()
            program.setFloatUniform("uHi", v[0])
            program.setFloatUniform("uSh", v[1])
            program.setFloatUniform("uVib", v[2])
            program.setFloatUniform("uFade", v[3])
            program.setFloatUniform("uSharp", v[4])
            program.setFloatUniform("uVig", v[5])
            program.setFloatUniform("uGrain", v[6])
            program.setFloatsUniform("uRes", floatArrayOf(w, h))
            program.setFloatUniform("uTime", ((presentationTimeUs / 1000L) % 100000L) / 1000f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        private const val VERTEX = """#version 100
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}
"""

        private const val FRAGMENT = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform float uHi;
uniform float uSh;
uniform float uVib;
uniform float uFade;
uniform float uSharp;
uniform float uVig;
uniform float uGrain;
uniform vec2 uRes;
uniform float uTime;
varying vec2 vTexSamplingCoord;

float rand(vec2 co) {
  return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

vec4 tex(vec2 uv) {
  return texture2D(uTexSampler, clamp(uv, 0.0, 1.0));
}

void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 px = 1.0 / uRes;
  vec4 col = tex(uv);
  if (uSharp > 0.0) {
    vec3 n = tex(uv + vec2(px.x, 0.0)).rgb + tex(uv - vec2(px.x, 0.0)).rgb
           + tex(uv + vec2(0.0, px.y)).rgb + tex(uv - vec2(0.0, px.y)).rgb;
    float k = uSharp * 0.8;
    col.rgb = col.rgb * (1.0 + 4.0 * k) - k * n;
  }
  float l = dot(col.rgb, vec3(0.299, 0.587, 0.114));
  float sw = 1.0 - smoothstep(0.0, 0.55, l);
  float hw = smoothstep(0.45, 1.0, l);
  col.rgb += uSh * 0.35 * sw + uHi * 0.35 * hw;
  float l2 = dot(col.rgb, vec3(0.299, 0.587, 0.114));
  float mx = max(max(col.r, col.g), col.b);
  float mn = min(min(col.r, col.g), col.b);
  float sat = mx - mn;
  col.rgb = mix(vec3(l2), col.rgb, 1.0 + uVib * (1.0 - sat));
  col.rgb = col.rgb * (1.0 - 0.25 * uFade) + 0.12 * uFade;
  float r = length((uv - 0.5) * vec2(uRes.x / uRes.y, 1.0));
  col.rgb *= 1.0 - uVig * smoothstep(0.25, 0.95, r);
  col.rgb += (rand(uv * uRes + uTime) - 0.5) * uGrain * 0.4;
  gl_FragColor = vec4(clamp(col.rgb, 0.0, 1.0), col.a);
}
"""
    }
}

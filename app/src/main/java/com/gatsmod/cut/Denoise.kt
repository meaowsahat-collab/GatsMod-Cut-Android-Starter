package com.gatsmod.cut

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Simple noise reduction: a gentle high-pass (removes rumble) plus a noise gate whose threshold follows the
 * quietest level it has recently heard. Not an AI denoiser, but it quiets steady hiss and hum between speech.
 */
class DenoiseCore(private val channels: Int, private val rate: Int, level: Float) {
    private val lv = level.coerceIn(0f, 1f)
    private val hpA = exp(-2.0 * Math.PI * 90.0 / rate).toFloat()
    private val envAttack = 1f - exp(-1.0 / (0.004 * rate)).toFloat()
    private val envRelease = 1f - exp(-1.0 / (0.15 * rate)).toFloat()
    private val gainDown = 1f - exp(-1.0 / (0.01 * rate)).toFloat()
    private val gainUp = 1f - exp(-1.0 / (0.004 * rate)).toFloat()
    private val floorRise = 1f / (rate * 8f)
    private val prevIn = FloatArray(channels)
    private val prevOut = FloatArray(channels)
    private var env = 0f
    private var floor = 0.05f
    private var gain = 1f

    /** [data] holds interleaved samples in -1..1 and is changed in place. */
    fun process(data: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            var peak = 0f
            for (c in 0 until channels) {
                val x = data[f * channels + c]
                val y = hpA * (prevOut[c] + x - prevIn[c])
                prevIn[c] = x
                prevOut[c] = y
                data[f * channels + c] = y
                peak = max(peak, abs(y))
            }
            env += (peak - env) * (if (peak > env) envAttack else envRelease)
            floor += if (env < floor) (env - floor) * 0.01f else (env - floor) * floorRise
            val threshold = max(floor * (1.6f + lv * 2.4f), 0.003f)
            val target = if (env < threshold) 1f - 0.93f * lv else 1f
            gain += (target - gain) * (if (target < gain) gainDown else gainUp)
            for (c in 0 until channels) data[f * channels + c] *= gain
        }
    }
}

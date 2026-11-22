package com.gatsmod.cut

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Finds beats (strong sound onsets) in mono audio. Pure maths, no Android classes. */
object BeatMath {
    /** [samples] are mono in -1..1 at [rate] Hz. Returns beat times in milliseconds from the start of [samples]. */
    fun detect(samples: FloatArray, rate: Int, minGapMs: Int = 280): List<Long> {
        val hop = max(1, rate / 100)           // 10 ms
        val frames = samples.size / hop - 1
        if (frames < 20) return emptyList()
        // energy of the high-passed signal, per 10 ms
        val e = FloatArray(frames)
        var prev = 0f
        for (f in 0 until frames) {
            var s = 0.0
            for (i in f * hop until (f + 2) * hop) {
                val x = samples[i]
                val d = x - prev
                prev = x
                s += d * d
            }
            e[f] = sqrt(s / (2 * hop)).toFloat()
        }
        // onset strength = rise of the log energy
        val on = FloatArray(frames)
        for (f in 1 until frames) on[f] = max(0f, ln(1f + 60f * e[f]) - ln(1f + 60f * e[f - 1]))
        // smooth a little
        val sm = FloatArray(frames)
        for (f in 0 until frames) {
            var s = 0f
            var n = 0
            for (k in -1..1) { val j = f + k; if (j in 0 until frames) { s += on[j]; n++ } }
            sm[f] = s / n
        }
        // adaptive threshold: local mean over +-0.6 s
        val w = 60
        val picks = ArrayList<Int>()
        for (f in 2 until frames - 2) {
            var s = 0f
            var n = 0
            for (j in max(0, f - w)..min(frames - 1, f + w)) { s += sm[j]; n++ }
            val thr = s / n * 1.6f + 0.02f
            var isPeak = sm[f] > thr
            if (isPeak) for (k in -5..5) { val j = f + k; if (j in 0 until frames && j != f && sm[j] > sm[f]) { isPeak = false; break } }
            if (isPeak) picks.add(f)
        }
        // enforce a minimum gap, keeping the stronger onset
        val minFrames = minGapMs / 10
        val kept = ArrayList<Int>()
        for (f in picks) {
            val last = kept.lastOrNull()
            if (last == null || f - last >= minFrames) kept.add(f)
            else if (sm[f] > sm[last]) kept[kept.size - 1] = f
        }
        return kept.map { it * 10L + 10L }
    }
}

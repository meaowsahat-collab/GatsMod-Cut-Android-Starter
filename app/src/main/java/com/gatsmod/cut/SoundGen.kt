package com.gatsmod.cut

import java.io.File
import java.io.RandomAccessFile
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Sound effects and background music made inside the app (no downloads, no copyright).
 * Everything is simple synthesis, so it sounds "electronic" rather than like recorded instruments.
 */
object SoundGen {
    const val SR = 44100

    class Item(val id: String, val name: String, val note: String, val seconds: Float)

    val SFX = listOf(
        Item("whoosh", "Whoosh", "Air sweep for transitions", 1.0f),
        Item("riser", "Riser", "Rising tension", 1.6f),
        Item("pop", "Pop", "Short bubble pop", 0.2f),
        Item("click", "Click", "Button click", 0.08f),
        Item("ding", "Ding", "Bell", 1.4f),
        Item("chime", "Chime", "Three-note chime", 1.6f),
        Item("beep", "Beep", "Censor / alert beep", 0.4f),
        Item("laser", "Laser", "Sci-fi shot", 0.45f),
        Item("coin", "Coin", "Game coin", 0.5f),
        Item("boing", "Boing", "Cartoon spring", 0.7f),
        Item("kick", "Kick drum", "Deep hit", 0.5f),
        Item("snare", "Snare", "Snare hit", 0.3f),
        Item("boom", "Boom", "Big impact", 1.8f),
        Item("glitch", "Glitch", "Digital stutter", 0.7f),
        Item("shutter", "Camera shutter", "Photo click", 0.3f),
        Item("notify", "Notification", "Two-tone ping", 0.5f),
        Item("typing", "Typing", "Keyboard taps", 1.2f),
        Item("heartbeat", "Heartbeat", "Two beats", 1.6f),
        Item("alarm", "Alarm", "Siren beeps", 1.2f),
        Item("success", "Success", "Rising arpeggio", 0.9f),
        Item("fail", "Fail", "Sad falling notes", 1.3f)
    )

    val MUSIC = listOf(
        Item("lofi", "Chill Lofi", "Slow, warm, relaxed", 45f),
        Item("pop", "Upbeat Pop", "Bright and bouncy", 40f),
        Item("epic", "Epic Cinematic", "Big and dramatic", 45f),
        Item("dream", "Dreamy Ambient", "Soft floating pads", 48f),
        Item("dark", "Dark Tension", "Moody, suspense", 40f),
        Item("funk", "Happy Groove", "Playful and light", 40f)
    )

    fun sfxFile(ctx: android.content.Context, it: Item): File = File(File(ctx.filesDir, "sfx").apply { mkdirs() }, "sfx_${it.id}.wav")
    fun musicFile(ctx: android.content.Context, it: Item): File = File(File(ctx.filesDir, "music").apply { mkdirs() }, "music_${it.id}.wav")

    fun ensureSfx(ctx: android.content.Context, it: Item): File {
        val f = sfxFile(ctx, it)
        if (!f.exists() || f.length() < 1000) writeWav(f, sfx(it.id))
        return f
    }

    fun ensureMusic(ctx: android.content.Context, it: Item): File {
        val f = musicFile(ctx, it)
        if (!f.exists() || f.length() < 1000) writeWav(f, music(it.id, it.seconds))
        return f
    }

    fun isBuiltIn(uri: String) = uri.contains("/sfx/") || uri.contains("/music/")

    // ------------------------------------------------------------------ helpers

    private fun buf(sec: Float) = FloatArray((sec * SR).toInt())
    private fun hz(m: Int) = 440.0 * 2.0.pow((m - 69) / 12.0)

    private fun writeWav(f: File, data: FloatArray) {
        var peak = 0f
        for (v in data) peak = max(peak, kotlin.math.abs(v))
        val g = if (peak > 0f) 0.85f / peak else 1f
        val tmp = File(f.parentFile, f.name + ".tmp")
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            val n = data.size * 2
            val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + n); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
            h.putInt(SR); h.putInt(SR * 2); h.putShort(2); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(n)
            raf.write(h.array())
            val out = java.nio.ByteBuffer.allocate(min(data.size, 32768) * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < data.size) {
                out.clear()
                val e = min(data.size, i + 32768)
                while (i < e) { out.putShort((data[i] * g).coerceIn(-1f, 1f).times(32767f).toInt().toShort()); i++ }
                raf.write(out.array(), 0, out.position())
            }
        }
        tmp.renameTo(f)
    }

    private fun fadeEnds(b: FloatArray, inMs: Int = 4, outMs: Int = 30) {
        val a = SR * inMs / 1000; val o = SR * outMs / 1000
        for (i in 0 until min(a, b.size)) b[i] *= i / a.toFloat()
        for (i in 0 until min(o, b.size)) b[b.size - 1 - i] *= i / o.toFloat()
    }

    /** One note: wave 0 sine, 1 triangle, 2 soft saw, 3 square. */
    private fun tone(b: FloatArray, start: Int, sec: Float, f: Double, amp: Float, wave: Int, atk: Float, rel: Float, decay: Float = 0f) {
        val n = (sec * SR).toInt()
        val a = max(1, (atk * SR).toInt())
        val r = max(1, (rel * SR).toInt())
        var ph = 0.0
        val inc = 2 * PI * f / SR
        for (i in 0 until n) {
            val p = start + i
            if (p < 0 || p >= b.size) { ph += inc; continue }
            var e = if (i < a) i / a.toFloat() else 1f
            if (i > n - r) e *= max(0f, (n - i) / r.toFloat())
            if (decay > 0f) e *= exp(-decay * i / SR).toFloat()
            val s = when (wave) {
                0 -> sin(ph)
                1 -> 2 / PI * kotlin.math.asin(sin(ph))
                2 -> { var x = 0.0; for (h in 1..6) x += sin(ph * h) / h; x * 0.55 }
                else -> if (sin(ph) >= 0) 0.6 else -0.6
            }
            b[p] += (s * amp * e).toFloat()
            ph += inc
        }
    }

    private fun pad(b: FloatArray, start: Int, sec: Float, f: Double, amp: Float, atk: Float, rel: Float) {
        tone(b, start, sec, f, amp, 1, atk, rel)
        tone(b, start, sec, f * 1.004, amp * 0.7f, 1, atk, rel)
        tone(b, start, sec, f * 0.996, amp * 0.7f, 0, atk, rel)
    }

    private fun kick(b: FloatArray, start: Int, amp: Float, low: Double = 45.0) {
        val n = (0.28f * SR).toInt(); var ph = 0.0
        for (i in 0 until n) {
            val t = i / SR.toDouble()
            val f = low + (150 - low) * exp(-t * 28)
            ph += 2 * PI * f / SR
            val p = start + i; if (p >= 0 && p < b.size) b[p] += (sin(ph) * exp(-t * 9) * amp).toFloat()
        }
    }

    private fun snare(b: FloatArray, start: Int, amp: Float, rnd: Random) {
        val n = (0.18f * SR).toInt(); var lp = 0f
        for (i in 0 until n) {
            val t = i / SR.toDouble()
            lp += 0.6f * ((rnd.nextFloat() * 2 - 1) - lp)
            val p = start + i; if (p >= 0 && p < b.size)
                b[p] += ((lp * exp(-t * 26) + sin(2 * PI * 190 * t) * exp(-t * 30) * 0.5) * amp).toFloat()
        }
    }

    private fun hat(b: FloatArray, start: Int, amp: Float, rnd: Random, len: Float = 0.04f) {
        val n = (len * SR).toInt(); var prev = 0f
        for (i in 0 until n) {
            val t = i / SR.toDouble()
            val x = rnd.nextFloat() * 2 - 1
            val hp = x - prev; prev = x
            val p = start + i; if (p >= 0 && p < b.size) b[p] += (hp * exp(-t * 90) * amp).toFloat()
        }
    }

    private fun pluck(b: FloatArray, start: Int, f: Double, amp: Float, decay: Float = 7f) {
        val n = (0.6f * SR).toInt()
        for (i in 0 until n) {
            val t = i / SR.toDouble()
            val p = start + i; if (p < 0 || p >= b.size) continue
            val s = sin(2 * PI * f * t) + 0.35 * sin(4 * PI * f * t) + 0.15 * sin(6 * PI * f * t)
            b[p] += (s * exp(-t * decay) * amp * 0.6).toFloat()
        }
    }

    // ------------------------------------------------------------------ sound effects

    fun sfx(id: String): FloatArray {
        val rnd = Random(7)
        val item = SFX.first { it.id == id }
        val b = buf(item.seconds)
        val n = b.size
        fun t(i: Int) = i / SR.toDouble()
        when (id) {
            "whoosh" -> { var y = 0f; for (i in 0 until n) { val x = i / n.toFloat(); val env = sin(PI * x).pow(2.0).toFloat()
                y += (0.02f + 0.55f * env) * ((rnd.nextFloat() * 2 - 1) - y); b[i] = y * env * 2.2f } }
            "riser" -> { var ph = 0.0; var y = 0f; for (i in 0 until n) { val x = i / n.toFloat()
                ph += 2 * PI * (200 * 10.0.pow(x.toDouble())) / SR
                y += 0.3f * ((rnd.nextFloat() * 2 - 1) - y)
                b[i] = ((sin(ph) * 0.5 + y * 0.8) * x * x).toFloat() } }
            "pop" -> { var ph = 0.0; for (i in 0 until n) { val tt = t(i); ph += 2 * PI * (150 + 450 * exp(-tt * 40)) / SR; b[i] = (sin(ph) * exp(-tt * 28)).toFloat() } }
            "click" -> { var prev = 0f; for (i in 0 until n) { val x = rnd.nextFloat() * 2 - 1; b[i] = (x - prev) * exp(-t(i) * 140).toFloat(); prev = x } }
            "ding" -> for (i in 0 until n) { val tt = t(i); b[i] = ((sin(2 * PI * 1318.5 * tt) + 0.4 * sin(2 * PI * 2637 * tt) + 0.15 * sin(2 * PI * 3951 * tt)) * exp(-tt * 3.2)).toFloat() }
            "chime" -> { val notes = intArrayOf(84, 88, 91); for ((k, m) in notes.withIndex()) { val s0 = (k * 0.18f * SR).toInt()
                for (i in 0 until (1.2f * SR).toInt()) { val p = s0 + i; if (p >= n) break; val tt = t(i)
                    b[p] += ((sin(2 * PI * hz(m) * tt) + 0.3 * sin(4 * PI * hz(m) * tt)) * exp(-tt * 3.5) * 0.6).toFloat() } } }
            "beep" -> tone(b, 0, 0.4f, 1000.0, 0.7f, 0, 0.005f, 0.02f)
            "laser" -> { var ph = 0.0; for (i in 0 until n) { val tt = t(i); ph += 2 * PI * (200 + 1600 * exp(-tt * 9)) / SR; b[i] = (sin(ph) * exp(-tt * 6)).toFloat() } }
            "coin" -> { tone(b, 0, 0.09f, hz(83), 0.55f, 3, 0.002f, 0.01f); tone(b, (0.09f * SR).toInt(), 0.4f, hz(88), 0.55f, 3, 0.002f, 0.3f, 4f) }
            "boing" -> { var ph = 0.0; for (i in 0 until n) { val tt = t(i); ph += 2 * PI * (130 + 220 * exp(-tt * 5) + 40 * sin(2 * PI * 14 * tt) * exp(-tt * 4)) / SR; b[i] = (sin(ph) * exp(-tt * 4.5)).toFloat() } }
            "kick" -> kick(b, 0, 1f)
            "snare" -> snare(b, 0, 1f, rnd)
            "boom" -> { var ph = 0.0; var y = 0f; for (i in 0 until n) { val tt = t(i); ph += 2 * PI * (28 + 60 * exp(-tt * 3)) / SR
                y += 0.05f * ((rnd.nextFloat() * 2 - 1) - y); b[i] = (sin(ph) * exp(-tt * 2.2) + y * exp(-tt * 3) * 2).toFloat() } }
            "glitch" -> { var i = 0; while (i < n) { val len = (SR * (0.02 + rnd.nextFloat() * 0.05)).toInt(); val f = 200 + rnd.nextInt(2400)
                val sq = rnd.nextBoolean(); for (k in 0 until len) { if (i + k >= n) break; val s = sin(2 * PI * f * k / SR); b[i + k] = (if (sq) (if (s > 0) 0.6f else -0.6f) else s.toFloat() * 0.8f) * (if (rnd.nextInt(6) == 0) 0f else 1f) }
                i += len } }
            "shutter" -> { for (s in intArrayOf(0, (0.07f * SR).toInt())) { var prev = 0f; for (i in 0 until (0.05f * SR).toInt()) { val x = rnd.nextFloat() * 2 - 1
                if (s + i < n) b[s + i] += (x - prev) * exp(-i / SR.toDouble() * 90).toFloat(); prev = x } } }
            "notify" -> { tone(b, 0, 0.14f, 880.0, 0.6f, 0, 0.004f, 0.05f); tone(b, (0.14f * SR).toInt(), 0.3f, 1174.7, 0.6f, 0, 0.004f, 0.2f, 3f) }
            "typing" -> { val at = floatArrayOf(0f, 0.13f, 0.21f, 0.4f, 0.5f, 0.66f, 0.74f, 0.95f); for (a in at) { var prev = 0f; val s = (a * SR).toInt()
                for (i in 0 until (0.025f * SR).toInt()) { val x = rnd.nextFloat() * 2 - 1; if (s + i < n) b[s + i] += (x - prev) * exp(-i / SR.toDouble() * 160).toFloat() * 0.8f; prev = x } } }
            "heartbeat" -> { for (s in floatArrayOf(0f, 0.28f, 0.8f, 1.08f)) kick(b, (s * SR).toInt(), if (s == 0f || s == 0.8f) 1f else 0.7f, 38.0) }
            "alarm" -> { for (k in 0 until 6) tone(b, (k * 0.2f * SR).toInt(), 0.18f, if (k % 2 == 0) 880.0 else 1100.0, 0.6f, 3, 0.004f, 0.02f) }
            "success" -> { val ms = intArrayOf(72, 76, 79, 84); for ((k, m) in ms.withIndex()) tone(b, (k * 0.12f * SR).toInt(), if (k == 3) 0.5f else 0.2f, hz(m), 0.5f, 1, 0.004f, 0.12f, 2f) }
            "fail" -> { val ms = intArrayOf(67, 65, 63, 58); for ((k, m) in ms.withIndex()) tone(b, (k * 0.25f * SR).toInt(), if (k == 3) 0.6f else 0.28f, hz(m), 0.5f, 2, 0.01f, 0.1f) }
        }
        fadeEnds(b)
        return b
    }

    // ------------------------------------------------------------------ music

    fun music(id: String, seconds: Float): FloatArray {
        val b = buf(seconds)
        val rnd = Random(id.hashCode().toLong())
        data class Style(val bpm: Int, val chords: List<IntArray>, val root: Int)
        // chords as semitone offsets above the root (a minor 7th chord = 0,3,7,10 ...)
        val st = when (id) {
            "lofi" -> Style(78, listOf(intArrayOf(0, 3, 7, 10), intArrayOf(-4, 0, 3, 7), intArrayOf(-9, -5, -2, 2), intArrayOf(-2, 2, 5, 9)), 57)
            "pop" -> Style(112, listOf(intArrayOf(0, 4, 7), intArrayOf(-5, -1, 2), intArrayOf(-3, 0, 4), intArrayOf(-7, -3, 0)), 60)
            "epic" -> Style(90, listOf(intArrayOf(0, 3, 7), intArrayOf(-4, 0, 3), intArrayOf(-9, -5, -2), intArrayOf(-2, 2, 5)), 57)
            "dream" -> Style(60, listOf(intArrayOf(0, 4, 7, 11), intArrayOf(-3, 0, 4, 7, 11), intArrayOf(-7, -3, 0, 4), intArrayOf(-5, -1, 2, 9)), 60)
            "dark" -> Style(100, listOf(intArrayOf(0, 3, 7), intArrayOf(-4, 0, 3), intArrayOf(-7, -4, 0), intArrayOf(-5, -2, 2)), 50)
            else -> Style(120, listOf(intArrayOf(0, 4, 7), intArrayOf(5, 9, 12), intArrayOf(7, 11, 14), intArrayOf(5, 9, 12)), 60)
        }
        val beat = 60f / st.bpm
        val bar = beat * 4
        val bars = max(4, (seconds / bar).toInt())
        fun at(sec: Float) = (sec * SR).toInt()
        for (k in 0 until bars) {
            val ch = st.chords[k % st.chords.size]
            val t0 = k * bar
            when (id) {
                "lofi" -> {
                    for (n in ch) pad(b, at(t0), bar * 1.02f, hz(st.root + n), 0.09f, 0.25f, 0.5f)
                    tone(b, at(t0), bar * 0.95f, hz(st.root + ch[0] - 12), 0.22f, 0, 0.02f, 0.3f)
                    kick(b, at(t0), 0.55f); kick(b, at(t0 + beat * 2.5f), 0.4f)
                    snare(b, at(t0 + beat), 0.28f, rnd); snare(b, at(t0 + beat * 3), 0.28f, rnd)
                    for (h in 0 until 8) hat(b, at(t0 + h * beat / 2 + (if (h % 2 == 1) 0.012f else 0f)), if (h % 2 == 0) 0.1f else 0.06f, rnd)
                    if (k % 2 == 1) for ((i, n) in ch.withIndex()) pluck(b, at(t0 + beat * (2 + i * 0.5f)), hz(st.root + n + 12), 0.12f, 4f)
                }
                "pop" -> {
                    for (n in ch) pad(b, at(t0), bar, hz(st.root + n), 0.05f, 0.02f, 0.2f)
                    for (h in 0 until 8) pluck(b, at(t0 + h * beat / 2), hz(st.root + 12 + ch[h % ch.size]), 0.16f, 9f)
                    tone(b, at(t0), beat * 1.9f, hz(st.root + ch[0] - 24), 0.2f, 0, 0.01f, 0.15f)
                    tone(b, at(t0 + beat * 2), beat * 1.9f, hz(st.root + ch[0] - 24), 0.2f, 0, 0.01f, 0.15f)
                    for (q in 0 until 4) kick(b, at(t0 + q * beat), 0.6f)
                    snare(b, at(t0 + beat), 0.3f, rnd); snare(b, at(t0 + beat * 3), 0.3f, rnd)
                    for (h in 0 until 4) hat(b, at(t0 + h * beat + beat / 2), 0.1f, rnd)
                }
                "epic" -> {
                    for (n in ch) { tone(b, at(t0), bar * 1.05f, hz(st.root + n), 0.09f, 2, bar * 0.5f, 0.6f); tone(b, at(t0), bar * 1.05f, hz(st.root + n - 12), 0.08f, 2, bar * 0.5f, 0.6f) }
                    kick(b, at(t0), 0.9f, 35.0); kick(b, at(t0 + beat * 2), 0.7f, 35.0)
                    if (k % 2 == 1) { kick(b, at(t0 + beat * 3), 0.6f, 35.0); kick(b, at(t0 + beat * 3.5f), 0.6f, 35.0) }
                    for (h in 0 until 4) tone(b, at(t0 + h * beat), beat, hz(st.root + ch[h % ch.size] + 12), 0.05f, 1, 0.01f, 0.4f)
                }
                "dream" -> {
                    for (n in ch) pad(b, at(t0), bar * 1.1f, hz(st.root + n), 0.08f, bar * 0.4f, bar * 0.5f)
                    val pent = intArrayOf(0, 2, 4, 7, 9)
                    for (s in 0 until 3) { val m = st.root + 24 + pent[rnd.nextInt(5)]; tone(b, at(t0 + rnd.nextFloat() * bar), 1.5f, hz(m), 0.06f, 0, 0.01f, 1.2f, 2.5f) }
                }
                "dark" -> {
                    tone(b, at(t0), bar * 1.02f, hz(st.root + ch[0] - 12), 0.22f, 2, 0.4f, 0.4f)
                    for (n in ch) tone(b, at(t0), bar, hz(st.root + n), 0.05f, 2, bar * 0.6f, 0.5f)
                    kick(b, at(t0), 0.8f, 40.0); kick(b, at(t0 + beat * 0.5f), 0.5f, 40.0)
                    kick(b, at(t0 + beat * 2), 0.8f, 40.0); kick(b, at(t0 + beat * 2.5f), 0.5f, 40.0)
                    for (h in intArrayOf(1, 3, 5, 7)) hat(b, at(t0 + h * beat / 2), 0.08f, rnd, 0.09f)
                }
                else -> {
                    for ((i, n) in ch.withIndex()) { pluck(b, at(t0 + beat * 0.5f), hz(st.root + n), 0.2f, 8f); pluck(b, at(t0 + beat * 1.5f), hz(st.root + n), 0.2f, 8f)
                        pluck(b, at(t0 + beat * 2.5f + i * 0.001f), hz(st.root + n), 0.2f, 8f); pluck(b, at(t0 + beat * 3.5f), hz(st.root + n), 0.2f, 8f) }
                    val walk = intArrayOf(0, 7, 4, 7)
                    for (q in 0 until 4) tone(b, at(t0 + q * beat), beat * 0.9f, hz(st.root + ch[0] - 24 + walk[q]), 0.22f, 0, 0.01f, 0.1f)
                    kick(b, at(t0), 0.5f); kick(b, at(t0 + beat * 2), 0.5f)
                    snare(b, at(t0 + beat), 0.22f, rnd); snare(b, at(t0 + beat * 3), 0.22f, rnd)
                    for (h in 0 until 8) hat(b, at(t0 + h * beat / 2), 0.07f, rnd, 0.03f)
                }
            }
        }
        fadeEnds(b, 30, 1500)
        return b
    }
}

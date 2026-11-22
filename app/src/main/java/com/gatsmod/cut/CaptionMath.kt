package com.gatsmod.cut

/** One recognised word, times in milliseconds. */
data class CapWord(val s: Long, val e: Long, val t: String)

/** One subtitle line, times in milliseconds. */
data class Cue(val s: Long, val e: Long, val text: String)

object CaptionMath {
    /** Groups words into readable subtitle lines (about 3 s / 7 words, and a new line after a pause). */
    fun cues(words: List<CapWord>, joiner: String = " ", maxWords: Int = 7, maxMs: Long = 3500, pauseMs: Long = 700): List<Cue> {
        val out = ArrayList<Cue>()
        var cur = ArrayList<CapWord>()
        fun flush() {
            if (cur.isEmpty()) return
            out.add(Cue(cur.first().s, cur.last().e, cur.joinToString(joiner) { it.t }))
            cur = ArrayList()
        }
        for (w in words) {
            if (w.t.isBlank()) continue
            val last = cur.lastOrNull()
            if (last != null && (cur.size >= maxWords || w.s - last.e > pauseMs || w.e - cur.first().s > maxMs)) flush()
            cur.add(w)
        }
        flush()
        // show each line a little longer, but never over the next line; at least 600 ms
        return out.mapIndexed { i, c ->
            val limit = if (i + 1 < out.size) out[i + 1].s else Long.MAX_VALUE
            var e = minOf(c.e + 250, limit)
            if (e - c.s < 600) e = minOf(c.s + 600, limit)
            if (e <= c.s) e = c.s + 100
            Cue(c.s, e, c.text)
        }
    }
}

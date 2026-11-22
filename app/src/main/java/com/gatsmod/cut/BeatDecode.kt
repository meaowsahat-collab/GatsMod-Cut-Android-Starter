package com.gatsmod.cut

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Decodes the audio of [uri] between [inMs] and [outMs] into mono floats (~11 kHz) and finds the beats. */
object BeatDecode {
    /** Returns beat times in source milliseconds, or null when the file has no readable audio / was cancelled. */
    fun detect(ctx: Context, uri: String, inMs: Long, outMs: Long, progress: (Int) -> Boolean): List<Long>? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, Uri.parse(uri), null)
            var track = -1
            for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
            if (track < 0) return null
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            ex.seekTo(inMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val out = ArrayList<FloatArray>()
            var total = 0
            var decim = 1
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            val spanUs = ((outMs - inMs).coerceAtLeast(1)) * 1000
            var acc = 0f; var accN = 0
            var cur = FloatArray(65536); var curN = 0
            fun push(v: Float) {
                acc += v; accN++
                if (accN == decim) {
                    if (curN == cur.size) { out.add(cur); total += curN; cur = FloatArray(65536); curN = 0 }
                    cur[curN++] = acc / accN; acc = 0f; accN = 0
                }
            }
            var guard = 0
            while (!outDone && guard++ < 2_000_000) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        val t = ex.sampleTime
                        if (n < 0 || t > outMs * 1000) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, t, 0); ex.advance() }
                        if (!progress((((t - inMs * 1000) * 90 / spanUs).toInt()).coerceIn(0, 90))) return null
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (oi >= 0) {
                    decim = (rate / 11025).coerceAtLeast(1)
                    val b = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = b.remaining() / ch
                    val startUs = info.presentationTimeUs
                    for (f in 0 until frames) {
                        val tUs = startUs + f * 1_000_000L / rate
                        var s = 0f
                        for (k in 0 until ch) s += b.get(f * ch + k) / 32768f
                        if (tUs >= inMs * 1000 && tUs <= outMs * 1000) push(s / ch)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            out.add(cur.copyOf(curN)); total += curN
            val all = FloatArray(total)
            var p = 0
            for (a in out) { System.arraycopy(a, 0, all, p, minOf(a.size, total - p)); p += a.size }
            val beats = BeatMath.detect(all, (rate / decim).coerceAtLeast(1000))
            progress(100)
            return beats.map { it + inMs }
        } catch (e: Throwable) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}

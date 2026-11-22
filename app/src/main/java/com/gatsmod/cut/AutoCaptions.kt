package com.gatsmod.cut

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** Offline speech recognition with Vosk. The language model is downloaded once (about 40-90 MB) and kept on the phone. */
object AutoCaptions {
    class Lang(val label: String, val model: String, val sizeMb: Int, val spaced: Boolean = true)

    val LANGS = listOf(
        Lang("English", "vosk-model-small-en-us-0.15", 40),
        Lang("Hindi", "vosk-model-small-hi-0.22", 42),
        Lang("Spanish", "vosk-model-small-es-0.42", 39),
        Lang("French", "vosk-model-small-fr-0.22", 41),
        Lang("German", "vosk-model-small-de-0.15", 45),
        Lang("Russian", "vosk-model-small-ru-0.22", 45),
        Lang("Portuguese", "vosk-model-small-pt-0.3", 31),
        Lang("Italian", "vosk-model-small-it-0.22", 48),
        Lang("Turkish", "vosk-model-small-tr-0.3", 35),
        Lang("Chinese", "vosk-model-small-cn-0.22", 42, false),
        Lang("Japanese", "vosk-model-small-ja-0.22", 48, false),
        Lang("Korean", "vosk-model-small-ko-0.22", 82)
    )

    private fun root(ctx: Context) = File(ctx.filesDir, "vosk")
    fun modelDir(ctx: Context, l: Lang) = File(root(ctx), l.model)
    fun hasModel(ctx: Context, l: Lang) = File(modelDir(ctx, l), ".ok").exists()

    /** Downloads and unpacks the model. Returns false when it fails or is cancelled. */
    fun download(ctx: Context, l: Lang, progress: (Int) -> Boolean): Boolean {
        val zip = File(root(ctx), l.model + ".zip")
        zip.parentFile?.mkdirs()
        try {
            val con = URL("https://alphacephei.com/vosk/models/" + l.model + ".zip").openConnection() as HttpURLConnection
            con.connectTimeout = 20000; con.readTimeout = 30000
            if (con.responseCode != 200) return false
            val total = con.contentLengthLong.coerceAtLeast(1L)
            con.inputStream.use { ins ->
                FileOutputStream(zip).use { os ->
                    val buf = ByteArray(65536); var got = 0L
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        os.write(buf, 0, n); got += n
                        if (!progress((got * 70 / total).toInt().coerceIn(0, 70))) return false
                    }
                }
            }
            val dest = modelDir(ctx, l)
            dest.deleteRecursively()
            val base = root(ctx).canonicalPath + File.separator
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val f = File(root(ctx), e.name)
                    if (!f.canonicalPath.startsWith(base)) continue   // never write outside the model folder
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); FileOutputStream(f).use { zin.copyTo(it) } }
                }
            }
            if (!File(dest, "am").exists()) return false
            File(dest, ".ok").writeText("ok")
            return true
        } catch (e: Throwable) {
            return false
        } finally {
            zip.delete()
        }
    }

    /** Recognises speech in [inMs]..[outMs] of [uri]; word times are source milliseconds. Null = failed / cancelled. */
    fun transcribe(ctx: Context, uri: String, inMs: Long, outMs: Long, l: Lang, from: Int, progress: (Int) -> Boolean): List<CapWord>? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var model: org.vosk.Model? = null
        var rec: Recognizer? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = org.vosk.Model(modelDir(ctx, l).absolutePath)
            val outRate = 16000
            rec = Recognizer(model, outRate.toFloat())
            rec.setWords(true)
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
            val words = ArrayList<CapWord>()
            fun collect(json: String) {
                val arr = JSONObject(json).optJSONArray("result") ?: return
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    words.add(CapWord(inMs + (o.getDouble("start") * 1000).toLong(), inMs + (o.getDouble("end") * 1000).toLong(), o.getString("word")))
                }
            }
            val chunk = ShortArray(4000); var cn = 0
            fun feed() { if (cn > 0) { if (rec.acceptWaveForm(chunk, cn)) collect(rec.result); cn = 0 } }
            var acc = 0f; var accN = 0; var phase = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            val spanUs = (outMs - inMs).coerceAtLeast(1) * 1000
            var guard = 0
            while (!outDone && guard++ < 4_000_000) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        val t = ex.sampleTime
                        if (n < 0 || t > outMs * 1000) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, t, 0); ex.advance() }
                        val p = from + (((t - inMs * 1000) * (97 - from)) / spanUs).toInt()
                        if (!progress(p.coerceIn(from, 97))) return null
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (oi >= 0) {
                    val b = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = b.remaining() / ch
                    val startUs = info.presentationTimeUs
                    for (f in 0 until frames) {
                        val tUs = startUs + f * 1_000_000L / rate
                        if (tUs < inMs * 1000 || tUs > outMs * 1000) continue
                        var s = 0f
                        for (k in 0 until ch) s += b.get(f * ch + k)
                        acc += s / ch; accN++
                        phase += outRate
                        if (phase >= rate) {            // one output sample = average of the input samples since the last one
                            phase -= rate
                            chunk[cn++] = (acc / accN).toInt().coerceIn(-32768, 32767).toShort()
                            acc = 0f; accN = 0
                            if (cn == chunk.size) feed()
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            feed()
            collect(rec.finalResult)
            progress(100)
            return words
        } catch (e: Throwable) {
            return null
        } finally {
            try { rec?.close() } catch (_: Throwable) {}
            try { model?.close() } catch (_: Throwable) {}
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            ex.release()
        }
    }
}

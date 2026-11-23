package com.gatsmod.cut

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves and loads projects as small JSON files inside the app's private storage. */
object ProjectStore {
    class Meta(val id: String, val name: String, val updated: Long, val durMs: Long, val size: Long)

    private fun dir(ctx: Context) = File(ctx.filesDir, "projects").apply { mkdirs() }
    fun file(ctx: Context, id: String) = File(dir(ctx), "$id.json")
    fun thumb(ctx: Context, id: String) = File(dir(ctx), "$id.jpg")

    fun toJson(name: String, p: Project, size: Long): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("updated", System.currentTimeMillis())
        o.put("durMs", p.totalMs())
        o.put("size", size)
        o.put("canvasAspect", p.canvasAspect.toDouble())
        o.put("nextId", p.nextId)
        o.put("main", JSONArray(p.main.map { clipJson(it) }))
        o.put("audio", JSONArray(p.audio.map { clipJson(it) }))
        o.put("text", JSONArray(p.text.map { clipJson(it) }))
        o.put("overlay", JSONArray(p.overlay.map { clipJson(it) }))
        return o
    }

    fun write(ctx: Context, id: String, o: JSONObject) {
        val f = file(ctx, id)
        val tmp = File(f.parentFile, "$id.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    /** Fills [p] from the saved project. Returns the project name, or null when it could not be read. */
    fun load(ctx: Context, id: String, p: Project): String? {
        return try {
            val o = JSONObject(file(ctx, id).readText())
            p.canvasAspect = o.optDouble("canvasAspect", 0.0).toFloat()
            p.nextId = o.optLong("nextId", 1L)
            p.main = readClips(o.optJSONArray("main"))
            p.audio = readClips(o.optJSONArray("audio"))
            p.text = readClips(o.optJSONArray("text"))
            p.overlay = readClips(o.optJSONArray("overlay"))
            p.relayout()
            o.optString("name", "Project")
        } catch (e: Exception) { null }
    }

    fun list(ctx: Context): List<Meta> {
        val out = ArrayList<Meta>()
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            try {
                val o = JSONObject(f.readText())
                out.add(Meta(f.name.removeSuffix(".json"), o.optString("name", "Project"), o.optLong("updated"), o.optLong("durMs"), o.optLong("size")))
            } catch (_: Exception) {}
        }
        return out.sortedByDescending { it.updated }
    }

    fun delete(ctx: Context, id: String) { file(ctx, id).delete(); thumb(ctx, id).delete() }

    fun rename(ctx: Context, id: String, name: String) {
        try {
            val o = JSONObject(file(ctx, id).readText())
            o.put("name", name)
            write(ctx, id, o)
        } catch (_: Exception) {}
    }

    fun duplicate(ctx: Context, id: String): String? {
        return try {
            val o = JSONObject(file(ctx, id).readText())
            val nid = "p" + System.currentTimeMillis()
            o.put("name", o.optString("name", "Project") + " copy")
            o.put("updated", System.currentTimeMillis())
            write(ctx, nid, o)
            val t = thumb(ctx, id)
            if (t.exists()) t.copyTo(thumb(ctx, nid), overwrite = true)
            nid
        } catch (_: Exception) { null }
    }

    private fun clipJson(c: Clip): JSONObject {
        val o = JSONObject()
        o.put("id", c.id); o.put("kind", c.kind.name); o.put("startMs", c.startMs); o.put("uri", c.uri)
        o.put("srcInMs", c.srcInMs); o.put("srcOutMs", c.srcOutMs); o.put("srcTotalMs", c.srcTotalMs)
        o.put("label", c.label); o.put("speed", c.speed.toDouble()); o.put("volume", c.volume.toDouble())
        o.put("filter", c.filter); o.put("brightness", c.brightness.toDouble()); o.put("contrast", c.contrast.toDouble())
        o.put("saturation", c.saturation.toDouble()); o.put("blur", c.blur.toDouble()); o.put("cropRatio", c.cropRatio.toDouble())
        o.put("rotation", c.rotation); o.put("flipH", c.flipH); o.put("flipV", c.flipV)
        o.put("temp", c.temp.toDouble()); o.put("hue", c.hue.toDouble()); o.put("lum", c.lum.toDouble())
        o.put("chromaOn", c.chroma.enabled); o.put("chromaKey", c.chroma.key); o.put("chromaSim", c.chroma.similarity.toDouble())
        o.put("chromaSmooth", c.chroma.smooth.toDouble()); o.put("chromaBg", c.chroma.bg)
        o.put("keyframes", JSONArray(c.keyframes.map {
            JSONObject().put("t", it.tMs).put("s", it.scale.toDouble()).put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("r", it.rot.toDouble())
        }))
        o.put("width", c.width); o.put("height", c.height); o.put("hasAudio", c.hasAudio); o.put("fps", c.fps.toDouble())
        o.put("text", c.text); o.put("color", c.color); o.put("sizeFrac", c.sizeFrac.toDouble())
        o.put("posX", c.posX.toDouble()); o.put("posY", c.posY.toDouble()); o.put("bold", c.bold); o.put("font", c.font)
        o.put("durMs", c.durMs)
        o.put("fadeIn", c.fadeInMs); o.put("fadeOut", c.fadeOutMs)
        o.put("fx", c.fx); o.put("fxAmount", c.fxAmount.toDouble()); o.put("maskShape", c.maskShape); o.put("maskSize", c.maskSize.toDouble())
        o.put("maskFeather", c.maskFeather.toDouble()); o.put("maskInvert", c.maskInvert); o.put("maskX", c.maskX.toDouble()); o.put("maskY", c.maskY.toDouble()); o.put("denoise", c.denoise.toDouble()); o.put("beats", JSONArray(c.beats))
        o.put("strokeColor", c.strokeColor); o.put("strokeW", c.strokeW.toDouble()); o.put("bgColor", c.bgColor); o.put("shadow", c.shadow); o.put("italic", c.italic); o.put("textFx", c.textFx); o.put("tilt", c.tilt.toDouble())
        o.put("animIn", c.animIn); o.put("animInMs", c.animInMs); o.put("animOut", c.animOut); o.put("animOutMs", c.animOutMs); o.put("animCombo", c.animCombo); o.put("textMotion", c.textMotion); o.put("textReveal", c.textReveal)
        o.put("stab", JSONArray(c.stab.map { JSONObject().put("t", it.tMs).put("s", it.scale.toDouble()).put("x", it.x.toDouble()).put("y", it.y.toDouble()) }))
        return o
    }

    private fun readClips(a: JSONArray?): MutableList<Clip> {
        val l = mutableListOf<Clip>()
        if (a == null) return l
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val kf = mutableListOf<Keyframe>()
            o.optJSONArray("keyframes")?.let { k ->
                for (j in 0 until k.length()) {
                    val q = k.getJSONObject(j)
                    kf.add(Keyframe(q.optLong("t"), q.optDouble("s", 1.0).toFloat(), q.optDouble("x").toFloat(), q.optDouble("y").toFloat(), q.optDouble("r").toFloat()))
                }
            }
            l.add(
                Clip(
                    id = o.getLong("id"), kind = LaneKind.valueOf(o.getString("kind")), startMs = o.optLong("startMs"), uri = o.optString("uri"),
                    srcInMs = o.optLong("srcInMs"), srcOutMs = o.optLong("srcOutMs"), srcTotalMs = o.optLong("srcTotalMs"),
                    label = o.optString("label"), speed = o.optDouble("speed", 1.0).toFloat(), volume = o.optDouble("volume", 1.0).toFloat(),
                    filter = o.optInt("filter"), brightness = o.optDouble("brightness").toFloat(), contrast = o.optDouble("contrast", 1.0).toFloat(),
                    saturation = o.optDouble("saturation", 1.0).toFloat(), blur = o.optDouble("blur").toFloat(), cropRatio = o.optDouble("cropRatio").toFloat(),
                    rotation = o.optInt("rotation"), flipH = o.optBoolean("flipH"), flipV = o.optBoolean("flipV"),
                    temp = o.optDouble("temp").toFloat(), hue = o.optDouble("hue").toFloat(), lum = o.optDouble("lum").toFloat(),
                    chroma = Chroma(
                        o.optBoolean("chromaOn"), o.optInt("chromaKey", 0xFF00B140.toInt()), o.optDouble("chromaSim", 0.35).toFloat(),
                        o.optDouble("chromaSmooth", 0.12).toFloat(), o.optInt("chromaBg", android.graphics.Color.BLACK)
                    ),
                    keyframes = kf, width = o.optInt("width"), height = o.optInt("height"), hasAudio = o.optBoolean("hasAudio", true),
                    fps = o.optDouble("fps", 30.0).toFloat(), text = o.optString("text"), color = o.optInt("color", android.graphics.Color.WHITE),
                    sizeFrac = o.optDouble("sizeFrac", 0.06).toFloat(), posX = o.optDouble("posX", 0.5).toFloat(), posY = o.optDouble("posY", 0.85).toFloat(),
                    bold = o.optBoolean("bold", true), font = o.optInt("font"), durMs = o.optLong("durMs", 3000),
                    fadeInMs = o.optLong("fadeIn"), fadeOutMs = o.optLong("fadeOut"),
                    fx = o.optInt("fx"), fxAmount = o.optDouble("fxAmount", 0.5).toFloat(), maskShape = o.optInt("maskShape"),
                    maskSize = o.optDouble("maskSize", 0.5).toFloat(), maskFeather = o.optDouble("maskFeather", 0.15).toFloat(),
                    maskInvert = o.optBoolean("maskInvert"), maskX = o.optDouble("maskX").toFloat(), maskY = o.optDouble("maskY").toFloat(), denoise = o.optDouble("denoise").toFloat(),
                    strokeColor = o.optInt("strokeColor"), strokeW = o.optDouble("strokeW", 0.08).toFloat(), bgColor = o.optInt("bgColor"), shadow = o.optBoolean("shadow", true), italic = o.optBoolean("italic"), textFx = o.optInt("textFx"), tilt = o.optDouble("tilt", 0.0).toFloat(),
                    animIn = o.optInt("animIn"), animInMs = o.optLong("animInMs", 600), animOut = o.optInt("animOut"), animOutMs = o.optLong("animOutMs", 600), animCombo = o.optInt("animCombo"), textMotion = o.optInt("textMotion"), textReveal = o.optInt("textReveal"),
                    stab = o.optJSONArray("stab")?.let { a -> (0 until a.length()).map { j -> a.getJSONObject(j).let { q -> Keyframe(q.optLong("t"), q.optDouble("s", 1.0).toFloat(), q.optDouble("x").toFloat(), q.optDouble("y").toFloat()) } } } ?: emptyList(),
                    beats = o.optJSONArray("beats")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()
                )
            )
        }
        return l
    }
}

package com.gatsmod.cut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Minimal GIPHY client (animated transparent stickers). Needs the user's own free API key from developers.giphy.com. */
object Giphy {
    class Item(val id: String, val thumbUrl: String, val gifUrl: String, val title: String)

    private const val PREFS = "gatsmod"
    /** The app's GIPHY API key (GIPHY keys are meant to live inside apps). A user can still save their own. */
    private const val PUBLIC_TEST_KEY = "QhsGeW2uTfWqxLrLce3YI7iJI23vXNHV"
    fun ownKey(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("giphy_key", "") ?: ""
    fun key(ctx: Context): String = ownKey(ctx).ifBlank { PUBLIC_TEST_KEY }
    fun setKey(ctx: Context, k: String) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("giphy_key", k.trim()).apply() }

    private fun get(url: String): ByteArray {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15000; con.readTimeout = 20000
        if (con.responseCode != 200) throw RuntimeException("HTTP " + con.responseCode)
        return con.inputStream.use { it.readBytes() }
    }

    /** Trending when [q] is blank, otherwise a search. Throws on network / key errors. */
    fun load(key: String, q: String): List<Item> {
        val base = if (q.isBlank()) "https://api.giphy.com/v1/stickers/trending?" else "https://api.giphy.com/v1/stickers/search?q=" + java.net.URLEncoder.encode(q.trim(), "UTF-8") + "&"
        val json = JSONObject(String(get(base + "api_key=" + key + "&limit=40&rating=g")))
        val data = json.getJSONArray("data")
        val out = ArrayList<Item>()
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val im = o.getJSONObject("images")
            val thumb = (im.optJSONObject("fixed_width_small_still") ?: im.optJSONObject("fixed_width_still"))?.optString("url") ?: continue
            val gif = (im.optJSONObject("fixed_width") ?: im.optJSONObject("downsized"))?.optString("url") ?: continue
            out.add(Item(o.getString("id"), thumb, gif, o.optString("title")))
        }
        return out
    }

    fun thumb(url: String): Bitmap? = try { val b = get(url); BitmapFactory.decodeByteArray(b, 0, b.size) } catch (_: Throwable) { null }

    /** Downloads the GIF into the app and returns the file. */
    fun download(ctx: Context, it: Item): File? = try {
        val f = File(File(ctx.filesDir, "gif").also { d -> d.mkdirs() }, "G_" + it.id + ".gif")
        if (!f.exists() || f.length() == 0L) f.writeBytes(get(it.gifUrl))
        f
    } catch (_: Throwable) { null }
}

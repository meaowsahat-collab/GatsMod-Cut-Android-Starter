package com.gatsmod.cut

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** A built-in sticker, drawn by code (no downloaded art). kind: emoji | badge | arrow | shape | banner. */
class Sticker(val id: String, val cat: String, val kind: String, val label: String, val glyph: String = "", val c1: Int = 0, val c2: Int = 0, val tags: String = "")

object StickerArt {
    val CATS = listOf("HOT", "Social & CTA", "Emoji", "Hearts & FX", "Arrows", "Arrows & Signs", "Shapes", "Love", "Party", "Fun & Gaming", "Text")

    private fun e(id: String, cat: String, g: String, tags: String) = Sticker(id, cat, "emoji", tags.substringBefore(' '), g, tags = tags)
    private fun b(id: String, text: String, g: String, c1: Long, c2: Long, tags: String = "", cat: String = "HOT") = Sticker(id, cat, "badge", text, g, c1.toInt(), c2.toInt(), tags)

    val ALL: List<Sticker> = listOf(
        b("sub", "SUBSCRIBE", "", 0xFFE53935, 0xFFB71C1C, "youtube subscribe"),
        b("subbell", "SUBSCRIBE", "🔔", 0xFFE53935, 0xFFFF7043, "subscribe bell notification"),
        b("subd", "SUBSCRIBED", "✓", 0xFF424242, 0xFF212121, "subscribed done"),
        b("share", "SHARE", "➦", 0xFF1E88E5, 0xFF1565C0, "share"),
        b("like", "Like", "👍", 0xFF7C4DFF, 0xFF00B0FF, "like thumbs up"),
        b("follow", "FOLLOW", "＋", 0xFF111111, 0xFF2C2C2C, "follow instagram tiktok"),
        b("comment", "COMMENT", "💬", 0xFF43A047, 0xFF1B5E20, "comment"),
        b("new", "NEW", "★", 0xFFFF1744, 0xFFD50000, "new"),
        b("hot", "HOT", "🔥", 0xFFFF6D00, 0xFFDD2C00, "hot fire"),
        b("live", "LIVE", "●", 0xFFE53935, 0xFFB71C1C, "live"),
        b("sale", "SALE", "%", 0xFFFFD600, 0xFFFF8F00, "sale discount"),
        b("bf", "BLACK FRIDAY", "", 0xFF212121, 0xFF000000, "black friday sale"),
        Sticker("thanks", "HOT", "banner", "Thanks for watching", c1 = 0xFFFFFFFF.toInt(), c2 = 0xFF222222.toInt(), tags = "thanks watching"),
        Sticker("likebanner", "HOT", "banner", "LIKE 👍", c1 = 0xFFFFEB3B.toInt(), c2 = 0xFFFF1744.toInt(), tags = "like banner"),
        Sticker("omg", "Text", "banner", "OMG!", c1 = 0xFFFFFFFF.toInt(), c2 = 0xFFFF1744.toInt(), tags = "omg wow"),
        Sticker("wow", "Text", "banner", "WOW", c1 = 0xFFFFEB3B.toInt(), c2 = 0xFF6A1B9A.toInt(), tags = "wow"),
        Sticker("lol", "Text", "banner", "LOL", c1 = 0xFF00E676.toInt(), c2 = 0xFF004D40.toInt(), tags = "lol funny"),
        Sticker("cool", "Text", "banner", "COOL", c1 = 0xFF40C4FF.toInt(), c2 = 0xFF01579B.toInt(), tags = "cool"),
        Sticker("part1", "Text", "banner", "PART 1", c1 = 0xFFFFFFFF.toInt(), c2 = 0xFF3949AB.toInt(), tags = "part one series"),
        Sticker("vs", "Text", "banner", "VS", c1 = 0xFFFFFFFF.toInt(), c2 = 0xFFD50000.toInt(), tags = "versus vs"),
        Sticker("arrowR", "Arrows", "arrow", "Arrow", c1 = 0xFFFF1744.toInt(), tags = "arrow red right"),
        Sticker("arrowY", "Arrows", "arrow", "Arrow yellow", c1 = 0xFFFFD600.toInt(), tags = "arrow yellow"),
        Sticker("arrowG", "Arrows", "arrow", "Arrow green", c1 = 0xFF00E676.toInt(), tags = "arrow green"),
        Sticker("arrowW", "Arrows", "arrow", "Arrow white", c1 = 0xFFFFFFFF.toInt(), tags = "arrow white"),
        Sticker("star", "Shapes", "shape", "Star", "star", 0xFFFFD600.toInt(), 0xFFFF8F00.toInt(), "star"),
        Sticker("heart", "Love", "shape", "Heart", "heart", 0xFFFF1744.toInt(), 0xFFD50000.toInt(), "heart love"),
        Sticker("heartp", "Love", "shape", "Pink heart", "heart", 0xFFFF80AB.toInt(), 0xFFF50057.toInt(), "heart pink love"),
        Sticker("burst", "Shapes", "shape", "Burst", "burst", 0xFFFFEB3B.toInt(), 0xFFFF6D00.toInt(), "burst explosion"),
        Sticker("circle", "Shapes", "shape", "Circle", "circle", 0xFF40C4FF.toInt(), 0xFF0091EA.toInt(), "circle"),
        Sticker("square", "Shapes", "shape", "Box", "square", 0xFFFFFFFF.toInt(), 0xFFBDBDBD.toInt(), "box square white"),
        Sticker("black", "Shapes", "shape", "Black bar", "bar", 0xFF000000.toInt(), 0xFF000000.toInt(), "black bar censor"),
        Sticker("ring", "Shapes", "shape", "Ring", "ring", 0xFFFF1744.toInt(), 0xFFFF1744.toInt(), "ring circle highlight")
    ) + listOf(
        e("skull", "HOT", "💀", "skull dead"), e("fire", "HOT", "🔥", "fire hot"), e("100", "HOT", "💯", "100 hundred"),
        e("crown", "HOT", "👑", "crown king"), e("rocket", "HOT", "🚀", "rocket"),
        e("laugh", "Emoji", "😂", "laugh funny"), e("grin", "Emoji", "😀", "smile happy"), e("heartEyes", "Emoji", "😍", "love eyes"),
        e("cool2", "Emoji", "😎", "cool sunglasses"), e("cry", "Emoji", "😭", "cry sad"), e("angry", "Emoji", "😡", "angry"),
        e("think", "Emoji", "🤔", "think"), e("shock", "Emoji", "😱", "shock scream"), e("wink", "Emoji", "😉", "wink"),
        e("clown", "Emoji", "🤡", "clown"), e("ghost", "Emoji", "👻", "ghost"), e("alien", "Emoji", "👽", "alien"),
        e("thumb", "Emoji", "👍", "thumb like"), e("clap", "Emoji", "👏", "clap"), e("pray", "Emoji", "🙏", "pray thanks"),
        e("eyes", "Emoji", "👀", "eyes look"), e("muscle", "Emoji", "💪", "strong muscle"),
        e("redheart", "Love", "❤️", "heart love red"), e("broken", "Love", "💔", "broken heart"), e("kiss", "Love", "😘", "kiss"),
        e("hug", "Love", "🥰", "love hug"), e("sparkHeart", "Love", "💖", "sparkle heart"), e("rose", "Love", "🌹", "rose flower"),
        e("party", "Party", "🎉", "party confetti"), e("balloon", "Party", "🎈", "balloon"), e("gift", "Party", "🎁", "gift"),
        e("cake", "Party", "🎂", "cake birthday"), e("trophy", "Party", "🏆", "trophy win"), e("music", "Party", "🎵", "music note"),
        e("sparkle", "Party", "✨", "sparkle"), e("rainbow", "Party", "🌈", "rainbow"), e("starE", "Party", "⭐", "star")
    ) + EXTRA

    private val EXTRA: List<Sticker> = listOf(
        b("cta1", "SUBSCRIBE", "🔔", 0xFFE53935, 0xFFB71C1C, "Sub Red SUBSCRIBE 🔔", "Social & CTA"),
        b("cta2", "SUBSCRIBE", "▶", 0xFFE53935, 0xFFB71C1C, "Sub Pill SUBSCRIBE ▶", "Social & CTA"),
        b("cta3", "SUB NOW", "🔔", 0xFFE53935, 0xFFB71C1C, "Sub Neon SUB NOW 🔔", "Social & CTA"),
        b("cta4", "SUBSCRIBE", "👑", 0xFFE53935, 0xFFB71C1C, "Sub Gold SUBSCRIBE 👑", "Social & CTA"),
        b("cta5", "SUBSCRIBE", "🔔", 0xFFE53935, 0xFFB71C1C, "Sub Dark SUBSCRIBE 🔔", "Social & CTA"),
        b("cta6", "SHARE", "➤", 0xFFE53935, 0xFFB71C1C, "Share Blue SHARE ➤", "Social & CTA"),
        b("cta7", "SHARE IT", "↗", 0xFFE53935, 0xFFB71C1C, "Share Cyan SHARE IT ↗", "Social & CTA"),
        b("cta8", "SHARE", "📲", 0xFFE53935, 0xFFB71C1C, "Share Grad SHARE 📲", "Social & CTA"),
        b("cta9", "FOLLOW", "+", 0xFFE53935, 0xFFB71C1C, "Follow Cyan FOLLOW +", "Social & CTA"),
        b("cta10", "FOLLOW", "♥", 0xFFE53935, 0xFFB71C1C, "Follow Pink FOLLOW ♥", "Social & CTA"),
        b("cta11", "FOLLOW ME", "⚡", 0xFFE53935, 0xFFB71C1C, "Follow Lime FOLLOW ME ⚡", "Social & CTA"),
        b("cta12", "LIKE", "👍", 0xFFE53935, 0xFFB71C1C, "Like Thumb LIKE 👍", "Social & CTA"),
        b("cta13", "SMASH LIKE", "👍🏻", 0xFFE53935, 0xFFB71C1C, "Like Sky SMASH LIKE 👍🏻", "Social & CTA"),
        b("cta14", "COMMENT", "💬", 0xFFE53935, 0xFFB71C1C, "Comment Gold COMMENT 💬", "Social & CTA"),
        b("cta15", "SAY HI", "💭", 0xFFE53935, 0xFFB71C1C, "Comment Mint SAY HI 💭", "Social & CTA"),
        b("cta16", "SAVE", "🔖", 0xFFE53935, 0xFFB71C1C, "Save Tag SAVE 🔖", "Social & CTA"),
        b("cta17", "LIVE", "🔴", 0xFFE53935, 0xFFB71C1C, "Live Red LIVE 🔴", "Social & CTA"),
        b("cta18", "NEW", "✨", 0xFFE53935, 0xFFB71C1C, "New Badge NEW ✨", "Social & CTA"),
        b("cta19", "HOT", "🔥", 0xFFE53935, 0xFFB71C1C, "Hot Badge HOT 🔥", "Social & CTA"),
        b("cta20", "PRO", "💎", 0xFFE53935, 0xFFB71C1C, "Pro Badge PRO 💎", "Social & CTA"),
        b("cta21", "LINK IN BIO", "🔗", 0xFFE53935, 0xFFB71C1C, "Link Chain LINK IN BIO 🔗", "Social & CTA"),
        b("cta22", "TAP HERE", "👆", 0xFFE53935, 0xFFB71C1C, "Tap Here TAP HERE 👆", "Social & CTA"),
        b("cta23", "SWIPE UP", "⬆", 0xFFE53935, 0xFFB71C1C, "Swipe Up SWIPE UP ⬆", "Social & CTA"),
        b("cta24", "WATCH NOW", "🎬", 0xFFE53935, 0xFFB71C1C, "Watch Now WATCH NOW 🎬", "Social & CTA"),
        b("cta25", "NEXT PART", "⏭", 0xFFE53935, 0xFFB71C1C, "Next Ep NEXT PART ⏭", "Social & CTA"),
        b("cta26", "PART 2", "🔥", 0xFFE53935, 0xFFB71C1C, "Part 2 PART 2 🔥", "Social & CTA"),
        b("cta27", "THANKS FOR WATCHING", "✨", 0xFFE53935, 0xFFB71C1C, "Thanks Card THANKS FOR WATCHING ✨", "Social & CTA"),
        b("cta28", "SEE YOU SOON", "👋", 0xFFE53935, 0xFFB71C1C, "See You SEE YOU SOON 👋", "Social & CTA"),
        b("cta29", "JOIN NOW", "🚀", 0xFFE53935, 0xFFB71C1C, "Join Club JOIN NOW 🚀", "Social & CTA"),
        b("cta30", "ALERT", "🔔", 0xFFE53935, 0xFFB71C1C, "Alert Bell ALERT 🔔", "Social & CTA"),
        e("emoji1", "Emoji", "😂", "emoji reaction 😂"),
        e("emoji2", "Emoji", "🤣", "emoji reaction 🤣"),
        e("emoji3", "Emoji", "😍", "emoji reaction 😍"),
        e("emoji4", "Emoji", "😘", "emoji reaction 😘"),
        e("emoji5", "Emoji", "😎", "emoji reaction 😎"),
        e("emoji6", "Emoji", "🤔", "emoji reaction 🤔"),
        e("emoji7", "Emoji", "😱", "emoji reaction 😱"),
        e("emoji8", "Emoji", "🤯", "emoji reaction 🤯"),
        e("emoji9", "Emoji", "😭", "emoji reaction 😭"),
        e("emoji10", "Emoji", "💀", "emoji reaction 💀"),
        e("emoji11", "Emoji", "👻", "emoji reaction 👻"),
        e("emoji12", "Emoji", "🤡", "emoji reaction 🤡"),
        e("emoji13", "Emoji", "👽", "emoji reaction 👽"),
        e("emoji14", "Emoji", "🤖", "emoji reaction 🤖"),
        e("emoji15", "Emoji", "😈", "emoji reaction 😈"),
        e("emoji16", "Emoji", "😇", "emoji reaction 😇"),
        e("emoji17", "Emoji", "🥳", "emoji reaction 🥳"),
        e("emoji18", "Emoji", "😴", "emoji reaction 😴"),
        e("emoji19", "Emoji", "🫡", "emoji reaction 🫡"),
        e("emoji20", "Emoji", "🫠", "emoji reaction 🫠"),
        e("emoji21", "Emoji", "🤓", "emoji reaction 🤓"),
        e("emoji22", "Emoji", "🤑", "emoji reaction 🤑"),
        e("emoji23", "Emoji", "🥺", "emoji reaction 🥺"),
        e("emoji24", "Emoji", "🤐", "emoji reaction 🤐"),
        e("emoji25", "Emoji", "🤫", "emoji reaction 🤫"),
        e("emoji26", "Emoji", "🥱", "emoji reaction 🥱"),
        e("emoji27", "Emoji", "🥶", "emoji reaction 🥶"),
        e("emoji28", "Emoji", "🥵", "emoji reaction 🥵"),
        e("emoji29", "Emoji", "😵‍💫", "emoji reaction 😵‍💫"),
        e("emoji30", "Emoji", "😏", "emoji reaction 😏"),
        e("fx1", "Hearts & FX", "❤️", "heart effects sparkle ❤️"),
        e("fx2", "Hearts & FX", "❤️‍🔥", "heart effects sparkle ❤️‍🔥"),
        e("fx3", "Hearts & FX", "💖", "heart effects sparkle 💖"),
        e("fx4", "Hearts & FX", "💜", "heart effects sparkle 💜"),
        e("fx5", "Hearts & FX", "💙", "heart effects sparkle 💙"),
        e("fx6", "Hearts & FX", "🖤", "heart effects sparkle 🖤"),
        e("fx7", "Hearts & FX", "💔", "heart effects sparkle 💔"),
        e("fx8", "Hearts & FX", "💕", "heart effects sparkle 💕"),
        e("fx9", "Hearts & FX", "💞", "heart effects sparkle 💞"),
        e("fx10", "Hearts & FX", "🔥", "heart effects sparkle 🔥"),
        e("fx11", "Hearts & FX", "✨", "heart effects sparkle ✨"),
        e("fx12", "Hearts & FX", "💥", "heart effects sparkle 💥"),
        e("fx13", "Hearts & FX", "⚡", "heart effects sparkle ⚡"),
        e("fx14", "Hearts & FX", "🌟", "heart effects sparkle 🌟"),
        e("fx15", "Hearts & FX", "💫", "heart effects sparkle 💫"),
        e("fx16", "Hearts & FX", "🌠", "heart effects sparkle 🌠"),
        e("fx17", "Hearts & FX", "🌈", "heart effects sparkle 🌈"),
        e("fx18", "Hearts & FX", "☀️", "heart effects sparkle ☀️"),
        e("fx19", "Hearts & FX", "🌙", "heart effects sparkle 🌙"),
        e("fx20", "Hearts & FX", "🎊", "heart effects sparkle 🎊"),
        e("fx21", "Hearts & FX", "🎉", "heart effects sparkle 🎉"),
        e("fx22", "Hearts & FX", "💯", "heart effects sparkle 💯"),
        e("fx23", "Hearts & FX", "💦", "heart effects sparkle 💦"),
        e("fx24", "Hearts & FX", "🌀", "heart effects sparkle 🌀"),
        e("fx25", "Hearts & FX", "☄️", "heart effects sparkle ☄️"),
        e("fx26", "Hearts & FX", "❄️", "heart effects sparkle ❄️"),
        e("fx27", "Hearts & FX", "💧", "heart effects sparkle 💧"),
        e("fx28", "Hearts & FX", "🎈", "heart effects sparkle 🎈"),
        e("fx29", "Hearts & FX", "👑", "heart effects sparkle 👑"),
        e("fx30", "Hearts & FX", "💎", "heart effects sparkle 💎"),
        e("sign1", "Arrows & Signs", "↘️", "arrow pointer symbol ↘️"),
        e("sign2", "Arrows & Signs", "↗️", "arrow pointer symbol ↗️"),
        e("sign3", "Arrows & Signs", "⬇️", "arrow pointer symbol ⬇️"),
        e("sign4", "Arrows & Signs", "⬆️", "arrow pointer symbol ⬆️"),
        e("sign5", "Arrows & Signs", "⬅️", "arrow pointer symbol ⬅️"),
        e("sign6", "Arrows & Signs", "➡️", "arrow pointer symbol ➡️"),
        e("sign7", "Arrows & Signs", "👉", "arrow pointer symbol 👉"),
        e("sign8", "Arrows & Signs", "👈", "arrow pointer symbol 👈"),
        e("sign9", "Arrows & Signs", "☝️", "arrow pointer symbol ☝️"),
        e("sign10", "Arrows & Signs", "👇", "arrow pointer symbol 👇"),
        e("sign11", "Arrows & Signs", "👏", "arrow pointer symbol 👏"),
        e("sign12", "Arrows & Signs", "🙌", "arrow pointer symbol 🙌"),
        e("sign13", "Arrows & Signs", "✌️", "arrow pointer symbol ✌️"),
        e("sign14", "Arrows & Signs", "🤘", "arrow pointer symbol 🤘"),
        e("sign15", "Arrows & Signs", "👌", "arrow pointer symbol 👌"),
        e("sign16", "Arrows & Signs", "💪", "arrow pointer symbol 💪"),
        e("sign17", "Arrows & Signs", "✅", "arrow pointer symbol ✅"),
        e("sign18", "Arrows & Signs", "❌", "arrow pointer symbol ❌"),
        e("sign19", "Arrows & Signs", "⚠️", "arrow pointer symbol ⚠️"),
        e("sign20", "Arrows & Signs", "❓", "arrow pointer symbol ❓"),
        e("sign21", "Arrows & Signs", "❗", "arrow pointer symbol ❗"),
        e("sign22", "Arrows & Signs", "♻️", "arrow pointer symbol ♻️"),
        e("sign23", "Arrows & Signs", "🛑", "arrow pointer symbol 🛑"),
        e("sign24", "Arrows & Signs", "🎯", "arrow pointer symbol 🎯"),
        e("sign25", "Arrows & Signs", "📍", "arrow pointer symbol 📍"),
        e("sign26", "Arrows & Signs", "🚀", "arrow pointer symbol 🚀"),
        e("sign27", "Arrows & Signs", "🏆", "arrow pointer symbol 🏆"),
        e("sign28", "Arrows & Signs", "🥇", "arrow pointer symbol 🥇"),
        e("sign29", "Arrows & Signs", "🏁", "arrow pointer symbol 🏁"),
        e("sign30", "Arrows & Signs", "📣", "arrow pointer symbol 📣"),
        e("fun1", "Fun & Gaming", "🎮", "gaming fun lifestyle 🎮"),
        e("fun2", "Fun & Gaming", "🕹️", "gaming fun lifestyle 🕹️"),
        e("fun3", "Fun & Gaming", "🎲", "gaming fun lifestyle 🎲"),
        e("fun4", "Fun & Gaming", "🍕", "gaming fun lifestyle 🍕"),
        e("fun5", "Fun & Gaming", "🍔", "gaming fun lifestyle 🍔"),
        e("fun6", "Fun & Gaming", "☕", "gaming fun lifestyle ☕"),
        e("fun7", "Fun & Gaming", "🧋", "gaming fun lifestyle 🧋"),
        e("fun8", "Fun & Gaming", "🍰", "gaming fun lifestyle 🍰"),
        e("fun9", "Fun & Gaming", "🍩", "gaming fun lifestyle 🍩"),
        e("fun10", "Fun & Gaming", "🎵", "gaming fun lifestyle 🎵"),
        e("fun11", "Fun & Gaming", "🎶", "gaming fun lifestyle 🎶"),
        e("fun12", "Fun & Gaming", "🎧", "gaming fun lifestyle 🎧"),
        e("fun13", "Fun & Gaming", "🎤", "gaming fun lifestyle 🎤"),
        e("fun14", "Fun & Gaming", "📸", "gaming fun lifestyle 📸"),
        e("fun15", "Fun & Gaming", "🎬", "gaming fun lifestyle 🎬"),
        e("fun16", "Fun & Gaming", "🍿", "gaming fun lifestyle 🍿"),
        e("fun17", "Fun & Gaming", "🏎️", "gaming fun lifestyle 🏎️"),
        e("fun18", "Fun & Gaming", "🚁", "gaming fun lifestyle 🚁"),
        e("fun19", "Fun & Gaming", "✈️", "gaming fun lifestyle ✈️"),
        e("fun20", "Fun & Gaming", "⚽", "gaming fun lifestyle ⚽"),
        e("fun21", "Fun & Gaming", "🏀", "gaming fun lifestyle 🏀"),
        e("fun22", "Fun & Gaming", "🐱", "gaming fun lifestyle 🐱"),
        e("fun23", "Fun & Gaming", "🐶", "gaming fun lifestyle 🐶"),
        e("fun24", "Fun & Gaming", "🦁", "gaming fun lifestyle 🦁"),
        e("fun25", "Fun & Gaming", "🦊", "gaming fun lifestyle 🦊"),
        e("fun26", "Fun & Gaming", "🐒", "gaming fun lifestyle 🐒"),
        e("fun27", "Fun & Gaming", "🐼", "gaming fun lifestyle 🐼"),
        e("fun28", "Fun & Gaming", "🎯", "gaming fun lifestyle 🎯"),
        e("fun29", "Fun & Gaming", "🏅", "gaming fun lifestyle 🏅"),
        e("fun30", "Fun & Gaming", "🎉", "gaming fun lifestyle 🎉")
    )

    fun byId(id: String): Sticker? = ALL.firstOrNull { it.id == id }

    /** Looping animation (Anim combo id) a sticker gets when it is added, so the panel tiles and the video move like CapCut's. */
    private val ANIM = mapOf(
        "sub" to 101, "subbell" to 103, "like" to 101, "follow" to 101, "share" to 105, "new" to 110, "hot" to 106, "live" to 111,
        "star" to 109, "burst" to 101, "heart" to 102, "heartp" to 102, "redheart" to 102, "sparkHeart" to 102, "arrowR" to 105,
        "arrowY" to 105, "arrowG" to 105, "arrowW" to 105, "skull" to 110, "fire" to 106, "100" to 101, "party" to 106, "rocket" to 105
    )
    fun animOf(id: String): Int = ANIM[id] ?: 0

    /** Draws a sticker into a bitmap [w] pixels wide. */
    fun render(s: Sticker, w: Int): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        return when (s.kind) {
            "emoji" -> {
                val bmp = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
                p.textSize = w * 0.78f; p.textAlign = Paint.Align.CENTER
                val c = Canvas(bmp)
                c.drawText(s.glyph, w / 2f, w / 2f - (p.ascent() + p.descent()) / 2f, p)
                bmp
            }
            "badge" -> badge(s, w, p)
            "banner" -> banner(s, w, p)
            "arrow" -> arrow(s, w, p)
            else -> shape(s, w, p)
        }
    }

    private fun badge(s: Sticker, w: Int, p: Paint): Bitmap {
        val h = (w * 0.34f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = RectF(w * 0.02f, h * 0.06f, w * 0.98f, h * 0.94f)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), s.c1, s.c2, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, h * 0.28f, h * 0.28f, p)
        p.shader = null
        p.color = 0xFFFFFFFF.toInt(); p.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        var size = h * 0.46f
        val full = (if (s.glyph.isNotEmpty()) s.glyph + " " else "") + s.label
        p.textSize = size
        val maxW = w * 0.86f
        if (p.measureText(full) > maxW) { size *= maxW / p.measureText(full); p.textSize = size }
        p.textAlign = Paint.Align.CENTER
        c.drawText(full, w / 2f, h / 2f - (p.ascent() + p.descent()) / 2f, p)
        return bmp
    }

    private fun banner(s: Sticker, w: Int, p: Paint): Bitmap {
        val h = (w * 0.3f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        p.typeface = Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC)
        var size = h * 0.7f
        p.textSize = size
        if (p.measureText(s.label) > w * 0.94f) { size *= w * 0.94f / p.measureText(s.label); p.textSize = size }
        p.textAlign = Paint.Align.CENTER
        val y = h / 2f - (p.ascent() + p.descent()) / 2f
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.22f; p.strokeJoin = Paint.Join.ROUND; p.color = s.c2
        c.drawText(s.label, w / 2f, y, p)
        p.style = Paint.Style.FILL; p.color = s.c1
        c.drawText(s.label, w / 2f, y, p)
        return bmp
    }

    private fun arrow(s: Sticker, w: Int, p: Paint): Bitmap {
        val bmp = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val f = w.toFloat()
        val path = Path().apply {      // an arrow pointing to the bottom-right, like CapCut's red one
            moveTo(f * 0.15f, f * 0.30f); lineTo(f * 0.55f, f * 0.30f); lineTo(f * 0.55f, f * 0.12f)
            lineTo(f * 0.90f, f * 0.50f); lineTo(f * 0.55f, f * 0.88f); lineTo(f * 0.55f, f * 0.70f); lineTo(f * 0.15f, f * 0.70f); close()
        }
        p.color = 0x55000000; c.save(); c.translate(f * 0.015f, f * 0.02f); c.drawPath(path, p); c.restore()
        p.color = s.c1; c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = f * 0.025f; p.color = 0xFFFFFFFF.toInt(); p.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, p)
        return bmp
    }

    private fun shape(s: Sticker, w: Int, p: Paint): Bitmap {
        val bar = s.glyph == "bar"
        val h = if (bar) (w * 0.3f).toInt() else w
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val f = w.toFloat()
        val path = Path()
        when (s.glyph) {
            "star" -> for (i in 0 until 10) { val a = Math.PI / 5 * i - Math.PI / 2; val r = if (i % 2 == 0) 0.46f else 0.2f
                val x = f * (0.5f + r * cos(a).toFloat()); val y = f * (0.52f + r * sin(a).toFloat()); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            "burst" -> for (i in 0 until 24) { val a = Math.PI / 12 * i; val r = if (i % 2 == 0) 0.48f else 0.34f
                val x = f * (0.5f + r * cos(a).toFloat()); val y = f * (0.5f + r * sin(a).toFloat()); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            "heart" -> { path.moveTo(f * 0.5f, f * 0.88f); path.cubicTo(f * 0.02f, f * 0.55f, f * 0.1f, f * 0.12f, f * 0.5f, f * 0.34f)
                path.cubicTo(f * 0.9f, f * 0.12f, f * 0.98f, f * 0.55f, f * 0.5f, f * 0.88f); path.close() }
            "circle", "ring" -> path.addCircle(f / 2f, f / 2f, f * 0.44f, Path.Direction.CW)
            "bar" -> path.addRect(f * 0.02f, h * 0.15f, f * 0.98f, h * 0.85f, Path.Direction.CW)
            else -> path.addRoundRect(RectF(f * 0.08f, f * 0.08f, f * 0.92f, f * 0.92f), f * 0.08f, f * 0.08f, Path.Direction.CW)
        }
        if (s.glyph == "ring") {
            p.style = Paint.Style.STROKE; p.strokeWidth = f * 0.07f; p.color = s.c1; c.drawPath(path, p)
        } else {
            p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), s.c1, s.c2, Shader.TileMode.CLAMP)
            c.drawPath(path, p)
            p.shader = null
            if (!bar) { p.style = Paint.Style.STROKE; p.strokeWidth = f * 0.03f; p.color = 0xFFFFFFFF.toInt(); p.strokeJoin = Paint.Join.ROUND; c.drawPath(path, p) }
        }
        return bmp
    }
}

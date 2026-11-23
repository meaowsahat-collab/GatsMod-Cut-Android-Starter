package com.gatsmod.cut

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** How a clip looks at one instant of an animation: size, shift (picture units, 2 = one full width), rotation (degrees) and brightness. */
data class Pose(val sx: Float = 1f, val sy: Float = 1f, val x: Float = 0f, val y: Float = 0f, val rot: Float = 0f, val gain: Float = 1f) {
    operator fun times(o: Pose) = Pose(sx * o.sx, sy * o.sy, x + o.x, y + o.y, rot + o.rot, gain * o.gain)
}

class AnimDef(val id: Int, val name: String, val glyph: String, val cats: String)

/** In / Out / Combo animations like CapCut's panel. Everything is a plain function of time, so preview and export match. */
object Anim {
    val CATS = listOf("Trending", "Basic", "Light", "Glitch", "Mask", "3D", "Vibration", "Zoom", "Slide", "Blur")

    val IN = listOf(
        AnimDef(1, "Zoom In", "⤢", "Trending,Basic,Zoom"), AnimDef(2, "Pop", "✦", "Trending,Basic"),
        AnimDef(3, "Bounce In", "◉", "Trending,Basic"), AnimDef(4, "Spin In", "↻", "Trending,Basic"),
        AnimDef(5, "Flash In", "☀", "Trending,Light"), AnimDef(6, "Fade In", "◐", "Basic,Light,Blur"),
        AnimDef(7, "Slide Left", "⇦", "Basic,Slide"), AnimDef(8, "Slide Right", "⇨", "Basic,Slide"),
        AnimDef(9, "Slide Up", "⇧", "Basic,Slide"), AnimDef(10, "Slide Down", "⇩", "Basic,Slide"),
        AnimDef(11, "Shrink In", "⤡", "Basic,Zoom"), AnimDef(12, "Blink In", "◌", "Light,Blur"),
        AnimDef(13, "Burn In", "✺", "Trending,Light"), AnimDef(14, "Glitch In", "▚", "Trending,Glitch"),
        AnimDef(16, "Stutter In", "▤", "Glitch"), AnimDef(22, "Jitter In", "▦", "Glitch"),
        AnimDef(17, "Flip X", "⇋", "3D"), AnimDef(18, "Flip Y", "⇅", "3D"),
        AnimDef(19, "Swing In", "⌒", "Trending,3D"), AnimDef(20, "Tilt In", "◢", "3D"),
        AnimDef(15, "Shake In", "≋", "Vibration"), AnimDef(21, "Vibration In", "∿", "Vibration"), AnimDef(23, "Rumble In", "≈", "Vibration"),
        AnimDef(24, "Circle Reveal", "●", "Trending,Mask"), AnimDef(25, "Square Reveal", "■", "Mask"), AnimDef(26, "Diamond Reveal", "◆", "Mask")
    )

    val EXTRA_IN = listOf(
        AnimDef(200, "Windshield Sweep", "✦", "Trending"),
        AnimDef(201, "Paging Down", "⤢", "Trending"),
        AnimDef(202, "Cartoon Fire Blast", "◉", "Trending"),
        AnimDef(203, "New Year Spark", "↻", "Trending"),
        AnimDef(204, "Pyro Blast", "☀", "Trending"),
        AnimDef(205, "Astral Flip-In", "◐", "Trending"),
        AnimDef(206, "Disorientation", "⇦", "Trending"),
        AnimDef(207, "Mid-Part Pop", "⇨", "Trending"),
        AnimDef(208, "Cursed Smoke", "⇧", "Trending"),
        AnimDef(209, "Forest Escape", "⇩", "Trending"),
        AnimDef(210, "Fade Rise", "⤡", "Basic"),
        AnimDef(211, "Gentle Float", "◌", "Basic"),
        AnimDef(212, "Soft Tilt", "✺", "Basic"),
        AnimDef(213, "Clean Drop", "▚", "Basic"),
        AnimDef(214, "Steady Shift", "≋", "Basic"),
        AnimDef(215, "Ease Scale", "▤", "Basic"),
        AnimDef(216, "Smooth Glide", "▦", "Basic"),
        AnimDef(217, "Calm Drift", "⇋", "Basic"),
        AnimDef(218, "Light Pop", "⇅", "Basic"),
        AnimDef(219, "Swing Exit", "⌒", "Basic"),
        AnimDef(220, "Solar Flare", "◢", "Light"),
        AnimDef(221, "Neon Pulse", "∿", "Light"),
        AnimDef(222, "Prism Glow", "≈", "Light"),
        AnimDef(223, "Flash Beam", "●", "Light"),
        AnimDef(224, "Golden Ray", "■", "Light"),
        AnimDef(225, "Aurora Wave", "◆", "Light"),
        AnimDef(226, "Spark Burst", "✦", "Light"),
        AnimDef(227, "Halo Expand", "⤢", "Light"),
        AnimDef(228, "Laser Sweep", "◉", "Light"),
        AnimDef(229, "Twilight Fade", "↻", "Light"),
        AnimDef(230, "Scanline Tear", "☀", "Glitch"),
        AnimDef(231, "RGB Split", "◐", "Glitch"),
        AnimDef(232, "Signal Drop", "⇦", "Glitch"),
        AnimDef(233, "Pixel Warp", "⇨", "Glitch"),
        AnimDef(234, "Static Flash", "⇧", "Glitch"),
        AnimDef(235, "Channel Shift", "⇩", "Glitch"),
        AnimDef(236, "Frame Jitter", "⤡", "Glitch"),
        AnimDef(237, "Cyber Skew", "◌", "Glitch"),
        AnimDef(238, "Data Slice", "✺", "Glitch"),
        AnimDef(239, "Corrupt Pulse", "▚", "Glitch"),
        AnimDef(240, "Center Reveal", "≋", "Mask"),
        AnimDef(241, "Blind Curtain", "▤", "Mask"),
        AnimDef(242, "Iris Open", "▦", "Mask"),
        AnimDef(243, "Shutter Wipe", "⇋", "Mask"),
        AnimDef(244, "Ribbon Unfold", "⇅", "Mask"),
        AnimDef(245, "Strip Sweep", "⌒", "Mask"),
        AnimDef(246, "Split Frame", "◢", "Mask"),
        AnimDef(247, "Curtain Drop", "∿", "Mask"),
        AnimDef(248, "Edge Peel", "≈", "Mask"),
        AnimDef(249, "Frame Expand", "●", "Mask"),
        AnimDef(250, "Orbit Flip", "■", "3D"),
        AnimDef(251, "Perspective Twist", "◆", "3D"),
        AnimDef(252, "Depth Dive", "✦", "3D"),
        AnimDef(253, "Card Turn", "⤢", "3D"),
        AnimDef(254, "3D Roll", "◉", "3D"),
        AnimDef(255, "Tunnel Pull", "↻", "3D"),
        AnimDef(256, "Cube Pivot", "☀", "3D"),
        AnimDef(257, "Skew Spin", "◐", "3D"),
        AnimDef(258, "Camera Tilt", "⇦", "3D"),
        AnimDef(259, "3D Swing", "⇨", "3D"),
        AnimDef(260, "Bass Shake", "⇧", "Vibration"),
        AnimDef(261, "Tremor Hit", "⇩", "Vibration"),
        AnimDef(262, "Quake Pulse", "⤡", "Vibration"),
        AnimDef(263, "Rumble Beat", "◌", "Vibration"),
        AnimDef(264, "Shock Jolt", "✺", "Vibration"),
        AnimDef(265, "Impact Buzz", "▚", "Vibration"),
        AnimDef(266, "Wobble Ring", "≋", "Vibration"),
        AnimDef(267, "Beat Bounce", "▤", "Vibration"),
        AnimDef(268, "Micro Quake", "▦", "Vibration"),
        AnimDef(269, "Turbo Shake", "⇋", "Vibration"),
        AnimDef(270, "Punch Zoom", "⇅", "Zoom"),
        AnimDef(271, "Slow Push", "⌒", "Zoom"),
        AnimDef(272, "Snap In", "◢", "Zoom"),
        AnimDef(273, "Breath Zoom", "∿", "Zoom"),
        AnimDef(274, "Macro Pull", "≈", "Zoom"),
        AnimDef(275, "Quick Zoom", "●", "Zoom"),
        AnimDef(276, "Lens Surge", "■", "Zoom"),
        AnimDef(277, "Deep Zoom", "◆", "Zoom"),
        AnimDef(278, "Zoom Bounce", "✦", "Zoom"),
        AnimDef(279, "Wide Pull", "⤢", "Zoom"),
        AnimDef(280, "Slide Right", "◉", "Slide"),
        AnimDef(281, "Slide Down", "↻", "Slide"),
        AnimDef(282, "Swift Glide", "☀", "Slide"),
        AnimDef(283, "Sweep Across", "◐", "Slide"),
        AnimDef(284, "Step Slide", "⇦", "Slide"),
        AnimDef(285, "Cascade Drop", "⇨", "Slide"),
        AnimDef(286, "Track Shift", "⇧", "Slide"),
        AnimDef(287, "Side Roll", "⇩", "Slide"),
        AnimDef(288, "Fast Slide", "⤡", "Slide"),
        AnimDef(289, "Glide Exit", "◌", "Slide"),
        AnimDef(290, "Focus Pull", "✺", "Blur"),
        AnimDef(291, "Soft Defocus", "▚", "Blur"),
        AnimDef(292, "Motion Blur", "≋", "Blur"),
        AnimDef(293, "Dream Haze", "▤", "Blur"),
        AnimDef(294, "Lens Blur In", "▦", "Blur"),
        AnimDef(295, "Mist Focus", "⇋", "Blur"),
        AnimDef(296, "Blur Zoom", "⇅", "Blur"),
        AnimDef(297, "Hazy Drift", "⌒", "Blur"),
        AnimDef(298, "Sharp Snap", "◢", "Blur"),
        AnimDef(299, "Soft Fade", "∿", "Blur")
    )

    // Out animations replay an In animation backwards; [src] is the In id used.
    class OutDef(val def: AnimDef, val src: Int)
    val OUT = listOf(
        OutDef(AnimDef(1001, "Zoom Out", "⤡", "Trending,Basic,Zoom"), 1), OutDef(AnimDef(1002, "Pop Out", "✦", "Trending,Basic"), 2),
        OutDef(AnimDef(1003, "Bounce Out", "◉", "Trending,Basic"), 3), OutDef(AnimDef(1004, "Spin Out", "↺", "Trending,Basic"), 4),
        OutDef(AnimDef(1005, "Flash Out", "☀", "Trending,Light"), 5), OutDef(AnimDef(1006, "Fade Out", "◐", "Basic,Light,Blur"), 6),
        OutDef(AnimDef(1007, "Slide Left", "⇦", "Basic,Slide"), 8), OutDef(AnimDef(1008, "Slide Right", "⇨", "Basic,Slide"), 7),
        OutDef(AnimDef(1009, "Slide Up", "⇧", "Basic,Slide"), 10), OutDef(AnimDef(1010, "Slide Down", "⇩", "Basic,Slide"), 9),
        OutDef(AnimDef(1011, "Grow Out", "⤢", "Basic,Zoom"), 11), OutDef(AnimDef(1012, "Blink Out", "◌", "Light,Blur"), 12),
        OutDef(AnimDef(1013, "Burn Out", "✺", "Trending,Light"), 13), OutDef(AnimDef(1014, "Glitch Out", "▚", "Trending,Glitch"), 14),
        OutDef(AnimDef(1016, "Stutter Out", "▤", "Glitch"), 16), OutDef(AnimDef(1022, "Jitter Out", "▦", "Glitch"), 22),
        OutDef(AnimDef(1017, "Flip X Out", "⇋", "3D"), 17), OutDef(AnimDef(1018, "Flip Y Out", "⇅", "3D"), 18),
        OutDef(AnimDef(1019, "Swing Out", "⌒", "Trending,3D"), 19), OutDef(AnimDef(1020, "Tilt Out", "◣", "3D"), 20),
        OutDef(AnimDef(1015, "Shake Out", "≋", "Vibration"), 15), OutDef(AnimDef(1021, "Vibration Out", "∿", "Vibration"), 21),
        OutDef(AnimDef(1023, "Rumble Out", "≈", "Vibration"), 23),
        OutDef(AnimDef(1024, "Circle Close", "●", "Trending,Mask"), 24), OutDef(AnimDef(1025, "Square Close", "■", "Mask"), 25), OutDef(AnimDef(1026, "Diamond Close", "◆", "Mask"), 26)
    )

    val EXTRA_OUT = EXTRA_IN.mapIndexed { i, d -> OutDef(AnimDef(1200+i, d.name + " Out", d.glyph, d.cats), d.id) }

    val COMBO = listOf(
        AnimDef(101, "Pulse", "◎", "Trending,Basic"), AnimDef(102, "Heartbeat", "♥", "Trending,Basic"),
        AnimDef(107, "Zoom Drift", "⤢", "Trending,Basic,Zoom"), AnimDef(108, "Breathe", "◍", "Basic,Light,Zoom"),
        AnimDef(113, "Pan Right", "⇨", "Basic,Slide"), AnimDef(114, "Pan Left", "⇦", "Basic,Slide"), AnimDef(105, "Float", "☁", "Basic,Blur"),
        AnimDef(111, "Strobe", "☀", "Light"), AnimDef(112, "Glitch Loop", "▚", "Trending,Glitch"),
        AnimDef(110, "Wobble", "≀", "3D"), AnimDef(109, "Spin Loop", "↻", "3D"), AnimDef(115, "Pendulum", "⌒", "3D"), AnimDef(103, "Swing", "⌓", "3D"),
        AnimDef(106, "Shake", "≋", "Trending,Vibration"), AnimDef(104, "Rock", "∿", "Vibration")
    )

    val EXTRA_COMBO = listOf(
        AnimDef(2200, "Windshield Sweep Loop", "⤡", "Trending"),
        AnimDef(2201, "Paging Down Loop", "◌", "Trending"),
        AnimDef(2202, "Cartoon Fire Blast Loop", "✺", "Trending"),
        AnimDef(2203, "New Year Spark Loop", "▚", "Trending"),
        AnimDef(2204, "Cartoon Fire Blast Loop", "≋", "Trending"),
        AnimDef(2205, "Astral Flip-In Loop", "▤", "Trending"),
        AnimDef(2206, "Windshield Sweep Loop", "▦", "Trending"),
        AnimDef(2207, "New Year Spark Loop", "⇋", "Trending"),
        AnimDef(2208, "Cursed Smoke Loop", "⇅", "Trending"),
        AnimDef(2209, "Forest Escape Loop", "⌒", "Trending"),
        AnimDef(2210, "Fade Rise Loop", "◢", "Basic"),
        AnimDef(2211, "Gentle Float Loop", "∿", "Basic"),
        AnimDef(2212, "Windshield Sweep Loop", "≈", "Basic"),
        AnimDef(2213, "Paging Down Loop", "●", "Basic"),
        AnimDef(2214, "Steady Shift Loop", "■", "Basic"),
        AnimDef(2215, "Forest Escape Loop", "◆", "Basic"),
        AnimDef(2216, "Smooth Glide Loop", "✦", "Basic"),
        AnimDef(2217, "Windshield Sweep Loop", "⤢", "Basic"),
        AnimDef(2218, "New Year Spark Loop", "◉", "Basic"),
        AnimDef(2219, "Swing Exit Loop", "↻", "Basic"),
        AnimDef(2220, "Solar Flare Loop", "☀", "Light"),
        AnimDef(2221, "Gentle Float Loop", "◐", "Light"),
        AnimDef(2222, "Cursed Smoke Loop", "⇦", "Light"),
        AnimDef(2223, "Solar Flare Loop", "⇨", "Light"),
        AnimDef(2224, "New Year Spark Loop", "⇧", "Light"),
        AnimDef(2225, "Cursed Smoke Loop", "⇩", "Light"),
        AnimDef(2226, "Solar Flare Loop", "⤡", "Light"),
        AnimDef(2227, "New Year Spark Loop", "◌", "Light"),
        AnimDef(2228, "Windshield Sweep Loop", "✺", "Light"),
        AnimDef(2229, "Fade Rise Loop", "▚", "Light"),
        AnimDef(2230, "Cursed Smoke Loop", "≋", "Glitch"),
        AnimDef(2231, "Cursed Smoke Loop", "▤", "Glitch"),
        AnimDef(2232, "Paging Down Loop", "▦", "Glitch"),
        AnimDef(2233, "Windshield Sweep Loop", "⇋", "Glitch"),
        AnimDef(2234, "Static Flash Loop", "⇅", "Glitch"),
        AnimDef(2235, "Cursed Smoke Loop", "⌒", "Glitch"),
        AnimDef(2236, "Frame Jitter Loop", "◢", "Glitch"),
        AnimDef(2237, "Windshield Sweep Loop", "∿", "Glitch"),
        AnimDef(2238, "Paging Down Loop", "≈", "Glitch"),
        AnimDef(2239, "Cursed Smoke Loop", "●", "Glitch"),
        AnimDef(2240, "Center Reveal Loop", "■", "Mask"),
        AnimDef(2241, "Blind Curtain Loop", "◆", "Mask"),
        AnimDef(2242, "Iris Open Loop", "✦", "Mask"),
        AnimDef(2243, "Paging Down Loop", "⤢", "Mask"),
        AnimDef(2244, "Center Reveal Loop", "◉", "Mask"),
        AnimDef(2245, "Blind Curtain Loop", "↻", "Mask"),
        AnimDef(2246, "Iris Open Loop", "☀", "Mask"),
        AnimDef(2247, "Blind Curtain Loop", "◐", "Mask"),
        AnimDef(2248, "Center Reveal Loop", "⇦", "Mask"),
        AnimDef(2249, "Iris Open Loop", "⇨", "Mask"),
        AnimDef(2250, "Astral Flip-In Loop", "⇧", "3D"),
        AnimDef(2251, "Windshield Sweep Loop", "⇩", "3D"),
        AnimDef(2252, "Forest Escape Loop", "⤡", "3D"),
        AnimDef(2253, "Astral Flip-In Loop", "◌", "3D"),
        AnimDef(2254, "3D Roll Loop", "✺", "3D"),
        AnimDef(2255, "Forest Escape Loop", "▚", "3D"),
        AnimDef(2256, "Cube Pivot Loop", "≋", "3D"),
        AnimDef(2257, "Windshield Sweep Loop", "▤", "3D"),
        AnimDef(2258, "Windshield Sweep Loop", "▦", "3D"),
        AnimDef(2259, "Swing Exit Loop", "⇋", "3D"),
        AnimDef(2260, "Static Flash Loop", "⇅", "Vibration"),
        AnimDef(2261, "Tremor Hit Loop", "⌒", "Vibration"),
        AnimDef(2262, "Quake Pulse Loop", "◢", "Vibration"),
        AnimDef(2263, "Rumble Beat Loop", "∿", "Vibration"),
        AnimDef(2264, "Static Flash Loop", "≈", "Vibration"),
        AnimDef(2265, "Tremor Hit Loop", "●", "Vibration"),
        AnimDef(2266, "Windshield Sweep Loop", "■", "Vibration"),
        AnimDef(2267, "Beat Bounce Loop", "◆", "Vibration"),
        AnimDef(2268, "Quake Pulse Loop", "✦", "Vibration"),
        AnimDef(2269, "Static Flash Loop", "⤢", "Vibration"),
        AnimDef(2270, "Forest Escape Loop", "◉", "Zoom"),
        AnimDef(2271, "Slow Push Loop", "↻", "Zoom"),
        AnimDef(2272, "New Year Spark Loop", "☀", "Zoom"),
        AnimDef(2273, "Gentle Float Loop", "◐", "Zoom"),
        AnimDef(2274, "Forest Escape Loop", "⇦", "Zoom"),
        AnimDef(2275, "New Year Spark Loop", "⇨", "Zoom"),
        AnimDef(2276, "Gentle Float Loop", "⇧", "Zoom"),
        AnimDef(2277, "Forest Escape Loop", "⇩", "Zoom"),
        AnimDef(2278, "Beat Bounce Loop", "⤡", "Zoom"),
        AnimDef(2279, "Slow Push Loop", "◌", "Zoom"),
        AnimDef(2280, "Smooth Glide Loop", "✺", "Slide"),
        AnimDef(2281, "Paging Down Loop", "▚", "Slide"),
        AnimDef(2282, "Smooth Glide Loop", "≋", "Slide"),
        AnimDef(2283, "Windshield Sweep Loop", "▤", "Slide"),
        AnimDef(2284, "Steady Shift Loop", "▦", "Slide"),
        AnimDef(2285, "Paging Down Loop", "⇋", "Slide"),
        AnimDef(2286, "Smooth Glide Loop", "⇅", "Slide"),
        AnimDef(2287, "Steady Shift Loop", "⌒", "Slide"),
        AnimDef(2288, "Smooth Glide Loop", "◢", "Slide"),
        AnimDef(2289, "Swing Exit Loop", "∿", "Slide"),
        AnimDef(2290, "Fade Rise Loop", "≈", "Blur"),
        AnimDef(2291, "Fade Rise Loop", "●", "Blur"),
        AnimDef(2292, "Steady Shift Loop", "■", "Blur"),
        AnimDef(2293, "Cursed Smoke Loop", "◆", "Blur"),
        AnimDef(2294, "Fade Rise Loop", "✦", "Blur"),
        AnimDef(2295, "Fade Rise Loop", "⤢", "Blur"),
        AnimDef(2296, "Forest Escape Loop", "◉", "Blur"),
        AnimDef(2297, "Cursed Smoke Loop", "↻", "Blur"),
        AnimDef(2298, "New Year Spark Loop", "☀", "Blur"),
        AnimDef(2299, "Fade Rise Loop", "◐", "Blur")
    )

    private fun easeOut(p: Float): Float { val q = 1f - p; return 1f - q * q * q }
    private fun back(p: Float): Float { val q = p - 1f; return 1f + 2.70158f * q * q * q + 1.70158f * q * q }
    private fun hash(n: Int): Float { var h = n * 374761393 + 668265263; h = (h xor (h ushr 13)) * 1274126177; return ((h xor (h ushr 16)) and 0xFFFF) / 65535f }

    /** Pose of In animation [id] at progress [p] (0 = just started, 1 = finished and at rest). */
    private val EXTRA_BASES = listOf(20, 10, 13, 2, 13, 17, 20, 2, 14, 1, 6, 108, 20, 10, 7, 1, 8, 20, 2, 19, 5, 108, 14, 5, 2, 14, 5, 2, 20, 6, 14, 14, 10, 20, 15, 14, 22, 20, 10, 14, 24, 25, 26, 10, 24, 25, 26, 25, 24, 26, 17, 20, 1, 17, 4, 1, 18, 20, 20, 19, 15, 21, 23, 106, 15, 21, 20, 3, 23, 15, 1, 107, 2, 108, 1, 2, 108, 1, 3, 107, 8, 10, 8, 20, 7, 10, 8, 7, 8, 19, 6, 6, 7, 14, 6, 6, 1, 14, 2, 6)
    fun extraBase(id: Int) = EXTRA_BASES.getOrNull(id - 200) ?: 0

    fun inPose(id: Int, p0: Float): Pose {
        if (id in 200..299) return inPose(extraBase(id), p0)
        val p = p0.coerceIn(0f, 1f)
        val e = easeOut(p)
        val r = 1f - p
        return when (id) {
            1 -> Pose(sx = 0.4f + 0.6f * e, sy = 0.4f + 0.6f * e, gain = e)
            2 -> Pose(sx = 1.35f - 0.35f * e, sy = 1.35f - 0.35f * e, gain = min(1f, p * 3f))
            3 -> { val s = max(0.01f, back(p)); Pose(sx = s, sy = s) }
            4 -> Pose(sx = 0.3f + 0.7f * e, sy = 0.3f + 0.7f * e, rot = 270f * (1f - e), gain = e)
            5 -> Pose(sx = 1.05f - 0.05f * e, sy = 1.05f - 0.05f * e, gain = 1f + 3f * (1f - e) * (1f - e))
            6 -> Pose(gain = p)
            7 -> Pose(x = 2f * (1f - e))
            8 -> Pose(x = -2f * (1f - e))
            9 -> Pose(y = -2f * (1f - e))
            10 -> Pose(y = 2f * (1f - e))
            11 -> Pose(sx = 1.7f - 0.7f * e, sy = 1.7f - 0.7f * e, gain = e)
            12 -> Pose(gain = if (p < 0.8f && floor(p * 10f).toInt() % 2 == 0) 0.2f else 1f)
            13 -> Pose(sx = 1.1f - 0.1f * e, sy = 1.1f - 0.1f * e, gain = e * (1f + 1.5f * sin(PI.toFloat() * p)))
            14 -> { val a = r; val n = hash(floor(p * 24f).toInt()); val n2 = hash(floor(p * 24f).toInt() + 99)
                Pose(sx = 1f + (n2 - 0.5f) * 0.3f * a, x = (n - 0.5f) * 0.5f * a, gain = 1f - (if (n > 0.7f) 0.5f * a else 0f)) }
            15 -> Pose(x = sin(p * 60f) * 0.08f * r, rot = sin(p * 50f) * 4f * r)
            16 -> Pose(gain = if (p < 0.7f && floor(p * 16f).toInt() % 2 == 0) 0f else 1f)
            17 -> Pose(sx = max(0.01f, sin(e * PI.toFloat() / 2f)))
            18 -> Pose(sy = max(0.01f, sin(e * PI.toFloat() / 2f)))
            19 -> Pose(rot = 40f * r * r * cos(p * 9f), gain = min(1f, p * 4f))
            20 -> Pose(rot = -30f * (1f - e), x = -0.6f * (1f - e), gain = e)
            21 -> Pose(x = sin(p * 120f) * 0.04f * r, y = cos(p * 140f) * 0.04f * r)
            22 -> { val n = hash(floor(p * 30f).toInt() + 7); Pose(x = (n - 0.5f) * 0.12f * r, y = (hash(floor(p * 30f).toInt() + 31) - 0.5f) * 0.06f * r, gain = 1f - 0.4f * r * (if (n > 0.6f) 1f else 0f)) }
            23 -> { val s = 1f + 0.05f * sin(p * 80f) * r; Pose(sx = s, sy = s, rot = sin(p * 70f) * 2f * r) }
            else -> Pose()
        }
    }

    /** Pose of a looping Combo animation [id] at [tMs] into a clip that lasts [lenMs]. */
    private val EXTRA_COMBO_BASES = listOf(110, 113, 111, 108, 111, 109, 110, 108, 112, 107, 108, 108, 110, 113, 113, 107, 113, 110, 108, 103, 108, 108, 112, 108, 108, 112, 108, 108, 110, 108, 112, 112, 113, 110, 106, 112, 108, 110, 113, 112, 108, 108, 108, 113, 108, 108, 108, 108, 108, 108, 109, 110, 107, 109, 108, 107, 110, 110, 110, 103, 106, 108, 108, 108, 106, 108, 110, 108, 108, 106, 107, 108, 108, 108, 107, 108, 108, 107, 108, 108, 113, 113, 113, 110, 113, 113, 113, 113, 113, 103, 108, 108, 113, 112, 108, 108, 107, 112, 108, 108)
    fun extraComboBase(id: Int) = EXTRA_COMBO_BASES.getOrNull(id - 2200) ?: 0

    fun comboPose(id: Int, tMs: Long, lenMs: Long): Pose {
        if (id in 2200..2299) return comboPose(extraComboBase(id), tMs, lenMs)
        val t = tMs.toFloat()
        val tau = (2.0 * PI).toFloat()
        fun w(period: Float) = tau * t / period
        return when (id) {
            101 -> { val s = 1f + 0.1f * (0.5f - 0.5f * cos(w(800f))); Pose(sx = s, sy = s) }
            102 -> { val pw = (t % 1000f) / 1000f
                val s = 1f + 0.1f * (exp(-((pw - 0.1f) / 0.06f).pow(2)) + 0.7f * exp(-((pw - 0.3f) / 0.06f).pow(2))); Pose(sx = s, sy = s) }
            103 -> Pose(sx = 1.08f, sy = 1.08f, rot = 6f * sin(w(1600f)))
            104 -> Pose(sx = 1.06f, sy = 1.06f, rot = 3f * sin(w(500f)))
            105 -> Pose(sx = 1.08f, sy = 1.08f, x = 0.02f * cos(w(3000f)), y = 0.04f * sin(w(2000f)))
            106 -> Pose(sx = 1.08f, sy = 1.08f, x = 0.025f * sin(w(90f)), y = 0.025f * cos(w(110f)))
            107 -> { val s = 1f + 0.25f * (t / max(1f, lenMs.toFloat())).coerceIn(0f, 1f); Pose(sx = s, sy = s) }
            108 -> { val s = 1.08f + 0.07f * sin(w(2400f)); Pose(sx = s, sy = s) }
            109 -> Pose(rot = 360f * ((t % 3000f) / 3000f))
            110 -> Pose(sx = 1f + 0.06f * sin(w(700f)), sy = 1f - 0.06f * sin(w(700f)))
            111 -> Pose(gain = if (floor(t / 150f).toInt() % 2 == 0) 1f else 0.55f)
            112 -> { val ph = t % 1200f
                if (ph > 200f) Pose() else { val k = floor(t / 40f).toInt(); val n = hash(k)
                    Pose(sx = 1f + (hash(k + 99) - 0.5f) * 0.2f, x = (n - 0.5f) * 0.3f, gain = if (n > 0.7f) 0.6f else 1f) } }
            113 -> { val f = (t / max(1f, lenMs.toFloat())).coerceIn(0f, 1f); Pose(sx = 1.2f, sy = 1.2f, x = -0.1f + 0.2f * f) }
            114 -> { val f = (t / max(1f, lenMs.toFloat())).coerceIn(0f, 1f); Pose(sx = 1.2f, sy = 1.2f, x = 0.1f - 0.2f * f) }
            115 -> Pose(sx = 1.12f, sy = 1.12f, rot = 12f * sin(w(2000f)), x = 0.05f * sin(w(2000f)))
            else -> Pose()
        }
    }

    fun isMask(id: Int) = id in 24..26 || id in 1024..1026 || (id in 200..299 && extraBase(id) in 24..26)

    private fun durs(animIn: Int, inMs: Long, animOut: Int, outMs: Long, lenMs: Long): LongArray {
        var a = if (animIn != 0) inMs else 0L
        var b = if (animOut != 0) outMs else 0L
        if (a + b > lenMs && a + b > 0) { val k = lenMs.toFloat() / (a + b); a = (a * k).toLong(); b = (b * k).toLong() }
        return longArrayOf(a, b)
    }

    /** Shape (0 = none, 1 circle, 2 rectangle, 3 diamond) and size of the reveal mask for the shader at [tMs]. */
    fun maskState(animIn: Int, inMs: Long, animOut: Int, outMs: Long, tMs: Long, lenMs: Long): FloatArray {
        val d = durs(animIn, inMs, animOut, outMs, lenMs)
        var id = 0
        var p = 1f
        if (isMask(animIn) && d[0] > 0 && tMs < d[0]) { id = animIn; p = tMs.toFloat() / d[0] }
        else if (isMask(animOut) && d[1] > 0 && tMs > lenMs - d[1]) { id = outSrc(animOut); p = (lenMs - tMs).toFloat() / d[1] }
        if (id == 0) return floatArrayOf(0f, 1f)
        val maskBase = if (id in 200..299) extraBase(id) else id
        val shape = maskBase - 23                 // 24,25,26 -> 1,2,3
        val big = if (shape == 2) 1.9f else 2.8f
        return floatArrayOf(shape.toFloat(), max(0.02f, easeOut(p.coerceIn(0f, 1f)) * big))
    }

    fun outSrc(id: Int): Int = OUT.firstOrNull { it.def.id == id }?.src ?: EXTRA_OUT.firstOrNull { it.def.id == id }?.src ?: 0

    /**
     * The combined pose at [tMs] (ms since the clip start on the timeline) for a clip [lenMs] long.
     * In and Out lengths are shortened if together they would be longer than the clip.
     */
    fun poseOf(c: Clip, tMs: Long): Pose {
        var p = pose(c.animIn, c.animInMs, c.animOut, c.animOutMs, c.animCombo, tMs, c.lengthMs)
        if (c.textMotion != 0) p *= ProText.pose(c.textMotion, tMs)
        return p
    }

    fun pose(animIn: Int, inMs: Long, animOut: Int, outMs: Long, combo: Int, tMs: Long, lenMs: Long): Pose {
        val d = durs(animIn, inMs, animOut, outMs, lenMs)
        val a = d[0]
        val b = d[1]
        var p = Pose()
        if (animIn != 0 && a > 0 && tMs < a) p *= inPose(animIn, tMs.toFloat() / a)
        if (animOut != 0 && b > 0 && tMs > lenMs - b) p *= inPose(outSrc(animOut), (lenMs - tMs).toFloat() / b)
        if (combo != 0) p *= comboPose(combo, tMs, lenMs)
        return p
    }
}

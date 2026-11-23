package com.gatsmod.cut

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Two-tone editor look: black preview area, coloured top bar + bottom (controls/timeline/toolbar) area. */
object EditorTheme {
    class Tone(val name: String, val panel: Int, val timeline: Int)

    val TONES = listOf(
        Tone("Graphite", 0xFF232327.toInt(), 0xFF1A1A1E.toInt()),
        Tone("Midnight", 0xFF18203A.toInt(), 0xFF111729.toInt()),
        Tone("Purple", 0xFF2A1D42.toInt(), 0xFF1E1431.toInt()),
        Tone("Forest", 0xFF173226.toInt(), 0xFF10231A.toInt()),
        Tone("Maroon", 0xFF3A1A1F.toInt(), 0xFF2A1216.toInt()),
        Tone("Ocean", 0xFF123238.toInt(), 0xFF0C2428.toInt()),
        Tone("Black", 0xFF0A0A0C.toInt(), 0xFF0D0D10.toInt())
    )

    private fun prefs(c: Context) = c.getSharedPreferences("theme", Context.MODE_PRIVATE)
    fun index(c: Context) = prefs(c).getInt("tone", 0).coerceIn(0, TONES.size - 1)
    fun current(c: Context) = TONES[index(c)]
    fun set(c: Context, i: Int) { prefs(c).edit().putInt("tone", i).apply() }

    /** A row of colour circles; tapping one saves it and calls [changed]. */
    fun picker(c: Context, changed: () -> Unit): View {
        val d = c.resources.displayMetrics.density
        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        val title = TextView(c)
        title.text = "Editor colour"
        title.textSize = 16f
        title.setTextColor(0xFF888888.toInt())
        title.setPadding(0, (13 * d).toInt(), 0, (6 * d).toInt())
        box.addView(title)
        val row = LinearLayout(c)
        row.gravity = Gravity.CENTER_VERTICAL
        val cells = ArrayList<View>()
        fun paint() {
            cells.forEachIndexed { i, v ->
                val g = GradientDrawable()
                g.shape = GradientDrawable.OVAL
                g.setColor(TONES[i].panel)
                g.setStroke((if (i == index(c)) 3 else 1) * d.toInt().coerceAtLeast(1), if (i == index(c)) 0xFF1E88E5.toInt() else 0xFFBBBBBB.toInt())
                v.background = g
            }
        }
        TONES.forEachIndexed { i, t ->
            val v = View(c)
            v.contentDescription = t.name
            v.setOnClickListener { set(c, i); paint(); changed() }
            val lp = LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt())
            lp.marginEnd = (10 * d).toInt()
            row.addView(v, lp)
            cells.add(v)
        }
        paint()
        box.addView(row)
        return box
    }

    fun apply(a: android.app.Activity) {
        val t = current(a)
        listOf(R.id.topBar, R.id.controls, R.id.timelineBlock, R.id.toolbarScroll).forEach {
            a.findViewById<View>(it)?.setBackgroundColor(t.panel)
        }
        a.findViewById<TimelineView>(R.id.timeline)?.let { it.setBgColor(t.timeline) }
        a.window.statusBarColor = t.panel
        a.window.navigationBarColor = t.panel
    }
}

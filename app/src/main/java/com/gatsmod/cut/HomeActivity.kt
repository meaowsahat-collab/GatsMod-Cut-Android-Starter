package com.gatsmod.cut

import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** CapCut-style start page: new video, quick tools and the list of saved projects. */
class HomeActivity : AppCompatActivity() {

    private val updater by lazy { Updater(this) }
    private lateinit var projectsBox: LinearLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_GatsModCut)
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        window.statusBarColor = 0xFFD9F4FF.toInt()
        window.navigationBarColor = Color.WHITE

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFD9F4FF.toInt(), 0xFFF4FBFF.toInt(), Color.WHITE))

        // top row
        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(14), dp(10), dp(14), dp(6))
        top.addView(iconBtn(R.drawable.ic_person) { toast("Accounts are not needed — everything stays on your phone") })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(iconBtn(R.drawable.ic_bell) { updater.check(false) })
        top.addView(iconBtn(R.drawable.ic_gear) { aboutDialog() })
        root.addView(top)

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(14), dp(6), dp(14), dp(24))

        // big cards
        val cards = LinearLayout(this)
        cards.addView(bigCard(R.drawable.ic_add, "New video", 1.5f) { openEditor(null, true) })
        cards.addView(bigCard(R.drawable.ic_photo, "Edit photo", 1f) { startActivity(Intent(this, PhotoActivity::class.java)) })
        body.addView(cards, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(116)))

        // tools grid
        val tools = listOf(
            Triple(R.drawable.ic_ai, "AutoCut", "AutoCut"),
            Triple(R.drawable.ic_retouch, "Retouch", "Retouch"),
            Triple(R.drawable.ic_resize, "Desktop editor", "Desktop editor"),
            Triple(R.drawable.ic_autocap, "Auto captions", "Auto captions"),
            Triple(R.drawable.ic_removebg, "Remove background", "Remove background"),
            Triple(R.drawable.ic_camera, "Camera", "Camera"),
            Triple(R.drawable.ic_captions, "Teleprompter", "Teleprompter"),
            Triple(R.drawable.ic_collage, "All tools", "")
        )
        for (r in 0 until 2) {
            val row = LinearLayout(this)
            row.setPadding(0, dp(14), 0, 0)
            for (i in 0 until 4) {
                val (ic, name, soonName) = tools[r * 4 + i]
                row.addView(toolCell(ic, name) { if (soonName.isEmpty()) openEditor(null, false) else if (soonName == "Camera") startActivity(Intent(this, MainActivity::class.java).putExtra("camera", true)) else if (soonName == "Teleprompter") startActivity(Intent(this, TeleprompterActivity::class.java)) else if (soonName == "Auto captions") toast("Open a video, then tap Auto captions in the bottom toolbar") else if (soonName == "Remove background") toast("Open a video, select the clip, then tap Remove BG") else toast("$soonName is coming soon") })
            }
            body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val ph = TextView(this)
        ph.text = "Projects"
        ph.textSize = 22f
        ph.setTypeface(null, Typeface.BOLD)
        ph.setTextColor(0xFF111111.toInt())
        ph.setPadding(0, dp(26), 0, dp(10))
        body.addView(ph)

        projectsBox = LinearLayout(this)
        projectsBox.orientation = LinearLayout.VERTICAL
        body.addView(projectsBox)

        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        updater.cleanup()
        updater.check(true)
    }

    override fun onResume() {
        super.onResume()
        updater.resumePendingInstall()
        refreshProjects()
    }

    private fun openEditor(id: String?, pick: Boolean) {
        val i = Intent(this, MainActivity::class.java)
        if (id != null) i.putExtra("id", id)
        i.putExtra("pick", pick)
        startActivity(i)
    }

    // ---------- pieces ----------

    private fun iconBtn(icon: Int, click: () -> Unit): View {
        val v = ImageView(this)
        v.setImageResource(icon)
        v.setColorFilter(0xFF111111.toInt())
        v.scaleType = ImageView.ScaleType.CENTER
        v.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        v.setOnClickListener { click() }
        return v
    }

    private fun bigCard(icon: Int, label: String, weight: Float, click: () -> Unit): View {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.gravity = Gravity.CENTER
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFB5E6FF.toInt(), 0xFF6FD3F2.toInt()))
        g.cornerRadius = dp(22).toFloat()
        l.background = g
        l.isClickable = true
        l.isFocusable = true
        l.setOnClickListener { click() }
        val box = FrameLayout(this)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(12).toFloat()
        bg.setColor(0xFF111111.toInt())
        box.background = bg
        val iv = ImageView(this)
        iv.setImageResource(icon)
        box.addView(iv, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        l.addView(box, LinearLayout.LayoutParams(dp(44), dp(44)))
        val t = TextView(this)
        t.text = label
        t.gravity = Gravity.CENTER
        t.textSize = 17f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(0xFF111111.toInt())
        t.setPadding(0, dp(8), 0, 0)
        l.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
        lp.setMargins(if (weight > 1f) 0 else dp(10), 0, 0, 0)
        l.layoutParams = lp
        return l
    }

    private fun toolCell(icon: Int, label: String, click: () -> Unit): View {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.gravity = Gravity.CENTER_HORIZONTAL
        l.setOnClickListener { click() }
        val box = FrameLayout(this)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(18).toFloat()
        bg.setColor(0xFFF1F3F6.toInt())
        box.background = bg
        val iv = ImageView(this)
        iv.setImageResource(icon)
        iv.setColorFilter(0xFF222222.toInt())
        box.addView(iv, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        l.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))
        val t = TextView(this)
        t.text = label
        t.textSize = 11f
        t.gravity = Gravity.CENTER
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(0xFF111111.toInt())
        t.setPadding(0, dp(6), 0, 0)
        l.addView(t)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(5), 0, dp(5), 0)
        l.layoutParams = lp
        return l
    }

    // ---------- projects ----------

    private fun refreshProjects() {
        projectsBox.removeAllViews()
        val list = ProjectStore.list(this)
        if (list.isEmpty()) {
            val t = TextView(this)
            t.text = "No projects yet. Tap New video to start."
            t.setTextColor(0xFF8A8F98.toInt())
            t.textSize = 14f
            projectsBox.addView(t)
            return
        }
        val df = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
        list.forEach { m ->
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(8), 0, dp(8))
            row.setOnClickListener { openEditor(m.id, false) }
            val th = ImageView(this)
            th.scaleType = ImageView.ScaleType.CENTER_CROP
            val tf = ProjectStore.thumb(this, m.id)
            if (tf.exists()) BitmapFactory.decodeFile(tf.absolutePath)?.let { th.setImageBitmap(it) } else th.setBackgroundColor(0xFFDDE3EA.toInt())
            th.clipToOutline = true
            val tbg = GradientDrawable()
            tbg.cornerRadius = dp(10).toFloat()
            tbg.setColor(0xFFDDE3EA.toInt())
            th.background = tbg
            row.addView(th, LinearLayout.LayoutParams(dp(92), dp(92)))
            val info = LinearLayout(this)
            info.orientation = LinearLayout.VERTICAL
            info.setPadding(dp(14), 0, 0, 0)
            val name = TextView(this)
            name.text = m.name
            name.textSize = 17f
            name.setTypeface(null, Typeface.BOLD)
            name.setTextColor(0xFF111111.toInt())
            name.maxLines = 1
            val date = TextView(this)
            date.text = df.format(Date(m.updated))
            date.textSize = 13f
            date.setTextColor(0xFF8A8F98.toInt())
            date.setPadding(0, dp(4), 0, 0)
            val meta = TextView(this)
            val sec = m.durMs / 1000
            meta.text = "✂ ${sizeText(m.size)}  |  %02d:%02d".format(sec / 60, sec % 60)
            meta.textSize = 13f
            meta.setTextColor(0xFF8A8F98.toInt())
            meta.setPadding(0, dp(4), 0, 0)
            info.addView(name); info.addView(date); info.addView(meta)
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val more = TextView(this)
            more.text = "⋮"
            more.textSize = 24f
            more.gravity = Gravity.CENTER
            more.setTextColor(0xFF666B75.toInt())
            more.setOnClickListener { projectMenu(m) }
            row.addView(more, LinearLayout.LayoutParams(dp(44), dp(60)))
            projectsBox.addView(row)
        }
    }

    private fun sizeText(b: Long): String = when {
        b >= 1_000_000_000L -> "%.1fGB".format(b / 1e9)
        b >= 1_000_000L -> "%dMB".format(b / 1_000_000L)
        else -> "%dKB".format(b / 1000L)
    }

    private fun lightSheet(title: String, vararg views: View): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.cornerRadii = floatArrayOf(dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), 0f, 0f, 0f, 0f)
        root.background = bg
        root.setPadding(dp(18), dp(16), dp(18), dp(18))
        val t = TextView(this)
        t.text = title
        t.textSize = 16f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(0xFF111111.toInt())
        t.setPadding(0, 0, 0, dp(8))
        root.addView(t)
        views.forEach { root.addView(it) }
        d.setContentView(root)
        d.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
        }
        d.show()
        return d
    }

    private fun row(text: String, danger: Boolean = false, click: () -> Unit): View {
        val t = TextView(this)
        t.text = text
        t.textSize = 16f
        t.setTextColor(if (danger) 0xFFE53935.toInt() else 0xFF111111.toInt())
        t.setPadding(0, dp(13), 0, dp(13))
        t.setOnClickListener { click() }
        return t
    }

    private fun projectMenu(m: ProjectStore.Meta) {
        var d: Dialog? = null
        d = lightSheet(m.name,
            row("Rename") { d?.dismiss(); renameDialog(m) },
            row("Duplicate") { d?.dismiss(); ProjectStore.duplicate(this, m.id); refreshProjects() },
            row("Delete", true) { d?.dismiss(); ProjectStore.delete(this, m.id); refreshProjects() })
    }

    private fun renameDialog(m: ProjectStore.Meta) {
        val e = EditText(this)
        e.setText(m.name)
        e.setSelection(e.text.length)
        e.setTextColor(0xFF111111.toInt())
        var d: Dialog? = null
        d = lightSheet("Rename project", e, row("Save") {
            val n = e.text.toString().trim()
            if (n.isNotEmpty()) ProjectStore.rename(this, m.id, n)
            d?.dismiss()
            refreshProjects()
        })
    }

    private fun aboutDialog() {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        lightSheet("Get Cut  v$v", row("Check for updates") { updater.check(false) }, EditorTheme.picker(this) {})
    }
}

package com.gatsmod.cut

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Launch screen: light-grey page, rounded white logo tile, "Get Cut" underneath. */
class SplashActivity : AppCompatActivity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bgColor = 0xFF4A4A4F.toInt()
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor

        val root = FrameLayout(this)
        root.setBackgroundColor(bgColor)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL

        val tile = ImageView(this)
        tile.setImageResource(R.drawable.ic_launcher_fg)
        tile.scaleType = ImageView.ScaleType.FIT_CENTER
        val g = GradientDrawable()
        g.setColor(Color.WHITE)
        g.cornerRadius = dp(30).toFloat()
        tile.background = g
        col.addView(tile, LinearLayout.LayoutParams(dp(130), dp(130)))

        val name = TextView(this)
        name.text = "Get Cut"
        name.textSize = 26f
        name.setTypeface(null, Typeface.BOLD)
        name.setTextColor(Color.WHITE)
        name.setPadding(0, dp(18), 0, 0)
        col.addView(name)

        root.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }, 1100)
    }
}

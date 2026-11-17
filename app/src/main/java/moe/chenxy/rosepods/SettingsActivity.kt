package moe.chenxy.rosepods

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import moe.chenxy.rosepods.core.AppSettings

/** 设置页 */
class SettingsActivity : Activity() {

    private companion object {
        const val BG = 0xFF0D0D0D.toInt()
        const val CARD = 0xFF1A1A1A.toInt()
        const val TEXT = 0xFFF5F5F5.toInt()
        const val TEXT_DIM = 0xFF8A8A8A.toInt()
        const val ACCENT = 0xFF3D7BFF.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.attach(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), statusBarHeight() + dp(12), dp(20), dp(32))
        }
        scroll.addView(root)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "‹"
            textSize = 26f
            setTextColor(TEXT)
            setPadding(0, 0, dp(12), 0)
            isClickable = true
            setOnClickListener { finish() }
        })
        top.addView(TextView(this).apply {
            text = "设置"
            textSize = 22f
            setTextColor(TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        root.addView(top, LinearLayout.LayoutParams(-2, -2))

        root.addView(sectionTitle("系统集成"))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 20f)
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        card.addView(switchRow(
            "音量面板降噪控制行",
            "在系统音量面板里显示 通透 / 降噪 / 关闭",
            AppSettings.volumeRow
        ) { AppSettings.volumeRow = it; AppSettings.broadcast(this) })
        card.addView(switchRow(
            "耳机通知",
            "连接后在通知栏显示左右耳与充电盒电量",
            AppSettings.notification
        ) { AppSettings.notification = it; AppSettings.broadcast(this) })
        card.addView(switchRow(
            "状态栏耳机图标",
            "连接时点亮状态栏的耳机图标",
            AppSettings.statusbar
        ) { AppSettings.statusbar = it; AppSettings.broadcast(this) })
        card.addView(switchRow(
            "设置页弹窗控制",
            "在系统设置里点耳机时弹出控制小窗",
            AppSettings.popup
        ) { AppSettings.popup = it; AppSettings.broadcast(this) })
        root.addView(card)

        root.addView(sectionTitle("关于"))
        val aboutCard = TextView(this).apply {
            text = "关于 RosePods  ›"
            textSize = 15f
            setTextColor(TEXT)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(CARD, 20f)
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, AboutActivity::class.java))
            }
        }
        root.addView(aboutCard, LinearLayout.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = "改完设置后可能需要重新打开音量面板才会生效"
            textSize = 11f
            setTextColor(TEXT_DIM)
            setPadding(dp(4), dp(16), 0, 0)
        })

        setContentView(scroll)
    }

    private fun switchRow(title: String, subtitle: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(TEXT)
        })
        col.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(TEXT_DIM)
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        row.addView(sw)
        return row
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(TEXT_DIM)
        setPadding(dp(4), dp(22), 0, dp(10))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }
}
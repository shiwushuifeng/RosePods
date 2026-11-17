package moe.chenxy.rosepods

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 关于页：作者、致谢、开源协议 */
class AboutActivity : Activity() {

    private companion object {
        const val BG = 0xFF0D0D0D.toInt()
        const val CARD = 0xFF1A1A1A.toInt()
        const val TEXT = 0xFFF5F5F5.toInt()
        const val TEXT_DIM = 0xFF8A8A8A.toInt()
        const val ACCENT = 0xFF3D7BFF.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), statusBarHeight() + dp(12), dp(20), dp(32))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "‹"
            textSize = 26f
            setTextColor(TEXT)
            isClickable = true
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "关于"
            textSize = 22f
            setTextColor(TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = "RosePods · 弱水时砂 CERAMICS-U 系统级控制"
            textSize = 12f
            setTextColor(TEXT_DIM)
            setPadding(0, 0, 0, dp(18))
        })

        // 作者
        root.addView(card(
            "作者" to "shiwushuifeng",
            "版本" to BuildConfig.VERSION_NAME,
            "项目" to "LSPosed 模块（libxposed API 102）",
        ))

        root.addView(sectionTitle("致谢"))
        root.addView(card(
            "OppoPods" to "github.com/1812z/OppoPods · github.com/Leaf-lsgtky/OppoPods\n" +
                "小米耳机原生设置页、蓝牙 HeadsetBinder 接管思路",
            "HyperVolumeAnc" to "github.com/zhhhyyyyyy/HyperVolumeAnc\n" +
                "音量面板布局注入思路",
            "LSPosed / Vector" to "模块框架与 libxposed API",
        ))

        root.addView(sectionTitle("开源协议"))
        root.addView(TextView(this).apply {
            text = "本项目以 GNU General Public License v3.0 发布。\n\n" +
                "因为移植了 OppoPods（GPL-3.0）的代码，按 GPL 的传染性要求，" +
                "本项目整体同样以 GPL-3.0 开源，完整源码公开。\n\n" +
                "HyperVolumeAnc 采用 Apache-2.0，与 GPL-3.0 兼容，其修改同样以 GPL-3.0 分发，" +
                "上游第三方声明一并保留。\n\n" +
                "分发本项目或其衍生作品时，必须同样开源并提供完整源码。\n\n" +
                "完整协议文本：gnu.org/licenses/gpl-3.0.html"
            textSize = 12f
            setTextColor(TEXT_DIM)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(CARD, 20f)
        })

        root.addView(TextView(this).apply {
            text = "本模块与小米、弱水时砂均无隶属关系，使用风险自负。"
            textSize = 11f
            setTextColor(TEXT_DIM)
            setPadding(dp(4), dp(18), 0, 0)
        })

        setContentView(scroll)
    }

    private fun card(vararg pairs: Pair<String, String>): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 20f)
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        pairs.forEach { (k, v) ->
            box.addView(TextView(this).apply {
                text = k
                textSize = 14f
                setTextColor(TEXT)
                setPadding(0, dp(10), 0, 0)
            })
            box.addView(TextView(this).apply {
                text = v
                textSize = 11f
                setTextColor(TEXT_DIM)
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(3), 0, dp(10))
            })
        }
        return box
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
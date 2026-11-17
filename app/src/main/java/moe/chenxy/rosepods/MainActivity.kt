package moe.chenxy.rosepods

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import moe.chenxy.rosepods.core.AppSettings
import moe.chenxy.rosepods.core.PodsContract
import moe.chenxy.rosepods.core.PodsState

/**
 * 主页：设备卡片 + 框架检测 + 设置/关于入口。
 *
 * 框架检测不依赖 root：模块每加载进一个进程就往这里广播一次，
 * 收集这段时间内有哪些进程是活的。
 */
class MainActivity : Activity() {

    private companion object {
        const val BG = 0xFF0D0D0D.toInt()
        const val CARD = 0xFF1A1A1A.toInt()
        const val CARD2 = 0xFF232323.toInt()
        const val TEXT = 0xFFF5F5F5.toInt()
        const val TEXT_DIM = 0xFF8A8A8A.toInt()
        const val ACCENT = 0xFF3D7BFF.toInt()
        const val GREEN = 0xFF4CD07D.toInt()
        const val RED = 0xFFFF6B6B.toInt()
        const val PROBE_MS = 900L
        const val COLLECT_MS = 1600L

        val REQUIRED = listOf(
            "com.android.bluetooth" to "蓝牙栈 · 建立 SPP 连接",
            "com.xiaomi.bluetooth" to "小米蓝牙扩展 · 供数据给系统",
            "com.android.settings" to "设置 · 弹窗控制页",
            "com.android.systemui" to "系统界面 · 音量面板控制行",
        )
    }

    private lateinit var deviceState: TextView
    private lateinit var frameworkBox: LinearLayout
    private lateinit var batteryText: TextView
    private lateinit var modeText: TextView
    private lateinit var dot: View

    private var remote = false
    private var state = PodsState()
    private val onlineProcs = java.util.Collections.synchronizedMap(HashMap<String, Int>())
    private val handler = Handler(Looper.getMainLooper())

    /** 各钩子进程上报：进程名 + 框架 API 版本 */
    private val moduleReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != PodsContract.ACTION_MODULE_ONLINE) return
            val proc = intent.getStringExtra(PodsContract.EXT_PROC) ?: return
            onlineProcs[proc] = intent.getIntExtra(PodsContract.EXT_API, 0)
            renderFramework()
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != PodsContract.ACTION_STATE) return
            state = PodsState(
                connected = intent.getBooleanExtra(PodsContract.EXT_CONNECTED, false),
                left = intent.getIntExtra(PodsContract.EXT_LEFT, -1),
                right = intent.getIntExtra(PodsContract.EXT_RIGHT, -1),
                case = intent.getIntExtra(PodsContract.EXT_CASE, -1),
                charging = intent.getBooleanExtra(PodsContract.EXT_CHARGING, false),
                mode = intent.getIntExtra(PodsContract.EXT_MODE, 0),
                address = intent.getStringExtra(PodsContract.EXT_ADDRESS).orEmpty(),
                name = intent.getStringExtra(PodsContract.EXT_NAME).orEmpty(),
                systemOwned = true,
            )
            remote = true
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.attach(this)
        buildUi()
        registerReceivers()
        // 广播设置顺便当作 ping，各进程收到后会回一句「在线」
        AppSettings.broadcast(this)
        handler.postDelayed({ renderFramework() }, COLLECT_MS)
        sendBroadcast(Intent(PodsContract.ACTION_STATE_REQ).setPackage(PodsContract.BT_PKG))
        handler.postDelayed({ render() }, COLLECT_MS)
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), statusBarHeight() + dp(16), dp(20), dp(32))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "RosePods"
            textSize = 26f
            setTextColor(TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "弱水时砂 CERAMICS-U 系统级控制"
            textSize = 12f
            setTextColor(TEXT_DIM)
            setPadding(0, dp(6), 0, dp(20))
        })

        // 设备卡片
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 22f)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, DeviceControlActivity::class.java)) }
        }
        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_buds_hero)
            setColorFilter(ACCENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val nameBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        nameBox.addView(TextView(this).apply {
            text = "ROSE Ceramics U"
            textSize = 16f
            setTextColor(TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        deviceState = TextView(this).apply {
            text = "检测中…"
            textSize = 12f
            setTextColor(TEXT_DIM)
            setPadding(0, dp(4), 0, 0)
        }
        nameBox.addView(deviceState)
        headRow.addView(nameBox, LinearLayout.LayoutParams(0, -2, 1f))
        dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(TEXT_DIM)
                setSize(dp(10), dp(10))
            }
        }
        headRow.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)))
        card.addView(headRow)

        batteryText = TextView(this).apply {
            text = "电量 —"
            textSize = 13f
            setTextColor(TEXT_DIM)
            setPadding(0, dp(14), 0, 0)
        }
        card.addView(batteryText)
        modeText = TextView(this).apply {
            text = "模式 —"
            textSize = 13f
            setTextColor(TEXT_DIM)
            setPadding(0, dp(5), 0, 0)
        }
        card.addView(modeText)
        card.addView(TextView(this).apply {
            text = "点按进入控制 ›"
            textSize = 13f
            setTextColor(ACCENT)
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        })
        root.addView(card)

        // 框架检测（显示框架 API 版本与各进程加载情况）
        root.addView(sectionTitle("框架状态"))
        frameworkBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 20f)
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        root.addView(frameworkBox)

        // 入口
        root.addView(sectionTitle("更多"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(entryButton("设置") {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }, LinearLayout.LayoutParams(0, -2, 1f).also { it.setMargins(0, 0, dp(8), 0) })
        row.addView(entryButton("关于") {
            startActivity(Intent(this@MainActivity, AboutActivity::class.java))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)

        root.addView(TextView(this).apply {
            text = "在系统设置里点耳机名称会弹出控制小窗"
            textSize = 11f
            setTextColor(TEXT_DIM)
            setPadding(dp(4), dp(20), 0, 0)
        })

        setContentView(scroll)
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(TEXT_DIM)
        setPadding(dp(4), dp(24), 0, dp(10))
    }

    private fun entryButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(TEXT)
        gravity = Gravity.CENTER
        setPadding(0, dp(16), 0, dp(16))
        background = rounded(CARD2, 18f)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun statusRow(ok: Boolean, title: String, detail: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
        }
        row.addView(TextView(this).apply {
            text = if (ok) "✓" else "✗"
            textSize = 14f
            setTextColor(if (ok) GREEN else RED)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(22), -2))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(TEXT)
        })
        col.addView(TextView(this).apply {
            text = detail
            textSize = 11f
            setTextColor(TEXT_DIM)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun renderFramework() {
        if (!::frameworkBox.isInitialized) return
        frameworkBox.removeAllViews()
        val procs = onlineProcs.entries.toList()
        val api = procs.maxOfOrNull { it.value } ?: 0
        frameworkBox.addView(statusRow(
            api > 0, "Xposed 框架",
            if (api > 0) "API $api · 已加载 ${procs.size} 个进程"
            else "未检测到框架响应（检查模块是否启用）"
        ))
        val required = listOf(
            "com.android.bluetooth" to "蓝牙栈",
            "com.xiaomi.bluetooth" to "小米蓝牙扩展",
            "com.android.settings" to "设置",
            "com.android.systemui" to "系统界面",
        )
        required.forEach { (proc, label) ->
            val loaded = onlineProcs.containsKey(proc)
            frameworkBox.addView(statusRow(
                loaded, label,
                if (loaded) "$proc · 已加载" else "$proc · 未加载"
            ))
        }
    }

    private fun render() {
        val connected = remote && state.connected
        deviceState.text = when {
            connected -> "已连接"
            remote -> "系统服务在线 · 耳机未连接"
            else -> "插件未生效"
        }
        (dot.background as? GradientDrawable)?.setColor(if (connected) GREEN else TEXT_DIM)
        val f = { v: Int -> if (v in 0..100) "$v%" else "—" }
        batteryText.text = if (connected) {
            "电量  左 ${f(state.left)}   右 ${f(state.right)}   仓 ${f(state.case)}"
        } else "电量 —"
        modeText.text = if (connected) "模式  ${PodsContract.modeName(state.mode)}" else "模式 —"
    }

    private fun registerReceivers() {
        val f1 = IntentFilter(PodsContract.ACTION_STATE)
        val f3 = IntentFilter(PodsContract.ACTION_MODULE_ONLINE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, f1, Context.RECEIVER_EXPORTED)
            registerReceiver(moduleReceiver, f3, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, f1)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(moduleReceiver, f3)
        }
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

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(stateReceiver) }
        runCatching { unregisterReceiver(moduleReceiver) }
    }
}
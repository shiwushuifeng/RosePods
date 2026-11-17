package moe.chenxy.rosepods

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import moe.chenxy.rosepods.core.PodsContract
import moe.chenxy.rosepods.core.PodsState
import moe.chenxy.rosepods.pods.CeramicsUPods

/**
 * 设备控制页 —— 系统设置里点耳机时弹出的悬浮小窗。
 *
 * 尺寸：高度 = 屏幕 1/3，宽度按比例，内容紧凑排列。
 * 视觉照小米那套：深色底 + 圆角 + 圆形图标按钮。
 */
class DeviceControlActivity : Activity() {

    private companion object {
        const val SYSTEM_PROBE_MS = 800L
        const val BG = 0xFF0D0D0D.toInt()
        const val CARD = 0xFF1B1B1B.toInt()
        const val CARD_SUB = 0xFF232323.toInt()
        const val SELECTED = 0xFF2C6BED.toInt()
        const val TEXT = 0xFFF7F7F7.toInt()
        const val TEXT_DIM = 0xFF8F8F8F.toInt()
        const val ICON_DIM = 0xFFB6B6B6.toInt()
    }

    private var pods: CeramicsUPods? = null
    private var remote = false
    private var remoteState = PodsState()
    private var localState = PodsState()
    private var chosenLevel = CeramicsUPods.LEVEL_MEDIUM

    private lateinit var heroIcon: ImageView
    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var batteryRow: LinearLayout
    private lateinit var modeGrid: LinearLayout
    private lateinit var levelRow: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val modeCells = mutableMapOf<Int, Triple<View, ImageView, TextView>>()
    private val levelViews = mutableMapOf<Int, TextView>()

    private data class ModeSpec(val mode: Int, val label: String, val icon: Int)

    private val allModes = listOf(
        ModeSpec(CeramicsUPods.NOISE_TRANSPARENT, "通透", R.drawable.ic_mode_transparency),
        ModeSpec(CeramicsUPods.NOISE_ANC, "降噪", R.drawable.ic_mode_anc),
        ModeSpec(CeramicsUPods.NOISE_NORMAL, "普通", R.drawable.ic_mode_off),
        ModeSpec(CeramicsUPods.NOISE_WIND, "风噪", R.drawable.ic_mode_wind),
        ModeSpec(CeramicsUPods.NOISE_ADAPTIVE, "自适应", R.drawable.ic_mode_adaptive),
        ModeSpec(CeramicsUPods.NOISE_DEEP, "深度", R.drawable.ic_mode_deep),
        ModeSpec(CeramicsUPods.NOISE_DYNAMIC, "动态", R.drawable.ic_mode_dynamic),
    )
    private val levels = listOf(
        CeramicsUPods.LEVEL_LIGHT to "轻度",
        CeramicsUPods.LEVEL_MEDIUM to "均衡",
        CeramicsUPods.LEVEL_DEEP to "深度",
    )

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != PodsContract.ACTION_STATE) return
            remoteState = PodsState(
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
            if (!remote) {
                remote = true
                pods?.close()
                pods = null
            }
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        registerStateReceiver()
        probeSystem()
    }

    // ------------------------------------------------------------------ 布局

    private fun buildUi() {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            // 点面板外的透明区域 → 关闭
            isClickable = true
            setOnClickListener { finish() }
        }
        val metrics = resources.displayMetrics
        val panelWidth = minOf(metrics.widthPixels - dp(48), (metrics.heightPixels / 3 * 0.96f).toInt())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(BG, 22f)
            layoutParams = FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            elevation = dp(18).toFloat()
            isClickable = true
        }
        frame.addView(root)

        // 顶栏
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), 0)
        }
        topBar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(TEXT)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(26), dp(26)))
        topBar.addView(TextView(this).apply {
            text = "设备控制"
            textSize = 13f
            setTextColor(TEXT_DIM)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(View(this), LinearLayout.LayoutParams(dp(26), dp(26)))
        root.addView(topBar)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(16))
        }
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        // 设备行：小图标 + 名称 + 状态
        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        heroIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_buds_hero)
            setColorFilter(0xFFEDEDED.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        heroRow.addView(heroIcon, LinearLayout.LayoutParams(dp(28), dp(28)))
        val nameBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        nameView = TextView(this).apply {
            text = "ROSE Ceramics U"
            textSize = 14f
            setTextColor(TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        statusView = TextView(this).apply {
            text = "正在探测…"
            textSize = 11f
            setTextColor(TEXT_DIM)
            setPadding(0, dp(2), 0, 0)
        }
        nameBox.addView(nameView)
        nameBox.addView(statusView)
        heroRow.addView(nameBox, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(heroRow)

        // 电量：三格紧凑
        batteryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(batteryRow, LinearLayout.LayoutParams(-1, -2).also { it.setMargins(0, dp(2), 0, 0) })

        // 模式：4 列圆形图标网格
        modeGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        allModes.chunked(4).forEachIndexed { index, chunk ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            // 第二排只有 3 个：两侧各加 0.5 权重占位，让三个居中且宽度和第一排一致
            if (index > 0 && chunk.size < 4) {
                row.addView(View(this), LinearLayout.LayoutParams(0, -2, 0.5f))
            }
            chunk.forEach { spec ->
                row.addView(modeCell(spec), LinearLayout.LayoutParams(0, -2, 1f))
            }
            if (index > 0 && chunk.size < 4) {
                row.addView(View(this), LinearLayout.LayoutParams(0, -2, 0.5f))
            }
            modeGrid.addView(row, LinearLayout.LayoutParams(-1, -2).also {
                if (index > 0) it.setMargins(0, dp(9), 0, 0)
            })
        }
        column.addView(modeGrid, LinearLayout.LayoutParams(-1, -2).also { it.setMargins(0, dp(4), 0, 0) })

        // 强度
        levelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        levels.forEach { (lv, label) ->
            val v = levelButton(lv, label)
            levelViews[lv] = v
            levelRow.addView(v, LinearLayout.LayoutParams(0, -2, 1f).also {
                it.setMargins(dp(3), 0, dp(3), 0)
            })
        }
        column.addView(levelRow)


        setContentView(frame)
        setFinishOnTouchOutside(true)
        overridePendingTransition(android.R.anim.fade_in, 0)
        render()
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 11f
        setTextColor(TEXT_DIM)
        letterSpacing = 0.06f
        setPadding(dp(4), dp(10), 0, dp(5))
    }

    /** 圆形图标 + 小标签 */
    private fun modeCell(spec: ModeSpec): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { applyMode(spec.mode) }
        }
        val icon = ImageView(this).apply {
            setImageResource(spec.icon)
            setColorFilter(ICON_DIM)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CARD)
            }
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }
        box.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)))
        val label = TextView(this).apply {
            text = spec.label
            textSize = 10f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        box.addView(label)
        modeCells[spec.mode] = Triple(box, icon, label)
        return box
    }

    private fun levelButton(level: Int, label: String): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(TEXT_DIM)
        gravity = Gravity.CENTER
        setPadding(0, dp(7), 0, dp(7))
        background = rounded(CARD_SUB, 14f)
        isClickable = true
        setOnClickListener { applyLevel(level) }
    }

    // ------------------------------------------------------------------ 渲染

    private fun render() {
        val s = if (remote) remoteState else localState

        statusView.text = when {
            remote && s.connected -> "已连接"
            remote -> "系统服务在线 · 耳机未连接"
            else -> "本地连接模式"
        }

        renderBattery(s)

        // 每个模式只亮自己
        allModes.forEach { spec ->
            val (_, icon, label) = modeCells[spec.mode] ?: return@forEach
            val on = spec.mode == s.mode
            (icon.background as? GradientDrawable)?.setColor(if (on) SELECTED else CARD)
            icon.setColorFilter(if (on) Color.WHITE else ICON_DIM)
            label.setTextColor(if (on) SELECTED else TEXT)
        }

        // 强度：仅降噪类模式可调
        val levelsEnabled = s.connected && supportsLevel(s.mode)
        levels.forEach { (lv, _) ->
            val tv = levelViews[lv] ?: return@forEach
            val on = lv == chosenLevel && levelsEnabled
            tv.isClickable = levelsEnabled
            tv.alpha = if (levelsEnabled) 1f else 0.32f
            tv.setTextColor(if (on) TEXT else TEXT_DIM)
            tv.background =
                if (on) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(CARD_SUB)
                        cornerRadius = dp(14).toFloat()
                        setStroke(dp(2), 0xFF3A3A3A.toInt())
                    }
                } else {
                    rounded(CARD_SUB, 14f)
                }
        }
    }

    private fun renderBattery(s: PodsState) {
        batteryRow.removeAllViews()
        val items = listOf("左" to s.left, "右" to s.right, "仓" to s.case)
        items.forEach { (label, value) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                background = rounded(CARD, 14f)
            }
            card.addView(TextView(this).apply {
                text = if (value in 0..100) "$value%" else "—"
                textSize = 14f
                setTextColor(TEXT)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = label
                textSize = 10f
                setTextColor(TEXT_DIM)
                setPadding(dp(4), dp(2), 0, 0)
            })
            batteryRow.addView(card, LinearLayout.LayoutParams(0, -2, 1f).also {
                it.setMargins(dp(3), 0, dp(3), 0)
            })
        }
    }

    // ------------------------------------------------------------------ 交互

    private fun applyMode(mode: Int) {
        if (remote) {
            remoteState = remoteState.copy(mode = mode)
            render()
            sendCommand(PodsContract.CMD_SET_MODE, mode)
        } else {
            localState = localState.copy(mode = mode)
            render()
            val client = pods ?: run { toast("请先连接耳机"); return }
            if (!client.setNoiseMode(mode)) toast("发送失败")
        }
    }

    private fun applyLevel(level: Int) {
        val s = if (remote) remoteState else localState
        if (!supportsLevel(s.mode)) {
            toast("该模式下不支持调节强度")
            return
        }
        chosenLevel = level
        render()
        if (remote) {
            sendCommand(PodsContract.CMD_SET_LEVEL, level)
        } else {
            val client = pods ?: run { toast("请先连接耳机"); return }
            if (!client.setNoiseLevel(level)) toast("发送失败")
        }
    }

    // ------------------------------------------------------------------ 系统通道

    private fun registerStateReceiver() {
        val filter = IntentFilter(PodsContract.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
    }

    private fun probeSystem() {
        requestSystemState()
        handler.postDelayed({ if (!remote) render() }, SYSTEM_PROBE_MS)
    }

    private fun requestSystemState() {
        sendBroadcast(Intent(PodsContract.ACTION_STATE_REQ).setPackage(PodsContract.BT_PKG))
    }

    private fun sendCommand(cmd: String, arg: Int) {
        sendBroadcast(
            Intent(PodsContract.ACTION_CMD)
                .setPackage(PodsContract.BT_PKG)
                .putExtra(PodsContract.EXT_CMD, cmd)
                .putExtra(PodsContract.EXT_ARG, arg)
        )
    }

    // ------------------------------------------------------------------ 工具

    private fun supportsLevel(mode: Int): Boolean = when (mode) {
        CeramicsUPods.NOISE_ANC,
        CeramicsUPods.NOISE_WIND,
        CeramicsUPods.NOISE_ADAPTIVE,
        CeramicsUPods.NOISE_DEEP,
        CeramicsUPods.NOISE_DYNAMIC -> true
        else -> false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(stateReceiver) }
        runCatching { pods?.close() }
    }
}
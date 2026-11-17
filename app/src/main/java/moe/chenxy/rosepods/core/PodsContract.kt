package moe.chenxy.rosepods.core

/**
 * App 进程与 com.android.bluetooth 进程之间的通信契约。
 *
 * 系统侧（LSPosed 注入 com.android.bluetooth）持有 SPP 连接，负责：
 *  - 连接耳机、组帧、解析电量与降噪模式
 *  - 通过 AdapterService.setBatteryLevel() 把电量交给系统蓝牙栈
 *    （系统随后广播 ACTION_BATTERY_LEVEL_CHANGED，设置页/快捷开关随之刷新）
 *  - 把状态广播给 App，并接收 App 的遥控命令
 *
 * App 侧（本应用）在系统服务可用时只做遥控，不做二次连接——
 * 耳机同一时间只接受一个 SPP 客户端，重复连接会一直阻塞。
 */
object PodsContract {

    const val APP_PKG = "moe.chenxy.rosepods"
    const val BT_PKG = "com.android.bluetooth"

    /** 系统侧 → App：状态同步（电量/模式/连接） */
    const val ACTION_STATE = "$APP_PKG.action.STATE"

    /** App → 系统侧：请求当前状态（同时用作探测系统侧是否在线） */
    const val ACTION_STATE_REQ = "$APP_PKG.action.STATE_REQ"

    /** App → 系统侧：控制命令 */
    const val ACTION_CMD = "$APP_PKG.action.CMD"

    const val EXT_SYSTEM = "system"
    const val EXT_CONNECTED = "connected"
    const val EXT_LEFT = "left"
    const val EXT_RIGHT = "right"
    const val EXT_CASE = "case"
    const val EXT_CHARGING = "charging"
    const val EXT_MODE = "mode"
    const val EXT_LEVEL = "level"
    const val EXT_ADDRESS = "address"
    const val EXT_NAME = "name"
    const val EXT_CMD = "cmd"
    const val EXT_ARG = "arg"
    const val EXT_REASON = "reason"

    const val CMD_PING = "ping"
    const val CMD_SET_MODE = "setMode"
    const val CMD_SET_LEVEL = "setLevel"
    const val CMD_CYCLE = "cycle"
    const val CMD_REQUEST_INFO = "requestInfo"
    const val CMD_DISCONNECT = "disconnect"
    const val CMD_SET_TARGET = "setTarget"

    /** 调试用：直接把 EXT_HEX 里的十六进制当 BODY 发出去（只用于协议探测） */
    const val CMD_RAW = "raw"
    const val EXT_HEX = "hex"

    /** 调试用：把 EXT_ARG 当电量值直接注入系统栈，验证 UI 是否跟着变 */
    const val CMD_TEST_BATTERY = "testBattery"

    /** 原生耳机通知里「断开连接」按钮的动作（沿用小米的 action 名） */
    const val ACTION_NOTIFICATION_DISCONNECT = "com.android.bluetooth.headset.notification"

    /** 原生耳机通知里的降噪切换按钮 */
    const val ACTION_NOTIFICATION_MODE = "moe.chenxy.rosepods.action.NOTIF_MODE"

    /** 原生耳机通知里直接选择某个降噪模式（EXT_ARG = mode） */
    const val ACTION_NOTIFICATION_SELECT_MODE = "moe.chenxy.rosepods.action.NOTIF_SELECT_MODE"

    // ---------------------------------------------------------------
    // HyperVolumeAnc 兼容层
    //
    // HyperVolumeAnc（github.com/zhhhyyyyyy/HyperVolumeAnc）在音量面板里
    // 给第三方耳机提供「降噪 / 通透 / 关闭」三态控件。它通过广播与
    // 「设备模块」对接（原设计对接 OppoPods），名字不是门槛：
    // 只要设备模块按地址主动上报状态，它就会接管显示与控制。
    //
    // 我们照这套契约说话，就能**零改动**复用它的音量面板 UI。
    // ---------------------------------------------------------------

    /** HyperVolumeAnc → 设备模块：用户点了某个模式 */
    const val ACTION_HVA_ANC_SELECT = "chen.action.oppopods.anc_select"

    /** HyperVolumeAnc → 设备模块：请求重新上报状态 */
    const val ACTION_HVA_REFRESH = "chen.action.oppopods.refresh_status"

    /** 设备模块 → HyperVolumeAnc（SystemUI 进程直接收，绕过小米蓝牙桥） */
    const val ACTION_HVA_STATE = "io.github.zhhhyyyyyy.hypervolumeanc.action.OPPO_STATE"

    /** HyperVolumeAnc 的播主进程（音量面板在 SystemUI） */
    const val SYSTEM_UI_PKG = "com.android.systemui"

    /** HyperVolumeAnc 的状态记录 120 秒过期，这里 30 秒重发一次 */
    const val HVA_HEARTBEAT_MS = 30_000L

    // ---------------------------------------------------------------
    // 跨进程状态通道：com.android.bluetooth（我们的 SPP 在这里）
    //   → com.xiaomi.bluetooth（MIUI 耳机服务在这里，要拿状态喂系统 UI）
    // ---------------------------------------------------------------

    const val XIAOMI_BT_PKG = "com.xiaomi.bluetooth"

    // ---------------- 设置同步（App → 各钩子进程） ----------------
    const val ACTION_SETTINGS = "$APP_PKG.action.SETTINGS"
    const val EXT_VOLUME_ROW = "volume_row"
    const val EXT_NOTIFICATION = "notification"
    const val EXT_STATUSBAR = "statusbar"
    const val EXT_POPUP = "popup"

    // ---------------- 模块自报（钩子进程 → App） ----------------
    const val ACTION_MODULE_ONLINE = "$APP_PKG.action.MODULE_ONLINE"
    const val EXT_PROC = "proc"
    const val EXT_API = "api"
    const val ACTION_STATUS_TO_XBT = "$APP_PKG.action.STATUS_TO_XBT"

    const val EXT_HVA_STATUS = "status"
    const val EXT_HVA_ADDRESS = "address"
    const val EXT_HVA_RECONNECT = "allow_rfcomm_reconnect"

    /** HyperVolumeAnc 的状态码 */
    const val HVA_STATUS_OFF = 1
    const val HVA_STATUS_ANC = 2
    const val HVA_STATUS_TRANSPARENCY = 3

    /** 我们的模式 → HyperVolumeAnc 状态码 */
    fun modeToHvaStatus(mode: Int): Int = when (mode) {
        CeramicsUPods_NOISE_NORMAL -> HVA_STATUS_OFF
        CeramicsUPods_NOISE_TRANSPARENT -> HVA_STATUS_TRANSPARENCY
        else -> HVA_STATUS_ANC   // 降噪/自适应/深度/动态 都归为降噪
    }

    /** HyperVolumeAnc 状态码 → 我们的模式 */
    fun hvaStatusToMode(status: Int): Int = when (status) {
        HVA_STATUS_OFF -> 2
        HVA_STATUS_TRANSPARENCY -> 3
        else -> 1
    }

    // 这里只用到几个模式常量，避免 PodsContract 依赖 pods 包
    private const val CeramicsUPods_NOISE_NORMAL = 0x02
    private const val CeramicsUPods_NOISE_TRANSPARENT = 0x03

    /** 耳机名称特征：ROSE Ceramics U / 弱水时砂 琉璃U */
    val TARGET_NAME_HINTS = listOf("Ceramics", "ROSE", "弱水")

    /** 文档记录的本机耳机地址，找不到名称特征时作为兜底 */
    const val DEFAULT_MAC = ""

    /** 快捷开关循环顺序：深度降噪 → 通透 → 普通 → 深度 */
    val CYCLE_MODES = intArrayOf(6, 3, 2)

    fun modeName(mode: Int): String = when (mode) {
        1 -> "降噪"
        2 -> "普通"
        3 -> "通透"
        4 -> "风噪"
        5 -> "自适应"
        6 -> "深度降噪"
        7 -> "动态"
        else -> "未知($mode)"
    }
}
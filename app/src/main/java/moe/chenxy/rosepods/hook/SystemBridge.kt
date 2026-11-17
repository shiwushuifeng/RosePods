package moe.chenxy.rosepods.hook

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import moe.chenxy.rosepods.core.PodsContract
import moe.chenxy.rosepods.core.PodsState
import moe.chenxy.rosepods.core.Reflect
import moe.chenxy.rosepods.pods.CeramicsUPods
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 运行在 `com.android.bluetooth` 进程内的系统侧控制器。
 *
 * 职责：
 *  - 监听耳机 ACL 连接/断开，在系统进程内维护唯一的 SPP 连接
 *  - 把电量交给系统蓝牙栈（见 [BatteryReporter]），让设置页显示电量
 *  - 与 App 通过广播互通：上报状态、接收遥控命令
 *
 * 为什么放在蓝牙进程：系统进程常驻，耳机连上即可工作，不依赖 App 打开；
 * 而且蓝牙栈权限齐全，能直接写系统电量。
 */
object SystemBridge {

    private const val TAG = "RosePods"

    /** 连接超时：SPP 被占用时 socket.connect() 会长时间阻塞 */
    private const val CONNECT_TIMEOUT_SEC = 35L

    /** 连接失败后的重试间隔 */
    private const val RETRY_DELAY_SEC = 60L

    private val started = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val hvaHeartbeat = AtomicBoolean(false)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "RosePods-spp").apply { isDaemon = true }
    }

    @Volatile private var context: Context? = null
    @Volatile private var adapterService: Any? = null
    @Volatile private var adapter: BluetoothAdapter? = null
    @Volatile private var targetDevice: BluetoothDevice? = null
    @Volatile private var currentPods: CeramicsUPods? = null
    @Volatile private var configuredAddress: String = ""

    private val stateLock = Any()
    private var state = PodsState(systemOwned = true)

    // ------------------------------------------------------------------ 生命周期

    fun attach(service: android.app.Service) {
        if (!started.compareAndSet(false, true)) return
        try {
            val ctx = service.applicationContext ?: service
            context = ctx
            adapterService = service
            adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            registerReceivers(ctx)
            HookLog.i("SystemBridge attached (adapter=${runCatching { adapter?.address }.getOrNull()})")
            val device = resolveTarget()
            if (device != null && isSystemConnected(device)) {
                targetDevice = device
                updateState { it.copy(address = device.address, name = safeName(device)) }
                connectAsync("attach")
            }
            broadcastState()
        } catch (t: Throwable) {
            HookLog.e("SystemBridge attach failed", t)
        }
    }

    private fun registerReceivers(ctx: Context) {
        val filter = IntentFilter().apply {
            addAction(PodsContract.ACTION_CMD)
            addAction(PodsContract.ACTION_STATE_REQ)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            // 通知里「断开连接」按钮
            addAction(PodsContract.ACTION_NOTIFICATION_DISCONNECT)
            // 通知上的降噪快捷按钮
            addAction(PodsContract.ACTION_NOTIFICATION_MODE)
            addAction(PodsContract.ACTION_NOTIFICATION_SELECT_MODE)
            // HyperVolumeAnc 音量面板兼容层
            addAction(PodsContract.ACTION_HVA_ANC_SELECT)
            addAction(PodsContract.ACTION_HVA_REFRESH)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
        HookLog.i("receivers registered")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val i = intent ?: return
            try {
                when (i.action) {
                    PodsContract.ACTION_CMD -> onCommand(i)
                    PodsContract.ACTION_STATE_REQ -> {
                        broadcastState()
                        // 恢复路径：ACL 还连着但 SPP 断了（例如点了通知里的"断开连接"，
                        // 或链路异常），收到状态请求时自动重连
                        val device = targetDevice
                        if (currentPods == null && device != null && isSystemConnected(device)) {
                            HookLog.i("state request while disconnected -> reconnect")
                            connectAsync("state request")
                        }
                    }
                    PodsContract.ACTION_NOTIFICATION_MODE -> {
                        HookLog.i("notification mode button -> cycle")
                        cycleMode()
                    }
                    PodsContract.ACTION_NOTIFICATION_SELECT_MODE -> {
                        val mode = i.getIntExtra(PodsContract.EXT_ARG, 0)
                        HookLog.i("notification select mode -> $mode")
                        sendMode(mode)
                        updateState { it.copy(mode = mode) }
                        broadcastState()
                        val s = snapshot()
                        MiuiHeadsetUi.post(
                            context, targetDevice, s.left, s.right, s.case,
                            s.leftCharging, s.rightCharging, s.caseCharging, mode
                        )
                    }
                    // ---- HyperVolumeAnc 音量面板：用户点了某个模式 ----
                    PodsContract.ACTION_HVA_ANC_SELECT -> {
                        val status = i.getIntExtra(PodsContract.EXT_HVA_STATUS, -1)
                        val mode = PodsContract.hvaStatusToMode(status)
                        HookLog.i("HVA anc_select status=$status -> mode=$mode")
                        sendMode(mode)
                        updateState { it.copy(mode = mode) }
                        broadcastState()
                        publishHvaState()
                    }
                    // ---- HyperVolumeAnc：请求重新上报状态 ----
                    PodsContract.ACTION_HVA_REFRESH -> {
                        HookLog.i("HVA refresh requested")
                        publishHvaState()
                    }
                    PodsContract.ACTION_NOTIFICATION_DISCONNECT -> {
                        HookLog.i("notification disconnect tapped")
                        val device = targetDevice
                        if (device != null) {
                            // 先停 SPP，再断开系统侧 ACL（走 AdapterService 的断开接口）
                            disconnect("notification")
                            val service = adapterService
                            if (service != null) {
                                runCatching {
                                    Reflect.callMethod(service, "disconnectAllEnabledProfiles", device)
                                }.onFailure { HookLog.e("disconnect profiles failed", it) }
                            }
                        }
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> onAclChanged(i, true)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> onAclChanged(i, false)
                }
            } catch (t: Throwable) {
                HookLog.e("receiver error (${i.action})", t)
            }
        }
    }

    // ------------------------------------------------------------------ 连接管理

    @Suppress("DEPRECATION")
    private fun onAclChanged(intent: Intent, connected: Boolean) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        if (!isTarget(device)) return
        targetDevice = device
        HookLog.i("acl ${if (connected) "connected" else "disconnected"}: ${device.address}")
        if (connected) {
            updateState { it.copy(address = device.address, name = safeName(device)) }
            connectAsync("acl")
        } else {
            disconnect("acl disconnected")
        }
    }

    /** 连续失败次数，成功连接后清零，避免耳机被其它客户端占用时无限重试 */
    private var retryCount = 0

    fun connectAsync(reason: String) {
        if (currentPods != null) return
        val device = targetDevice ?: resolveTarget() ?: run {
            HookLog.i("no target device found")
            return
        }
        targetDevice = device
        if (!connecting.compareAndSet(false, true)) return

        scheduler.execute {
            val pods = try {
                CeramicsUPods(::onBattery, ::onMode) { line -> HookLog.i("spp $line") }
            } catch (t: Throwable) {
                connecting.set(false)
                HookLog.e("create client failed", t)
                return@execute
            }
            pods.adapter = adapter
            pods.onBatteryDetail = ::onBatteryDetail
            pods.onClosed = {
                HookLog.i("spp link closed")
                if (currentPods === pods) {
                    currentPods = null
                    updateState { it.copy(connected = false) }
                    broadcastState()
                    scheduleRetry()
                }
            }
            currentPods = pods
            try {
                HookLog.i("connecting ${device.address} ($reason)")
                pods.connect(device)
                retryCount = 0
                updateState { it.copy(connected = true, address = device.address, name = safeName(device), systemOwned = true) }
                broadcastState()
                MiuiHeadsetUi.setHeadsetIcon(context, true)
                publishHvaState()
                if (hvaHeartbeat.compareAndSet(false, true)) {
                    scheduleHvaHeartbeat()
                }
                scheduleConnectWatchdog(pods)
            } catch (t: Throwable) {
                HookLog.e("connect failed", t)
                if (currentPods === pods) currentPods = null
                runCatching { pods.close() }
                updateState { it.copy(connected = false) }
                broadcastState()
                scheduleRetry()
            } finally {
                connecting.set(false)
            }
        }
    }

    private fun scheduleConnectWatchdog(pods: CeramicsUPods) {
        scheduler.schedule({
            try {
                if (currentPods === pods && !snapshot().connected) {
                    HookLog.i("connect watchdog: aborting stale attempt")
                    runCatching { pods.close() }
                }
            } catch (t: Throwable) {
                HookLog.e("watchdog error", t)
            }
        }, CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    private fun scheduleRetry() {
        if (retryCount >= 10) {
            HookLog.i("give up retrying after $retryCount attempts; wait for next ACL event")
            return
        }
        retryCount++
        scheduler.schedule({
            try {
                val device = targetDevice ?: return@schedule
                if (snapshot().connected) return@schedule
                if (isSystemConnected(device)) connectAsync("retry #$retryCount") else HookLog.i("retry skipped: device gone")
            } catch (t: Throwable) {
                HookLog.e("retry error", t)
            }
        }, RETRY_DELAY_SEC, TimeUnit.SECONDS)
    }

    fun disconnect(reason: String) {
        HookLog.i("disconnect: $reason")
        val pods = currentPods
        currentPods = null
        runCatching { pods?.close() }
        BatteryReporter.clear(adapterService, targetDevice)
        MiuiHeadsetUi.cancel(context, targetDevice)
        updateState { it.copy(connected = false) }
        broadcastState()
    }

    // ------------------------------------------------------------------ 数据回调

    private fun onBattery(left: Int, right: Int, case: Int, charging: Boolean) {
        updateState { it.copy(left = left, right = right, case = case, charging = charging, connected = true) }
        BatteryReporter.publish(adapterService, targetDevice, left, right, case, charging)
        broadcastState()
    }

    private fun onBatteryDetail(
        left: Int,
        right: Int,
        case: Int,
        leftCharging: Boolean,
        rightCharging: Boolean,
        caseCharging: Boolean,
    ) {
        HookLog.i("电池细节 L$left(${if (leftCharging) "充" else "-"}) R$right(${if (rightCharging) "充" else "-"}) C$case(${if (caseCharging) "充" else "-"})")
        updateState {
            it.copy(
                left = left,
                right = right,
                case = case,
                leftCharging = leftCharging,
                rightCharging = rightCharging,
                caseCharging = caseCharging,
                connected = true,
            )
        }
        if (SettingsBridge.notification) {
            MiuiHeadsetUi.post(
                context, targetDevice, left, right, case, leftCharging, rightCharging, caseCharging,
                snapshot().mode
            )
        }
        // 推给系统 UI（设置页原生耳机页 / 融合面板 / 音量面板）
        MiuiHeadsetBridge.pushStatus()
    }

    private fun onMode(mode: Int) {
        updateState { it.copy(mode = mode, connected = true) }
        broadcastState()
        publishHvaState()
        // 模式变了，通知上的按钮标题也要跟着变
        val s = snapshot()
        MiuiHeadsetUi.post(
            context, targetDevice, s.left, s.right, s.case,
            s.leftCharging, s.rightCharging, s.caseCharging, mode
        )
        MiuiHeadsetBridge.pushStatus()
    }

    // ------------------------------------------------------------------ 命令

    private fun onCommand(intent: Intent) {
        val cmd = intent.getStringExtra(PodsContract.EXT_CMD) ?: return
        val arg = intent.getIntExtra(PodsContract.EXT_ARG, 0)
        HookLog.i("command $cmd arg=$arg")
        when (cmd) {
            PodsContract.CMD_PING -> broadcastState()
            PodsContract.CMD_SET_MODE -> sendMode(arg)
            PodsContract.CMD_SET_LEVEL -> {
                val ok = currentPods?.setNoiseLevel(arg) ?: false
                if (!ok) HookLog.i("setNoiseLevel not ready")
            }
            PodsContract.CMD_REQUEST_INFO -> {
                if (currentPods?.requestInfo() != true) HookLog.i("requestInfo not ready")
            }
            PodsContract.CMD_CYCLE -> cycleMode()
            PodsContract.CMD_RAW -> {
                val hex = intent.getStringExtra(PodsContract.EXT_HEX) ?: ""
                val sent = currentPods?.sendRaw(hex) ?: false
                HookLog.i("raw($hex) sent=$sent")
            }
            PodsContract.CMD_TEST_BATTERY -> {
                val device = targetDevice
                if (device != null) {
                    HookLog.i("test battery inject: $arg")
                    BatteryReporter.publish(adapterService, device, arg, arg, arg, false)
                }
            }
            PodsContract.CMD_DISCONNECT -> disconnect("app request")
            PodsContract.CMD_SET_TARGET -> {
                val address = intent.getStringExtra(PodsContract.EXT_ADDRESS) ?: ""
                claimTarget(address)
            }
        }
    }

    private fun sendMode(mode: Int) {
        val pods = currentPods
        if (pods == null) {
            HookLog.i("setMode: not connected")
            connectAsync("command")
            return
        }
        if (!pods.setNoiseMode(mode)) {
            HookLog.i("setNoiseMode failed")
            return
        }
        // 设备不一定会回报模式（实测 1/2 只 ACK 不推送），这里乐观更新并广播，
        // 让 App / 通知 / 系统 UI 立刻有反馈；等设备回报再校正。
        updateState { it.copy(mode = mode) }
        broadcastState()
        val s = snapshot()
        MiuiHeadsetUi.post(
            context, targetDevice, s.left, s.right, s.case,
            s.leftCharging, s.rightCharging, s.caseCharging, mode
        )
        MiuiHeadsetBridge.pushStatus()
    }

    private fun cycleMode() {
        val current = snapshot().mode
        val modes = PodsContract.CYCLE_MODES
        val index = modes.indexOf(current)
        val next = modes[(index + 1) % modes.size]
        sendMode(next)
        // 设备不一定会立刻回报新模式；先乐观更新，避免连点重复发同一条
        updateState { it.copy(mode = next) }
        broadcastState()
        val s = snapshot()
        MiuiHeadsetUi.post(
            context, targetDevice, s.left, s.right, s.case,
            s.leftCharging, s.rightCharging, s.caseCharging, next
        )
    }

    private fun claimTarget(address: String) {
        if (address.isBlank()) return
        configuredAddress = address
        val a = adapter ?: return
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return
        targetDevice = device
        disconnect("target changed")
        if (isSystemConnected(device)) connectAsync("target changed") else HookLog.i("target set: $address (not connected)")
    }

    // ------------------------------------------------------------------ 设备发现

    private fun resolveTarget(): BluetoothDevice? {
        val a = adapter ?: return null
        val bonded = runCatching { a.bondedDevices }.getOrNull().orEmpty()
        if (configuredAddress.isNotBlank()) {
            bonded.firstOrNull { it.address.equals(configuredAddress, true) }?.let { return it }
        }
        bonded.firstOrNull { isTarget(it) }?.let { return it }
        return null
    }

    private fun isTarget(device: BluetoothDevice): Boolean {
        if (configuredAddress.isNotBlank() && device.address.equals(configuredAddress, true)) return true
        val name = safeName(device)
        return PodsContract.TARGET_NAME_HINTS.any { hint -> name.contains(hint, ignoreCase = true) }
    }

    private fun safeName(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull().orEmpty()

    private fun isSystemConnected(device: BluetoothDevice): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true // 低版本没有聚合查询，直接尝试连接
        val manager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return false
        manager.getConnectedDevices(BluetoothProfile.A2DP).any { it.address == device.address }
    }.getOrDefault(false)

    // ------------------------------------------------------------------ 状态与广播

    private fun snapshot(): PodsState = synchronized(stateLock) { state }

    /** 给 [MiuiHeadsetBridge] 用的只读快照 */
    fun snapshotPublic(): PodsState? = synchronized(stateLock) {
        if (state.connected) state else null
    }

    private fun updateState(block: (PodsState) -> PodsState) {
        synchronized(stateLock) { state = block(state) }
    }

    /**
     * 按 HyperVolumeAnc 的契约上报当前模式，让它的音量面板显示我们的耳机。
     *
     * 契约：广播 `chen.action.oppopods.pods_anc_select` 到 com.android.bluetooth，
     * 由 HyperVolumeAnc 在该进程里的 OppoPodsBridge 转发到 SystemUI。
     * 它注册时按 address 记录状态，所以设备名不必含 "oppo"。
     */
    private fun publishHvaState() {
        val ctx = context ?: return
        val device = targetDevice ?: return
        val s = snapshot()
        val intent = Intent(PodsContract.ACTION_HVA_STATE).apply {
            setPackage(PodsContract.SYSTEM_UI_PKG)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(PodsContract.EXT_HVA_STATUS, PodsContract.modeToHvaStatus(s.mode))
            putExtra(PodsContract.EXT_HVA_ADDRESS, device.address)
        }
        runCatching { ctx.sendBroadcast(intent) }
            .onSuccess { HookLog.i("HVA state -> SystemUI mode=${s.mode} addr=${device.address}") }
            .onFailure { HookLog.e("HVA publish failed", it) }
    }

    /** 周期性重发，避免 HyperVolumeAnc 的 120 秒状态过期 */
    private fun scheduleHvaHeartbeat() {
        scheduler.schedule({
            try {
                if (snapshot().connected) publishHvaState()
                if (snapshot().connected) scheduleHvaHeartbeat()
            } catch (t: Throwable) {
                HookLog.e("HVA heartbeat error", t)
            }
        }, PodsContract.HVA_HEARTBEAT_MS, TimeUnit.MILLISECONDS)
    }

    private fun broadcastState() {
        // 顺带把状态发给 com.xiaomi.bluetooth（MIUI 耳机服务）和 com.android.settings（原生耳机页）
        val s2 = snapshot()
        for (pkg in listOf(PodsContract.XIAOMI_BT_PKG, "com.android.settings", PodsContract.SYSTEM_UI_PKG)) {
            val xbt = Intent(PodsContract.ACTION_STATUS_TO_XBT).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(PodsContract.EXT_CONNECTED, s2.connected)
                putExtra(PodsContract.EXT_LEFT, s2.left)
                putExtra(PodsContract.EXT_RIGHT, s2.right)
                putExtra(PodsContract.EXT_CASE, s2.case)
                putExtra(PodsContract.EXT_CHARGING, s2.charging)
                putExtra(PodsContract.EXT_MODE, s2.mode)
                putExtra(PodsContract.EXT_ADDRESS, s2.address)
                putExtra(PodsContract.EXT_NAME, s2.name)
            }
            runCatching { context?.sendBroadcast(xbt) }
        }
        val ctx = context ?: return
        val s = snapshot()
        val intent = Intent(PodsContract.ACTION_STATE).apply {
            setPackage(PodsContract.APP_PKG)
            putExtra(PodsContract.EXT_SYSTEM, true)
            putExtra(PodsContract.EXT_CONNECTED, s.connected)
            putExtra(PodsContract.EXT_LEFT, s.left)
            putExtra(PodsContract.EXT_RIGHT, s.right)
            putExtra(PodsContract.EXT_CASE, s.case)
            putExtra(PodsContract.EXT_CHARGING, s.charging)
            putExtra(PodsContract.EXT_MODE, s.mode)
            putExtra(PodsContract.EXT_LEVEL, s.level)
            putExtra(PodsContract.EXT_ADDRESS, s.address)
            putExtra(PodsContract.EXT_NAME, s.name)
        }
        runCatching { ctx.sendBroadcast(intent) }
            .onFailure { HookLog.e("broadcast state failed", it) }
    }
}
package moe.chenxy.rosepods.hook

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import moe.chenxy.rosepods.core.PodsContract
import moe.chenxy.rosepods.core.PodsState
import moe.chenxy.rosepods.core.Reflect

/**
 * 让系统把琉璃U 当作「支持降噪的小米 TWS 耳机」。
 *
 * 移植自 OppoPods 2.2.0 的 `BluetoothUpstreamHeadsetHook`
 * （github.com/1812z/OppoPods，GPL-3.0）。做法是在 **com.xiaomi.bluetooth**
 * 进程里 hook `BluetoothHeadsetService$HeadsetBinder`：
 *
 *  - `checkSupport` / `getDeviceInfo` → 伪造能力串 `01010607,000000000000000010000000`
 *  - `isMiTWS` / `checkIsMiTWS`       → true（系统据此判定"这是小米 TWS"）
 *  - `getRingFindState`              → false
 *  - `setCommonCommand`              → 接管系统下发命令
 *  - `register(callback)`            → 记住 `IMiuiHeadsetCallback`，
 *                                      再用 `refreshStatus(address, payload)` 推电量/ANC
 *
 * 我们的 SPP 在 com.android.bluetooth 进程，所以状态通过广播
 * （[PodsContract.ACTION_STATUS_TO_XBT]）送过来缓存。
 */
object MiuiHeadsetBridge {

    /** 伪造的小米设备型号 ID（沿用 OppoPods 默认值） */
    private const val FAKE_DEVICE_ID = "01010607"
    private val FAKE_SUPPORT = "$FAKE_DEVICE_ID,000000000000000010000000"

    private val hookedBinderClasses = java.util.Collections.synchronizedSet(HashSet<String>())
    private val callbacks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    @Volatile private var installed = false
    @Volatile private var receiverRegistered = false
    @Volatile private var state: PodsState = PodsState()

    // ------------------------------------------------------------------ 安装

    fun install(module: XposedModule, loader: ClassLoader) {
        if (installed) return
        installed = true
        try {
            val serviceClass = Class.forName(
                "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService", false, loader
            )

            // onCreate → 拿 Context 注册状态接收器
            val onCreate = serviceClass.declaredMethods.firstOrNull {
                it.name == "onCreate" && it.parameterCount == 0
            }
            if (onCreate != null) {
                module.hook(onCreate)
                    .setId("rosepods.v1:miui-headset-oncreate")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(XposedInterface.Hooker { chain ->
                        val result = chain.proceed()
                        try {
                            (chain.thisObject as? Context)?.let { registerReceiver(it) }
                        } catch (t: Throwable) {
                            HookLog.e("registerReceiver failed", t)
                        }
                        result
                    })
            }

            // onBind → 拿到 HeadsetBinder 实例，按它的实际类挂方法
            val onBind = serviceClass.declaredMethods.firstOrNull {
                it.name == "onBind" && it.parameterCount == 1
            }
            if (onBind != null) {
                module.hook(onBind)
                    .setId("rosepods.v1:miui-headset-onbind")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(XposedInterface.Hooker { chain ->
                        val result = chain.proceed()
                        try {
                            (chain.thisObject as? Context)?.let { registerReceiver(it) }
                            if (result != null) installBinderHooks(module, result.javaClass)
                        } catch (t: Throwable) {
                            HookLog.e("installBinderHooks failed", t)
                        }
                        result
                    })
                HookLog.i("MiuiHeadsetBridge: hooks installed")
            }
        } catch (t: Throwable) {
            HookLog.e("MiuiHeadsetBridge install failed", t)
        }
    }

    private fun registerReceiver(context: Context) {
        runCatching { SettingsBridge.install(context, "com.xiaomi.bluetooth") }
        if (receiverRegistered) return
        receiverRegistered = true
        val filter = IntentFilter(PodsContract.ACTION_STATUS_TO_XBT)
        val rx = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val i = intent ?: return
                state = PodsState(
                    connected = i.getBooleanExtra(PodsContract.EXT_CONNECTED, false),
                    left = i.getIntExtra(PodsContract.EXT_LEFT, -1),
                    right = i.getIntExtra(PodsContract.EXT_RIGHT, -1),
                    case = i.getIntExtra(PodsContract.EXT_CASE, -1),
                    charging = i.getBooleanExtra(PodsContract.EXT_CHARGING, false),
                    mode = i.getIntExtra(PodsContract.EXT_MODE, 0),
                    address = i.getStringExtra(PodsContract.EXT_ADDRESS).orEmpty(),
                    name = i.getStringExtra(PodsContract.EXT_NAME).orEmpty(),
                )
                pushStatus()
            }
        }
        runCatching { context.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED) }
            .onSuccess { HookLog.i("status receiver registered") }
            .onFailure { HookLog.e("status receiver failed", it) }
    }

    private fun installBinderHooks(module: XposedModule, binderClass: Class<*>) {
        if (!hookedBinderClasses.add(binderClass.name)) return
        HookLog.i("binder class = ${binderClass.name}")

        hookFirst(module, binderClass, listOf("checkSupport"), "checkSupport") { chain ->
            val device = chain.args.firstOrNull() as? BluetoothDevice
            if (device != null && isTarget(device)) FAKE_SUPPORT else chain.proceed()
        }

        for (name in listOf("isMiTWS", "checkIsMiTWS")) {
            hookFirst(module, binderClass, listOf(name), name) { chain ->
                val device = chain.args.firstOrNull() as? BluetoothDevice
                if (device != null && isTarget(device)) true else chain.proceed()
            }
        }

        hookFirst(module, binderClass, listOf("getDeviceInfo"), "getDeviceInfo") { chain ->
            val address = chain.args.firstOrNull() as? String
            if (address != null && isTargetAddress(address)) FAKE_SUPPORT else chain.proceed()
        }

        hookFirst(module, binderClass, listOf("getRingFindState"), "getRingFindState") { chain ->
            val device = chain.args.firstOrNull() as? BluetoothDevice
            if (device != null && isTarget(device)) false else chain.proceed()
        }

        hookFirst(module, binderClass, listOf("setCommonCommand"), "setCommonCommand") { chain ->
            val device = chain.args.filterIsInstance<BluetoothDevice>().firstOrNull()
            if (device != null && isTarget(device)) {
                val command = (chain.args.getOrNull(0) as? Int) ?: -1
                HookLog.i("setCommonCommand cmd=$command device=${device.address}")
                pushStatus()
                if (command == 123) "4" else "1"
            } else {
                chain.proceed()
            }
        }

        for (name in listOf("register", "registerCallbackDevice")) {
            hookFirst(module, binderClass, listOf(name), name) { chain ->
                val callback = chain.args.firstOrNull()
                val device = chain.args.filterIsInstance<BluetoothDevice>().firstOrNull()
                if (callback != null && (device == null || isTarget(device))) {
                    callbacks[device?.address ?: "*"] = callback
                    HookLog.i("callback registered key=${device?.address ?: "*"}")
                    chain.proceed()
                    pushStatus()
                } else {
                    chain.proceed()
                }
            }
        }

        hookFirst(module, binderClass, listOf("unregister"), "unregister") { chain ->
            val device = chain.args.filterIsInstance<BluetoothDevice>().firstOrNull()
            if (device != null) callbacks.remove(device.address) else callbacks.clear()
            chain.proceed()
        }
    }

    /** 找到第一个名字匹配、参数里含 BluetoothDevice/String 的方法并 hook */
    private fun hookFirst(
        module: XposedModule,
        clazz: Class<*>,
        names: List<String>,
        id: String,
        body: (XposedInterface.Chain) -> Any?,
    ) {
        val method = clazz.declaredMethods.firstOrNull { m ->
            m.name in names && m.parameterCount in 1..3 &&
                m.parameterTypes.any {
                    it == BluetoothDevice::class.java || it == String::class.java
                }
        } ?: run {
            HookLog.e("binder method not found: $names in ${clazz.name}")
            return
        }
        runCatching {
            module.hook(method)
                .setId("rosepods.v1:$id")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(XposedInterface.Hooker { chain -> body(chain) })
            HookLog.i("hooked ${method.name}@${clazz.simpleName}")
        }.onFailure { HookLog.e("hook ${method.name} failed", it) }
    }

    // ------------------------------------------------------------------ 推送

    /** 把当前状态推给系统 UI */
    fun pushStatus() {
        val s = state
        if (!s.connected || s.address.isBlank()) return
        if (callbacks.isEmpty()) {
            HookLog.i("pushStatus skipped: no callback yet")
            return
        }
        val payload = buildPayload(s)
        for ((key, cb) in callbacks.entries.toList()) {
            if (key != "*" && !key.equals(s.address, ignoreCase = true)) continue
            runCatching {
                Reflect.callMethod(cb, "refreshStatus", s.address, payload)
                HookLog.i("refreshStatus -> ${s.address} payload=$payload")
            }.onFailure {
                HookLog.e("refreshStatus failed, dropping $key", it)
                callbacks.remove(key)
            }
        }
    }

    /** 16 字段 payload，格式与 MIUI 一致 */
    private fun buildPayload(s: PodsState): String {
        fun battery(value: Int, charging: Boolean): String {
            if (value !in 0..100) return "255"
            return (if (charging) value or 128 else value).toString()
        }
        val values = MutableList(16) { "" }
        values[0] = battery(s.left, s.leftCharging)
        values[1] = battery(s.right, s.rightCharging)
        values[2] = battery(s.case, s.caseCharging)
        values[7] = ancLevel(s.mode)
        values[8] = "true"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    /** 我们的模式 → MIUI 四位 ANC 码 */
    private fun ancLevel(mode: Int): String = when (mode) {
        6 -> "0102"        // 深度降噪
        1, 4, 5, 7 -> "0100" // 降噪/风噪/自适应/动态 → 中度降噪
        3 -> "0200"        // 通透
        else -> "0000"     // 普通/关闭
    }

    // ------------------------------------------------------------------ 匹配

    private fun isTarget(device: BluetoothDevice): Boolean = isTargetAddress(device.address)

    private fun isTargetAddress(address: String): Boolean {
        val cached = state.address
        return cached.isNotBlank() && address.equals(cached, ignoreCase = true)
    }
}
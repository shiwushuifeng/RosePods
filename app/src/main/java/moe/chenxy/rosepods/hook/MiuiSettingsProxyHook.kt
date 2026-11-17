package moe.chenxy.rosepods.hook

import android.bluetooth.BluetoothDevice
import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import moe.chenxy.rosepods.core.PodsContract

/**
 * 让「设置 → 小米耳机原生页」里的控件真正生效。
 *
 * 移植自 OppoPods 2.2.0 的 `SettingsHeadsetHook#hookServiceProxy`。
 *
 * 页面在 **com.android.settings** 进程，控件点击会走 AIDL 代理
 * `com.android.bluetooth.ble.app.IMiuiHeadsetService$Stub$Proxy`：
 *  - `changeAncMode(int miMode, BluetoothDevice)`   ← 降噪/通透/关闭按钮
 *  - `changeAncLevel(String level, BluetoothDevice)` ← 强度滑块
 *
 * 我们把这两个截下来，翻成我们的模式，用广播发给蓝牙进程执行
 * （SPP 连接在 com.android.bluetooth 里）。
 *
 * MIUI 模式码：1=降噪 2=通透 3=关闭（与 OppoPods 的映射一致）
 * MIUI 强度码：0101=轻 0100=中 0102=深 0103=智能 02xx=通透增强
 */
object MiuiSettingsProxyHook {

    private val installedPackages = java.util.Collections.synchronizedSet(HashSet<String>())

    fun install(module: XposedModule, loader: ClassLoader, packageName: String) {
        if (!installedPackages.add(packageName)) return
        try {
            // 先拿 Context（发广播要用）
            runCatching {
                val attach = android.app.Application::class.java.getDeclaredMethod("attach", Context::class.java)
                module.hook(attach)
                    .setId("rosepods.v1:settings-app-attach")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(XposedInterface.Hooker { chain ->
                        val result = chain.proceed()
                        (chain.args.firstOrNull() as? Context)?.let {
                            SettingsContextHolder.context = it
                            registerStatusReceiver(it)
                            runCatching { SettingsBridge.install(it, "com.android.settings") }
                        }
                        result
                    })
            }.onFailure { HookLog.e("Application.attach hook failed", it) }

            val proxyClass = Class.forName(
                "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub\$Proxy", false, loader
            )
            hookVoidCommand(module, proxyClass, "changeAncMode") { args ->
                val miMode = args.firstOrNull { it is Int } as? Int ?: return@hookVoidCommand
                val mode = miuiModeToPodsMode(miMode)
                HookLog.i("settings changeAncMode mi=$miMode -> pods=$mode")
                sendCommand(PodsContract.CMD_SET_MODE, mode)
            }
            hookVoidCommand(module, proxyClass, "changeAncLevel") { args ->
                val level = args.firstOrNull { it is String } as? String ?: return@hookVoidCommand
                val podsLevel = miuiLevelToPodsLevel(level)
                HookLog.i("settings changeAncLevel $level -> pods=$podsLevel")
                if (podsLevel > 0) sendCommand(PodsContract.CMD_SET_LEVEL, podsLevel)
            }
            HookLog.i("MiuiSettingsProxyHook installed in $packageName")
        } catch (t: Throwable) {
            HookLog.e("MiuiSettingsProxyHook install failed", t)
        }
    }

    private fun hookVoidCommand(
        module: XposedModule,
        clazz: Class<*>,
        name: String,
        body: (Array<Any?>) -> Unit,
    ) {
        val method = clazz.declaredMethods.firstOrNull {
            it.name == name && it.parameterCount == 2
        } ?: run {
            HookLog.e("proxy method not found: $name")
            return
        }
        runCatching {
            module.hook(method)
                .setId("rosepods.v1:settings-proxy-$name")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(XposedInterface.Hooker { chain ->
                    try {
                        body(chain.args.toTypedArray())
                    } catch (t: Throwable) {
                        HookLog.e("proxy $name failed", t)
                    }
                    // 不再往下走（Binder 调用会失败），直接返回
                    null
                })
            HookLog.i("hooked proxy $name")
        }.onFailure { HookLog.e("hook proxy $name failed", it) }
    }

    private var receiverRegistered = false

    private fun registerStatusReceiver(context: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val filter = android.content.IntentFilter(PodsContract.ACTION_STATUS_TO_XBT)
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                val i = intent ?: return
                MiuiHeadsetFragmentHook.onStatus(
                    MiuiHeadsetFragmentHook.PodsStateSnapshot(
                        connected = i.getBooleanExtra(PodsContract.EXT_CONNECTED, false),
                        left = i.getIntExtra(PodsContract.EXT_LEFT, -1),
                        right = i.getIntExtra(PodsContract.EXT_RIGHT, -1),
                        case = i.getIntExtra(PodsContract.EXT_CASE, -1),
                        mode = i.getIntExtra(PodsContract.EXT_MODE, 0),
                        address = i.getStringExtra(PodsContract.EXT_ADDRESS).orEmpty(),
                    )
                )
            }
        }
        runCatching { context.registerReceiver(rx, filter, Context.RECEIVER_EXPORTED) }
            .onSuccess { HookLog.i("settings status receiver registered") }
            .onFailure { HookLog.e("settings receiver failed", it) }
    }

    /** MIUI 模式 → 我们的模式 */
    private fun miuiModeToPodsMode(miui: Int): Int = when (miui) {
        1 -> 1   // 降噪
        2 -> 3   // 通透
        else -> 2 // 关闭 → 普通
    }

    /** MIUI 强度码 → 我们的 1..5 */
    private fun miuiLevelToPodsLevel(level: String): Int = when {
        level.startsWith("0101") -> 1 // 轻
        level.startsWith("0100") -> 3 // 中
        level.startsWith("0102") -> 5 // 深
        else -> 0
    }

    private fun sendCommand(cmd: String, arg: Int) {
        val ctx = SettingsContextHolder.context ?: run {
            HookLog.i("no settings context yet")
            return
        }
        val intent = android.content.Intent(PodsContract.ACTION_CMD).apply {
            setPackage(PodsContract.BT_PKG)
            addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(PodsContract.EXT_CMD, cmd)
            putExtra(PodsContract.EXT_ARG, arg)
        }
        runCatching { ctx.sendBroadcast(intent) }
            .onFailure { HookLog.e("sendCommand failed", it) }
    }

    /** 设置进程里拿一个 Context 用来发广播 */
    object SettingsContextHolder {
        @Volatile var context: Context? = null
    }
}
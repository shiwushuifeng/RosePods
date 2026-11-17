package moe.chenxy.rosepods.hook

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import moe.chenxy.rosepods.core.PodsContract
import moe.chenxy.rosepods.core.Reflect

/**
 * 打通「设置 → 小米耳机原生页」的控件。
 *
 * 移植自 OppoPods 2.2.0 的 `SettingsHeadsetHook`（fragment 部分）。
 *
 * 页面类是 `com.android.settings.bluetooth.MiuiHeadsetFragment`，关键点：
 *  - `onCreateView` / `onServiceConnected`：拿到 fragment 实例
 *  - `updateAtUiInfo(String)`：注入 "模式|档位列表|电量|00"
 *  - `updateAncUi(String, boolean)`：注入当前强度
 *  - `updateAncMode(int, boolean)`：**用户点降噪/通透/关闭时触发** → 我们要转发
 *  - `updateAncLevel(String, boolean)`：**用户拖强度时触发** → 转发
 *  - `onBatteryChanged(int,int,int)`：电量视图
 *
 * 数据来自蓝牙进程的广播（[PodsContract.ACTION_STATUS_TO_XBT]）。
 */
object MiuiHeadsetFragmentHook {

    private const val FRAGMENT = "com.android.settings.bluetooth.MiuiHeadsetFragment"
    private val installed = java.util.Collections.synchronizedSet(HashSet<String>())
    private val fragments = java.util.Collections.synchronizedSet(HashSet<Any>())

    @Volatile private var latest: PodsStateSnapshot = PodsStateSnapshot()

    fun install(module: XposedModule, loader: ClassLoader, packageName: String) {
        if (!installed.add(packageName)) return
        try {
            val fragmentClass = Class.forName(FRAGMENT, false, loader)

            // 拿 fragment 实例并注入状态
            for (name in listOf("onCreateView", "onServiceConnected")) {
                val m = fragmentClass.declaredMethods.firstOrNull { it.name == name } ?: continue
                module.hook(m)
                    .setId("rosepods.v1:frag-$name")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(XposedInterface.Hooker { chain ->
                        val result = chain.proceed()
                        try {
                            chain.thisObject?.let { frag ->
                                fragments.add(frag)
                                inject(frag)
                            }
                        } catch (t: Throwable) {
                            HookLog.e("fragment inject failed", t)
                        }
                        result
                    })
                HookLog.i("hooked $FRAGMENT.$name")
            }

            // 用户点模式
            val ancMode = fragmentClass.declaredMethods.firstOrNull {
                it.name == "updateAncMode" && it.parameterCount == 2
            }
            if (ancMode != null) {
                module.hook(ancMode)
                    .setId("rosepods.v1:frag-ancmode")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(XposedInterface.Hooker { chain ->
                        val miMode = chain.args.firstOrNull() as? Int ?: 0
                        val mode = when (miMode) {
                            1 -> 1   // 降噪
                            2 -> 3   // 通透
                            else -> 2 // 关闭
                        }
                        HookLog.i("fragment updateAncMode mi=$miMode -> pods=$mode")
                        send(PodsContract.CMD_SET_MODE, mode)
                        latest = latest.copy(mode = mode)
                        chain.proceed()
                    })
                HookLog.i("hooked updateAncMode")
            }

            // 用户拖强度
            val ancLevel = fragmentClass.declaredMethods.firstOrNull {
                it.name == "updateAncLevel" && it.parameterCount == 2
            }
            if (ancLevel != null) {
                module.hook(ancLevel)
                    .setId("rosepods.v1:frag-anclevel")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(XposedInterface.Hooker { chain ->
                        val level = chain.args.firstOrNull() as? String ?: ""
                        val pods = when {
                            level.startsWith("0101") -> 1
                            level.startsWith("0100") -> 3
                            level.startsWith("0102") -> 5
                            level.startsWith("0103") -> 3
                            else -> 0
                        }
                        HookLog.i("fragment updateAncLevel $level -> pods=$pods")
                        if (pods > 0) send(PodsContract.CMD_SET_LEVEL, pods)
                        chain.proceed()
                    })
                HookLog.i("hooked updateAncLevel")
            }

            // 吞掉「当前耳机无法正常设置，您的耳机可能已连接其他手机」
            // 系统连不上耳机的私有通道(MMA)时会弹这个，我们用自己的数据顶上
            val mmaFailed = fragmentClass.declaredMethods.firstOrNull {
                it.name == "handleConnectMmaFailed" && it.parameterCount == 1
            }
            if (mmaFailed != null) {
                module.hook(mmaFailed)
                    .setId("rosepods.v1:frag-mmafail")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(XposedInterface.Hooker { chain ->
                        HookLog.i("swallowed MMA failure: ${chain.args.firstOrNull()}")
                        try {
                            chain.thisObject?.let { inject(it) }
                        } catch (t: Throwable) {
                            HookLog.e("re-inject after MMA failure failed", t)
                        }
                        null
                    })
                HookLog.i("hooked handleConnectMmaFailed")
            }

            HookLog.i("MiuiHeadsetFragmentHook installed in $packageName")
        } catch (t: Throwable) {
            HookLog.e("MiuiHeadsetFragmentHook install failed", t)
        }
    }

    /** 状态广播进来时刷新页面 */
    fun onStatus(s: PodsStateSnapshot) {
        latest = s
        fragments.toList().forEach { inject(it) }
    }

    /** 给其它 hook 用的当前地址 */
    fun cachedAddress(): String = latest.address

    private fun inject(fragment: Any) {
        val s = latest
        if (!s.connected) return
        runCatching {
            // 让页面认为支持 ANC
            setFieldIfPresent(fragment, "mSupportAnc", java.lang.Boolean.TRUE)

            // 风噪(4) 归到降噪一档
            val ancMode = when (s.mode) {
                1, 4, 5, 6, 7 -> "1"
                3 -> "2"
                else -> "0"
            }
            fun battery(v: Int, chg: Boolean): String =
                if (v !in 0..100) "255" else (if (chg) v or 128 else v).toString()
            val batteryStr =
                "${battery(s.left, s.leftCharging)},${battery(s.right, s.rightCharging)},${battery(s.case, s.caseCharging)}"

            // 一条 payload 同时带电量 + 模式 + 支持的档位
            val payload = "$ancMode|0100;0101;0102;0103;0200;0201|$batteryStr|00"
            Reflect.callMethod(fragment, "updateAtUiInfo", payload)

            val levelCode = when (s.mode) {
                6 -> "0102"
                1, 4, 5, 7 -> "0100"
                3 -> "0200"
                else -> "0000"
            }
            Reflect.callMethod(fragment, "updateAncUi", levelCode, false)
            HookLog.i("fragment injected payload=$payload level=$levelCode")
        }.onFailure { HookLog.e("inject failed", it) }
    }

    /** 字段不存在时静默跳过 */
    private fun setFieldIfPresent(target: Any, name: String, value: Any) {
        runCatching {
            var cls: Class<*>? = target.javaClass
            while (cls != null) {
                val f = cls.declaredFields.firstOrNull { it.name == name }
                if (f != null) {
                    f.isAccessible = true
                    f.set(target, value)
                    return
                }
                cls = cls.superclass
            }
        }
    }

    private fun send(cmd: String, arg: Int) {
        val ctx = MiuiSettingsProxyHook.SettingsContextHolder.context ?: return
        val intent = android.content.Intent(PodsContract.ACTION_CMD).apply {
            setPackage(PodsContract.BT_PKG)
            addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(PodsContract.EXT_CMD, cmd)
            putExtra(PodsContract.EXT_ARG, arg)
        }
        runCatching { ctx.sendBroadcast(intent) }
    }

    data class PodsStateSnapshot(
        val connected: Boolean = false,
        val left: Int = -1,
        val right: Int = -1,
        val case: Int = -1,
        val leftCharging: Boolean = false,
        val rightCharging: Boolean = false,
        val caseCharging: Boolean = false,
        val mode: Int = 0,
        val address: String = "",
    )
}
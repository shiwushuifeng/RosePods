package moe.chenxy.rosepods.hook

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.Intent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 彻底挡掉小米的耳机页，只留 RosePods 自己的控制页。
 *
 * 系统把我们当小米 TWS 后，点耳机就会启动 `MiuiHeadsetActivity`（或插件版
 * `MiuiHeadsetActivityPlugin`）。那个页面是小米耳机的固定模板：
 * 只有通透/降噪/关闭，没有风噪，还带一堆我们没有的功能（虚拟环绕声、
 * 佩戴检测、双设备…）。所以这里在 `onCreate` 起点拦下来：
 *
 *  1. 先把我们的控制页拉起来（前台）
 *  2. 再 `finish()` 掉小米页面
 *  3. 返回 null，**不执行原 onCreate**，页面根本不渲染
 *
 * 默认对所有设备生效（测试机没有真小米耳机）；要放行真小米耳机时，
 * 把 [ONLY_OUR_DEVICE] 改成 true。
 */
object SettingsRedirectHook {

    private const val OUR_ACTIVITY = "moe.chenxy.rosepods.DeviceControlActivity"

    /** true = 只拦我们自己的耳机；false = 拦住这个页面的一切入口 */
    private const val ONLY_OUR_DEVICE = true

    private val TARGETS = listOf(
        "com.android.settings.bluetooth.MiuiHeadsetActivity",
        "com.android.settings.bluetooth.MiuiHeadsetActivityPlugin",
    )

    private val installed = java.util.Collections.synchronizedSet(HashSet<String>())

    fun install(module: XposedModule, loader: ClassLoader, packageName: String) {
        if (!installed.add(packageName)) return
        var hooked = 0
        for (target in TARGETS) {
            hooked += runCatching { hookActivity(module, loader, target) }.getOrDefault(0)
        }
        HookLog.i("SettingsRedirectHook installed in $packageName, hooked=$hooked")
    }

    private fun hookActivity(module: XposedModule, loader: ClassLoader, className: String): Int {
        val activityClass = try {
            Class.forName(className, false, loader)
        } catch (t: Throwable) {
            HookLog.i("$className not present")
            return 0
        }
        var count = 0
        for (name in listOf("onCreate", "onStart", "onResume")) {
            val method = activityClass.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount <= 1
            } ?: continue
            runCatching {
                module.hook(method)
                    .setId("rosepods.v1:block-$className-$name")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(XposedInterface.Hooker { chain ->
                        val activity = chain.thisObject as? Activity
                        if (activity == null) {
                            chain.proceed()
                        } else if (!shouldBlock(activity)) {
                            chain.proceed()
                        } else {
                            if (name == "onCreate") {
                                HookLog.i("blocked $className (${activity.javaClass.simpleName})")
                                // 把被拦页面的窗口涂成透明 + 去动画，避免黑底闪一下
                                runCatching {
                                    activity.window?.setBackgroundDrawable(
                                        android.graphics.drawable.ColorDrawable(0x00000000)
                                    )
                                    activity.overridePendingTransition(0, 0)
                                }
                                launchOurs(activity)
                            }
                            runCatching { activity.finish() }
                            null
                        }
                    })
                count++
            }.onFailure { HookLog.e("hook $className.$name failed", it) }
        }
        return count
    }

    /**
     * 只拦我们自己的耳机：
     *  1. 地址命中当前连接的耳机 → 拦
     *  2. 名字命中 CERAMICS / ROSE 等关键字 → 拦
     * 其它耳机（真的小米耳机、客人借的）正常走系统页面。
     */
    private fun shouldBlock(activity: Activity): Boolean {
        if (!SettingsBridge.popup) return false
        if (!ONLY_OUR_DEVICE) return true
        val address = addressOf(activity)
        val cached = MiuiHeadsetFragmentHook.cachedAddress()
        if (address != null && cached.isNotBlank() &&
            address.equals(cached, ignoreCase = true)
        ) return true
        val name = nameOf(activity)
        if (name.isNotEmpty() &&
            moe.chenxy.rosepods.core.PodsContract.TARGET_NAME_HINTS.any {
                name.contains(it, ignoreCase = true)
            }
        ) return true
        return false
    }

    @Suppress("DEPRECATION")
    private fun nameOf(activity: Activity): String {
        val intent = activity.intent ?: return ""
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
        return runCatching { device?.name.orEmpty() }.getOrDefault("")
    }

    private fun launchOurs(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent().apply {
                    setClassName("moe.chenxy.rosepods", OUR_ACTIVITY)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("from_settings", true)
                }
            )
        }.onFailure { HookLog.e("launch control page failed", it) }
    }

    @Suppress("DEPRECATION")
    private fun addressOf(activity: Activity): String? {
        val intent = activity.intent ?: return null
        intent.getStringExtra("bluetoothaddress")?.let { if (it.isNotBlank()) return it }
        (intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice)?.let {
            return it.address
        }
        return null
    }
}
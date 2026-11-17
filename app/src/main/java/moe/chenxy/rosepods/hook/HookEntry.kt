package moe.chenxy.rosepods.hook

import android.app.Service
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块入口（libxposed 新 API）
 *
 * Vector / LSPosed 1.9+ 只加载声明了 `META-INF/xposed/` 的模块，
 * 经典 `de.robv.android.xposed` 入口在 Vector 2.x 上不会被调用。
 *
 * 注意：不同进程拿到的回调不一样。普通 App 进程通常收到 `onPackageLoaded`，
 * 而 com.android.bluetooth 这类常驻进程实测只收到 `onPackageReady`
 * （框架日志里是 dispatchPackageReady），所以两个回调都接，安装逻辑幂等。
 */
class HookEntry : XposedModule() {


    init {
        // 构造函数里打日志：确认框架是否真的实例化了入口类
        HookLog.i("entry constructed")
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        HookLog.i("module loaded in ${param.processName}")
        log(Log.INFO, "RosePods", "module loaded in ${param.processName}")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val proc = currentProcess()
        HookLog.i("packageLoaded: pkg=${param.packageName} proc=$proc")
        install(param.packageName, proc, param.defaultClassLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val proc = currentProcess()
        HookLog.i("packageReady: pkg=${param.packageName} proc=$proc")
        install(param.packageName, proc, param.classLoader)
    }

    /** 尽力拿到当前进程的 Context（Application 可能还没建好） */
    private fun appContext(): android.content.Context? {
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            (at.getMethod("currentApplication").invoke(null) as? android.content.Context)?.let {
                return it
            }
            val thread = at.getMethod("currentActivityThread").invoke(null)
            (at.getMethod("getSystemContext").invoke(thread) as? android.content.Context)?.let {
                return it
            }
        }
        return null
    }

    /** libxposed 的 Param 没有 processName，用 ActivityThread 拿 */
    private fun currentProcess(): String = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentProcessName")
            .invoke(null) as? String ?: ""
    }.getOrDefault("")

    private val installedPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 按**进程名**分发（systemui 进程里 packageName 可能报成 com.android.settings，
     * 用 packageName 判断会走错分支）。
     */
    private fun install(packageName: String, processName: String, loader: ClassLoader) {
        val target = when {
            processName == "com.android.systemui" -> "com.android.systemui"
            processName == "com.android.bluetooth" -> "com.android.bluetooth"
            processName == "com.xiaomi.bluetooth" -> "com.xiaomi.bluetooth"
            processName.startsWith("com.android.settings") -> "com.android.settings"
            else -> packageName
        }
        if (target !in setOf(
                "com.android.bluetooth", "com.android.settings",
                "com.xiaomi.bluetooth", "com.android.systemui"
            )
        ) {
            HookLog.i("install() skipped: target=$target (pkg=$packageName proc=$processName)")
            return
        }
        if (!installedPackages.add(target)) {
            HookLog.i("install() already done for $target")
            return
        }
        HookLog.i("install() target=$target (pkg=$packageName proc=$processName)")
        // 设置镜像 + 向 App 自报「这个进程已加载模块」
        appContext()?.let { runCatching { SettingsBridge.install(it, processName, apiVersion) } }
        when (target) {
            "com.android.bluetooth" -> installBluetooth(loader)
            "com.android.settings" -> {
                SettingsBatteryHook.install(this, loader)
                MiuiSettingsProxyHook.install(this, loader, target)
                MiuiHeadsetFragmentHook.install(this, loader, target)
                SettingsRedirectHook.install(this, loader, target)
            }
            "com.xiaomi.bluetooth" -> MiuiHeadsetBridge.install(this, loader)
            "com.android.systemui" -> installSystemUi(loader)
        }
    }

    /** 音量面板降噪控制：完整移植 HyperVolumeAnc 的实现 */
    private fun installSystemUi(loader: ClassLoader) {
        runCatching {
            moe.chenxy.rosepods.hva.HvaVolumeEntry().install(this, loader)
        }.onFailure { HookLog.e("HvaVolumeEntry install failed", it) }
    }

    private fun installBluetooth(loader: ClassLoader) {
        try {
            val adapterService = Class.forName(
                "com.android.bluetooth.btservice.AdapterService", false, loader
            )
            val onCreate = adapterService.getDeclaredMethod("onCreate")
            hook(onCreate).intercept(XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                try {
                    val service = chain.thisObject
                    if (service is Service) SystemBridge.attach(service)
                } catch (t: Throwable) {
                    HookLog.e("SystemBridge.attach failed", t)
                }
                result
            })
            log(Log.INFO, "RosePods", "hooked AdapterService.onCreate (api ${apiVersion})")
        } catch (t: Throwable) {
            log(Log.ERROR, "RosePods", "install failed: ${t.message}", t)
        }
    }
}

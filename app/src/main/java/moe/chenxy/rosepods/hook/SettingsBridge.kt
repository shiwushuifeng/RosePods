package moe.chenxy.rosepods.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import moe.chenxy.rosepods.core.PodsContract

/**
 * 钩子进程里的设置镜像 + 模块自报。
 *
 *- App 改设置 → 广播 [PodsContract.ACTION_SETTINGS] → 这里更新内存标志
 *- 每个进程加载模块后 → 广播 [PodsContract.ACTION_MODULE_ONLINE] 让 App 知道它活着
 *
 * 这样 App 的「框架检测」完全不需要 root。
 */
object SettingsBridge {

    @Volatile var volumeRow: Boolean = true
        private set
    @Volatile var notification: Boolean = true
        private set
    @Volatile var statusbar: Boolean = true
        private set
    @Volatile var popup: Boolean = true
        private set

    @Volatile private var registered = false
    @Volatile private var contextRef: Context? = null
    @Volatile private var currentProc: String = ""
    @Volatile private var apiVersion: Int = 0

    /** 在任意钩子进程里调用一次 */
    fun install(context: Context, processName: String, api: Int = 0) {
        if (api > 0) apiVersion = api
        contextRef = context.applicationContext
        if (currentProc.isBlank()) currentProc = processName
        reportOnline(context, currentProc)
        startHeartbeat()
        if (registered) return
        registered = true
        runCatching {
            val filter = IntentFilter(PodsContract.ACTION_SETTINGS)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val i = intent ?: return
                    volumeRow = i.getBooleanExtra(PodsContract.EXT_VOLUME_ROW, volumeRow)
                    notification = i.getBooleanExtra(PodsContract.EXT_NOTIFICATION, notification)
                    statusbar = i.getBooleanExtra(PodsContract.EXT_STATUSBAR, statusbar)
                    popup = i.getBooleanExtra(PodsContract.EXT_POPUP, popup)
                    HookLog.i("settings <- row=$volumeRow notif=$notification icon=$statusbar popup=$popup")
                    // 顺便回一句「我在线」，让 App 能实时统计各进程
                    reportOnline(contextRef ?: return, currentProc)
                }
            }, filter, Context.RECEIVER_EXPORTED)
        }.onFailure { HookLog.e("settings receiver failed", it) }
    }

    @Volatile private var heartbeatStarted = false

    /** 每 15 秒报一次「我在线」，App 任何时候打开都能收集到 */
    private fun startHeartbeat() {
        if (heartbeatStarted) return
        heartbeatStarted = true
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                val ctx = contextRef ?: return
                reportOnline(ctx, currentProc)
                handler.postDelayed(this, 15_000)
            }
        }
        handler.postDelayed(tick, 15_000)
    }

    private fun reportOnline(context: Context, processName: String) {
        runCatching {
            context.sendBroadcast(
                Intent(PodsContract.ACTION_MODULE_ONLINE)
                    .setPackage(PodsContract.APP_PKG)
                    .putExtra(PodsContract.EXT_PROC, processName)
                    .putExtra(PodsContract.EXT_API, apiVersion)
            )
        }
    }
}
package moe.chenxy.rosepods.core

import android.content.Context
import android.content.Intent

/** App 侧设置存储（只存本地，改完广播给各钩子进程） */
object AppSettings {

    private const val PREFS = "rosepods_settings"

    var volumeRow: Boolean
        get() = getBool(PodsContract.EXT_VOLUME_ROW, true)
        set(v) = putBool(PodsContract.EXT_VOLUME_ROW, v)

    var notification: Boolean
        get() = getBool(PodsContract.EXT_NOTIFICATION, true)
        set(v) = putBool(PodsContract.EXT_NOTIFICATION, v)

    var statusbar: Boolean
        get() = getBool(PodsContract.EXT_STATUSBAR, true)
        set(v) = putBool(PodsContract.EXT_STATUSBAR, v)

    var popup: Boolean
        get() = getBool(PodsContract.EXT_POPUP, true)
        set(v) = putBool(PodsContract.EXT_POPUP, v)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getBool(key: String, def: Boolean): Boolean =
        lastContext?.let { prefs(it).getBoolean(key, def) } ?: def

    private fun putBool(key: String, value: Boolean) {
        val ctx = lastContext ?: return
        prefs(ctx).edit().putBoolean(key, value).apply()
        broadcast(ctx)
    }

    @Volatile private var lastContext: Context? = null

    fun attach(context: Context) {
        lastContext = context.applicationContext
        broadcast(context.applicationContext)
    }

    /** 把当前设置推给所有钩子进程 */
    fun broadcast(context: Context) {
        val intent = Intent(PodsContract.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(PodsContract.EXT_VOLUME_ROW, volumeRow)
            putExtra(PodsContract.EXT_NOTIFICATION, notification)
            putExtra(PodsContract.EXT_STATUSBAR, statusbar)
            putExtra(PodsContract.EXT_POPUP, popup)
        }
        for (pkg in listOf(
            PodsContract.BT_PKG,
            PodsContract.XIAOMI_BT_PKG,
            "com.android.settings",
            PodsContract.SYSTEM_UI_PKG,
        )) {
            runCatching { context.sendBroadcast(Intent(intent).setPackage(pkg)) }
        }
    }
}
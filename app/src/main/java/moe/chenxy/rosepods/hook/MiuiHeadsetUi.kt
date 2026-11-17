package moe.chenxy.rosepods.hook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.UserHandle
import moe.chenxy.rosepods.core.PodsContract
import moe.chenxy.rosepods.core.Reflect

/**
 * 让第三方耳机在 HyperOS 上"看起来像小米耳机"。
 *
 * 思路来自 HyperPods（Art_Chen）：不碰融合设备中心 / MiLink，
 * 而是直接借用 `com.xiaomi.bluetooth` 自己的通知模板与图标，
 * 在蓝牙进程里发一条**原生耳机通知**，并点亮状态栏的耳机图标。
 *
 * 实测（平板 HyperOS 4 / Android 17）：
 *  - `miheadset_notification_Box/_LeftEar/_RightEar/_Disconnect` 字符串存在
 *  - `ic_headset_notification` drawable 存在
 */
object MiuiHeadsetUi {

    private const val TAG = "RosePods-Ui"
    private const val XIAOMI_BT_PKG = "com.xiaomi.bluetooth"
    private const val NOTIFY_ID = 10003
    private const val ICON_NAME = "wireless_headset"

    @Volatile private var currentTag: String? = null

    /**
     * `allUser` 是隐藏字段；取不到就退回当前进程的用户（蓝牙进程通常是 user 0）。
     */
    private val allUser: UserHandle by lazy {
        runCatching { Reflect.getStaticField(UserHandle::class.java, "ALL") as UserHandle }
            .getOrElse { android.os.Process.myUserHandle() }
    }

    /** 发/更新耳机电量通知 */
    fun post(
        context: Context?,
        device: BluetoothDevice?,
        left: Int,
        right: Int,
        case: Int,
        leftCharging: Boolean,
        rightCharging: Boolean,
        caseCharging: Boolean,
        mode: Int = 0,
    ) {
        if (context == null || device == null) return
        try {
            val resources = xiaomiResources(context) ?: context.resources
            val alias = runCatching { device.alias }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: runCatching { device.name }.getOrNull()
                ?: device.address

            fun str(name: String, fallback: String): String {
                val id = resources.getIdentifier(name, "string", XIAOMI_BT_PKG)
                return if (id != 0) runCatching { resources.getString(id) }.getOrDefault(fallback) else fallback
            }

            val boxLabel = str("miheadset_notification_Box", "充电仓")
            val leftLabel = str("miheadset_notification_LeftEar", "左耳")
            val rightLabel = str("miheadset_notification_RightEar", "右耳")
            val disconnectLabel = str("miheadset_notification_Disconnect", "断开")

            fun pct(v: Int) = if (v in 0..100) "$v%" else "—"
            fun chg(on: Boolean) = if (on) "（充电中）" else ""
            val line1 = "$boxLabel：${pct(case)}${chg(caseCharging)}"
            val line2 = "$leftLabel：${pct(left)}${chg(leftCharging)}  $rightLabel：${pct(right)}${chg(rightCharging)}"
            val content = "$line1\n$line2"

            val channelId = "BTHeadset${device.address}"
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            runCatching {
                manager.createNotificationChannel(
                    NotificationChannel(channelId, alias, NotificationManager.IMPORTANCE_MIN)
                )
            }

            // 点通知本体 → 打开控制页
            val openIntent = Intent().apply {
                setClassName(context.packageName, "moe.chenxy.rosepods.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val contentIntent = PendingIntent.getActivity(
                context, 100, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 通知上唯一的按钮：显示当前模式，点一下切下一个
            // （实测 MIUI 通知动作区只渲染第一个 action，多个选项塞不进去）
            val modeLabel = when (mode) {
                1 -> "降噪"
                2 -> "普通"
                3 -> "通透"
                4 -> "风噪"
                5 -> "自适应"
                6 -> "深度"
                7 -> "动态"
                else -> "切换模式"
            }
            val modeButton = Notification.Action(
                0,
                modeLabel,
                PendingIntent.getBroadcast(
                    context, 21,
                    Intent(PodsContract.ACTION_NOTIFICATION_MODE).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            // "断开"动作：沿用小米的广播，目标是我们自己的 SystemBridge
            val disconnectIntent = Intent(PodsContract.ACTION_NOTIFICATION_DISCONNECT).apply {
                setPackage(context.packageName)
                putExtra("android.bluetooth.device.extra.DEVICE", device)
                putExtra("disconnect", "1")
                identifier = channelId
            }
            val action = Notification.Action(
                285737079,
                disconnectLabel,
                PendingIntent.getBroadcast(
                    context, 0, disconnectIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val extras = Bundle().apply {
                putBoolean("miui.showAction", true)
                val iconId = resources.getIdentifier("ic_headset_notification", "drawable", XIAOMI_BT_PKG)
                if (iconId != 0) {
                    putParcelable("miui.appIcon", Icon.createWithResource(XIAOMI_BT_PKG, iconId))
                }
            }

            val builder = Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setWhen(0L)
                .setTicker(alias)
                .setDefaults(-1)
                .setContentTitle(alias)
                .setContentText(content)
                .setStyle(Notification.BigTextStyle().bigText(content))
                .setContentIntent(contentIntent)
                .setExtras(extras)
                .addAction(modeButton)
                .addAction(action)
                .setVisibility(Notification.VISIBILITY_PUBLIC)

            val notification = builder.build()
            val tag = "BTHeadset${device.address}"
            currentTag = tag
            Reflect.callMethod(manager, "notifyAsUser", tag, NOTIFY_ID, notification, allUser)
            HookLog.i("notification posted: $content")
        } catch (t: Throwable) {
            HookLog.e("post notification failed", t)
        }
    }

    /** 状态栏耳机图标（小米的 wireless_headset） */
    fun setHeadsetIcon(context: Context?, show: Boolean) {
        if (context == null) return
        try {
            val statusBar = context.getSystemService("statusbar")
            Reflect.callMethod(statusBar, "setIconVisibility", ICON_NAME, show)
            HookLog.i("statusbar icon $ICON_NAME = $show")
        } catch (t: Throwable) {
            HookLog.e("setIconVisibility failed", t)
        }
    }

    fun cancel(context: Context?, device: BluetoothDevice?) {
        if (context == null) return
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val tag = currentTag ?: "BTHeadset${device?.address}"
            Reflect.callMethod(manager, "cancelAsUser", tag, NOTIFY_ID, allUser)
        } catch (t: Throwable) {
            HookLog.e("cancel notification failed", t)
        }
        setHeadsetIcon(context, false)
    }

    /** 拿到小米蓝牙 App 的资源，才能用它的文案和图标 */
    private fun xiaomiResources(context: Context) = runCatching {
        context.createPackageContext(XIAOMI_BT_PKG, 0).resources
    }.getOrNull()
}
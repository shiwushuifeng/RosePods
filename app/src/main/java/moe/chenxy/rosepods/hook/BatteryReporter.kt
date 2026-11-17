package moe.chenxy.rosepods.hook

import android.bluetooth.BluetoothDevice
import moe.chenxy.rosepods.core.Reflect

/**
 * 把 SPP 读到的电量交给系统蓝牙栈。
 *
 * 两条通道：
 *  1. `AdapterService.setBatteryLevel(device, level, fromBas)`：
 *     系统蓝牙栈更新设备电量，并由 RemoteDevices 广播
 *     `ACTION_BATTERY_LEVEL_CHANGED`；设置页、快捷开关等系统 UI 读取的正是这个值。
 *  2. `BluetoothDevice.setMetadata(key, byte[])`：
 *     写入 untethered（分体式耳机）左右耳/仓电量元数据，
 *     供读取 per-bud 电量的界面使用。
 *
 * 隐藏 API 全部用反射调用，失败只记日志。
 */
object BatteryReporter {

    /** AOSP 元数据键值（BluetoothDevice.METADATA_UNTETHERED_*） */
    private const val KEY_LEFT_BATTERY = 10
    private const val KEY_RIGHT_BATTERY = 11
    private const val KEY_CASE_BATTERY = 12
    private const val KEY_LEFT_CHARGING = 13
    private const val KEY_RIGHT_CHARGING = 14
    private const val KEY_CASE_CHARGING = 15

    /** 上一次的低电量档位，用于避免重复弹 HyperOS 蓝牙低电量提示 */
    @Volatile private var lastLowBucket: Int = -1

    fun publish(
        adapterService: Any?,
        device: BluetoothDevice?,
        left: Int,
        right: Int,
        case: Int,
        charging: Boolean,
    ) {
        if (adapterService == null || device == null) return

        // 只在真正有有效值时注入；某只耳机 -1 表示未上报（关机/脱离）
        val valid = listOf(left, right, case).filter { it in 1..100 }
        if (valid.isEmpty()) return
        val overall = valid.min()

        // 1) 系统级电量：设置页 / 快捷开关读取的入口
        try {
            Reflect.callMethod(adapterService, "setBatteryLevel", device, overall, false)
            val readBack = runCatching { Reflect.callMethod(device, "getBatteryLevel") }.getOrNull()
            HookLog.i("battery -> system: $overall% (L$left R$right C$case) readBack=$readBack")
        } catch (t: Throwable) {
            HookLog.e("setBatteryLevel failed", t)
        }

        // 2) 分体式耳机的左右耳 / 充电仓元数据
        setMetadata(device, "METADATA_UNTETHERED_LEFT_BATTERY", KEY_LEFT_BATTERY, left)
        setMetadata(device, "METADATA_UNTETHERED_RIGHT_BATTERY", KEY_RIGHT_BATTERY, right)
        setMetadata(device, "METADATA_UNTETHERED_CASE_BATTERY", KEY_CASE_BATTERY, case)
        val flag = if (charging) 1 else 0
        setMetadata(device, "METADATA_UNTETHERED_LEFT_CHARGING", KEY_LEFT_CHARGING, flag)
        setMetadata(device, "METADATA_UNTETHERED_RIGHT_CHARGING", KEY_RIGHT_CHARGING, flag)
        setMetadata(device, "METADATA_UNTETHERED_CASE_CHARGING", KEY_CASE_CHARGING, flag)

        // 3) HyperOS 的「蓝牙免提电量」：低电量时下发，恢复后取消
        updateLowBattery(adapterService, overall)
    }

    /**
     * HyperOS 会在低电量时显示蓝牙电量提示。系统只在 0..9 档之间接受，
     * 因此这里只在真正进入低电量、且档位变化时下发，避免反复打扰。
     */
    private fun updateLowBattery(adapterService: Any, overall: Int) {
        try {
            if (overall in 1..10) {
                val bucket = (overall / 10).coerceIn(0, 9)
                if (bucket != lastLowBucket) {
                    lastLowBucket = bucket
                    Reflect.callMethod(adapterService, "notifyOrCancelBluetoothBattery", true, bucket)
                }
            } else if (lastLowBucket >= 0) {
                lastLowBucket = -1
                Reflect.callMethod(adapterService, "notifyOrCancelBluetoothBattery", false, 0)
            }
        } catch (t: Throwable) {
            HookLog.e("low battery notify failed", t)
        }
    }

    /** 断开后清掉系统电量，避免显示过期数值 */
    fun clear(adapterService: Any?, device: BluetoothDevice?) {
        if (adapterService == null || device == null) return
        try {
            Reflect.callMethod(adapterService, "setBatteryLevel", device, -1, false)
        } catch (t: Throwable) {
            HookLog.e("clear battery failed", t)
        }
        if (lastLowBucket >= 0) {
            lastLowBucket = -1
            runCatching {
                Reflect.callMethod(adapterService, "notifyOrCancelBluetoothBattery", false, 0)
            }
        }
    }

    private fun setMetadata(device: BluetoothDevice, field: String, fallbackKey: Int, value: Int) {
        if (value < 0) return
        try {
            val key = try {
                Reflect.getStaticIntField(BluetoothDevice::class.java, field)
            } catch (t: Throwable) {
                fallbackKey
            }
            Reflect.callMethod(device, "setMetadata", key, byteArrayOf(value.toByte()))
        } catch (t: Throwable) {
            HookLog.e("setMetadata($field) failed", t)
        }
    }
}
package moe.chenxy.rosepods.hook

import android.bluetooth.BluetoothDevice
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import moe.chenxy.rosepods.core.PodsContract
import moe.chenxy.rosepods.core.Reflect

/**
 * 让系统「设置 → 蓝牙 → 设备详情」显示**左耳 / 右耳 / 充电仓**三个电量。
 *
 * 原理：
 *  设置页的 `CachedBluetoothDevice.getBatteryFromBluetoothService()` 决定显示几块电量。
 *  对普通经典耳机它只返回一个总电量（`getMinBatteryLevelWithMemberDevices()`），
 *  对助听器/LE Audio 才读分体元数据。
 *
 *  我们已经把分体电量写进蓝牙栈的元数据：
 *    METADATA_UNTETHERED_LEFT_BATTERY / RIGHT / CASE = 10 / 11 / 12
 *  所以这里在设置进程里 hook 那个方法，直接按元数据拼一个 `BatteryLevelsInfo` 返回。
 *
 * 全部反射，类/方法不存在时安静跳过。
 */
object SettingsBatteryHook {

    private const val KEY_LEFT = 10
    private const val KEY_RIGHT = 11
    private const val KEY_CASE = 12

    fun install(module: XposedModule, loader: ClassLoader) {
        try {
            val cachedClass = Class.forName(
                "com.android.settingslib.bluetooth.CachedBluetoothDevice", false, loader
            )
            val batteryClass = runCatching {
                Class.forName("com.android.settingslib.bluetooth.BatteryLevelsInfo", false, loader)
            }.getOrNull()

            if (batteryClass == null) {
                HookLog.e("BatteryLevelsInfo not found in settings; skip")
                return
            }

            val method = cachedClass.declaredMethods.firstOrNull {
                it.name == "getBatteryFromBluetoothService" && it.parameterCount == 0
            }
            if (method == null) {
                HookLog.e("getBatteryFromBluetoothService not found; skip")
                return
            }

            module.hook(method)
                .setId("rosepods.v1:settings-battery")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(XposedInterface.Hooker { chain ->
                    val original = chain.proceed()
                    var result: Any? = original
                    try {
                        val device = deviceOf(chain.thisObject)
                        if (device != null && isTarget(device)) {
                            val left = readMeta(device, KEY_LEFT)
                            val right = readMeta(device, KEY_RIGHT)
                            val case = readMeta(device, KEY_CASE)
                            if (left >= 0 || right >= 0 || case >= 0) {
                                val overall = listOf(left, right, case)
                                    .filter { it in 1..100 }.minOrNull() ?: -1
                                val built = newBatteryLevels(batteryClass, left, right, case, overall)
                                if (built != null) {
                                    HookLog.i("settings battery L$left R$right C$case")
                                    result = built
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        HookLog.e("settings battery hook failed", t)
                    }
                    result
                })
            HookLog.i("SettingsBatteryHook installed")
        } catch (t: Throwable) {
            HookLog.e("SettingsBatteryHook install failed", t)
        }
    }

    /** CachedBluetoothDevice.mDevice */
    private fun deviceOf(cached: Any?): BluetoothDevice? {
        if (cached == null) return null
        return runCatching {
            val field = cached.javaClass.declaredFields.firstOrNull { it.name == "mDevice" }
                ?: cached.javaClass.superclass?.declaredFields?.firstOrNull { it.name == "mDevice" }
            field?.isAccessible = true
            field?.get(cached) as? BluetoothDevice
        }.getOrNull()
    }

    private fun isTarget(device: BluetoothDevice): Boolean {
        val name = runCatching { device.name }.getOrNull().orEmpty()
        return PodsContract.TARGET_NAME_HINTS.any { name.contains(it, ignoreCase = true) }
    }

    private fun readMeta(device: BluetoothDevice, key: Int): Int = runCatching {
        val bytes = Reflect.callMethod(device, "getMetadata", key) as? ByteArray
        if (bytes == null || bytes.isEmpty()) -1 else bytes[0].toInt() and 0xFF
    }.getOrDefault(-1)

    private fun newBatteryLevels(
        clazz: Class<*>,
        left: Int,
        right: Int,
        case: Int,
        overall: Int,
    ): Any? = runCatching {
        val ctor = clazz.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        ctor.isAccessible = true
        ctor.newInstance(left, right, case, overall)
    }.getOrNull()
}
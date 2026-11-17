package moe.chenxy.rosepods.core

import android.bluetooth.BluetoothDevice

/** 耳机的即时状态，App 与系统侧共用。 */
data class PodsState(
    val connected: Boolean = false,
    val left: Int = -1,
    val right: Int = -1,
    val case: Int = -1,
    val charging: Boolean = false,
    val leftCharging: Boolean = false,
    val rightCharging: Boolean = false,
    val caseCharging: Boolean = false,
    val mode: Int = 0,
    val level: Int = 0,
    val address: String = "",
    val name: String = "",
    val systemOwned: Boolean = false,
) {
    val hasBattery: Boolean get() = left >= 0 || right >= 0 || case >= 0

    /** 系统电量取三处中的最低有效值，与系统蓝牙栈的聚合习惯一致 */
    val overallBattery: Int
        get() {
            val candidates = listOf(left, right, case).filter { it in 1..100 }
            return if (candidates.isEmpty()) -1 else candidates.min()
        }

    fun summary(): String {
        fun show(v: Int) = if (v in 0..100) "$v%" else "—"
        val battery = if (hasBattery) {
            "L ${show(left)}  R ${show(right)}  仓 ${show(case)}" + if (charging) "  充电中" else ""
        } else {
            "电量未知"
        }
        return "$battery   模式 ${PodsContract.modeName(mode)}"
    }

    companion object {
        fun fromDevice(device: BluetoothDevice?, connected: Boolean, systemOwned: Boolean): PodsState =
            PodsState(
                connected = connected,
                address = device?.address ?: "",
                name = device?.name ?: "",
                systemOwned = systemOwned,
            )
    }
}
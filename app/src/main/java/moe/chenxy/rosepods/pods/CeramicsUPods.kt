package moe.chenxy.rosepods.pods

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import moe.chenxy.rosepods.core.PodsCodec
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * 弱水时砂 CERAMICS-U（琉璃U）独立客户端
 *
 * 协议依据：roseselsa-RoseLink-Protocol（RS Common V2 / CERAMICS-U）
 *  - SPP 服务 UUID：0cf12d31-fac3-4553-bd80-d6832e7b3931
 *  - 帧格式：FF SEQ LEN BODY... CS AA
 *      LEN = len(BODY)
 *      CS  = (FF + SEQ + LEN + BODY...) & 0xFF
 *  - 接收：DD/FF 开头，同样结构，最后一个 LTV 需按 L 解析
 *
 * 写命令 Body（**不带前缀**）：
 *   09 01 降噪   09 02 普通   09 03 通透   09 04 风噪
 *   09 05 自适应 09 06 深度   09 07 动态自适应
 *   2C NN 降噪强度(1..5)      2D NN 通透强度(1..5)
 *
 * 读命令 Body（带 FA 前缀）：
 *   FA 09 读模式   FA 0C 读电量   FA 01 … 读全部
 *
 * 注意：写命令若误加 02 前缀，设备回 ACK 状态 02（拒绝），且不报错。
 *
 * 该类同时被 App 进程与注入 com.android.bluetooth 的系统侧使用，
 * 因此不依赖任何 Activity/Context，只做协议与连接。
 */
class CeramicsUPods(
    private val onBattery: (left: Int, right: Int, case: Int, charging: Boolean) -> Unit,
    private val onNoiseMode: (mode: Int) -> Unit,
    private val trace: ((String) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "RosePods"

        /** CERAMICS-U 的 SPP 服务 UUID */
        val SPP_UUID: UUID = UUID.fromString("0CF12D31-FAC3-4553-BD80-D6832E7B3931")

        const val NOISE_ANC = 0x01
        const val NOISE_NORMAL = 0x02
        const val NOISE_TRANSPARENT = 0x03
        const val NOISE_WIND = 0x04
        const val NOISE_ADAPTIVE = 0x05
        const val NOISE_DEEP = 0x06
        const val NOISE_DYNAMIC = 0x07

        const val LEVEL_LIGHT = 0x01
        const val LEVEL_MEDIUM = 0x03
        const val LEVEL_DEEP = 0x05
    }

    var adapter: android.bluetooth.BluetoothAdapter? = null
    var lastConnectMethod: String = ""

    /** 当前 SPP 是否已连接（readLoop 存活） */
    @Volatile
    var connected: Boolean = false
        private set

    /** 连接被外部关闭/链路断开时回调（系统侧据此重连） */
    var onClosed: (() -> Unit)? = null

    /**
     * 细分电量回调：左/右/仓各自是否在充电。
     *
     * 实测（平板 + 琉璃U）：**左右耳字节的最高位都是充电标志**，
     * 文档里"只有左耳有充电位"的说法不成立：
     *   0x5D=93% 未充电、0xDD=93% 充电中、0xDE=94% 充电中（右耳）
     */
    var onBatteryDetail: ((left: Int, right: Int, case: Int, leftCharging: Boolean, rightCharging: Boolean, caseCharging: Boolean) -> Unit)? = null

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile private var running = false
    @Volatile private var cancelled = false
    private var seq = 0

    // ---------------------------------------------------------------- 连接

    @Throws(Exception::class)
    fun connect(device: BluetoothDevice) {
        close()
        cancelled = false
        adapter?.cancelDiscovery()

        val errors = StringBuilder()
        repeat(3) { attempt ->
            if (cancelled) throw java.io.IOException("连接已取消")
            val r = tryConnect(device)
            if (r == null) return
            errors.append("try$attempt: ").append(r).append(" ")
            if (attempt < 2) Thread.sleep(3000)
        }
        throw java.io.IOException("连接失败：$errors")
    }

    /** 返回 null 表示成功，否则返回错误摘要 */
    private fun tryConnect(device: BluetoothDevice): String? {
        val errors = StringBuilder()
        // 优先按文档给的 SPP UUID，其次退回通用 SPP
        val tries = listOf(
            "ceramics-u" to SPP_UUID,
            "spp" to UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
        )
        for ((name, uuid) in tries) {
            if (cancelled) return "cancelled"
            for (secure in listOf(false, true)) {
                if (cancelled) return "cancelled"
                val s = try {
                    if (secure) device.createRfcommSocketToServiceRecord(uuid)
                    else device.createInsecureRfcommSocketToServiceRecord(uuid)
                } catch (e: Exception) {
                    continue
                }
                try {
                    s.connect()
                    socket = s
                    input = s.inputStream
                    output = s.outputStream
                    running = true
                    connected = true
                    seq = 0
                    lastConnectMethod = "$name-${if (secure) "secure" else "insecure"}"
                    trace?.invoke("已连接 $lastConnectMethod")
                    Thread(::readLoop, "CeramicsU-rx").apply { isDaemon = true }.start()
                    // 连接后请求一次信息（电量/模式/强度）
                    Thread.sleep(300)
                    requestInfo()
                    return null
                } catch (e: Exception) {
                    connected = false
                    errors.append("$name/${if (secure) "s" else "i"}:${e.javaClass.simpleName} ")
                    runCatching { s.close() }
                }
            }
        }
        return errors.toString()
    }

    fun close() {
        cancelled = true
        running = false
        connected = false
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    // ---------------------------------------------------------------- 发送

    /** 按协议组帧：FF SEQ LEN BODY CS AA */
    private fun buildFrame(body: ByteArray): ByteArray {
        val frame = PodsCodec.buildFrame(seq, body)
        seq = (seq + 1) and 0xFF
        return frame
    }

    @Synchronized
    private fun send(body: ByteArray): Boolean {
        val out = output ?: return false
        val frame = buildFrame(body)
        return try {
            out.write(frame)
            out.flush()
            val line = "TX ${PodsCodec.toHex(frame)}"
            Log.i(TAG, line)
            trace?.invoke(line)
            true
        } catch (e: Exception) {
            trace?.invoke("TX失败 ${e.javaClass.simpleName}")
            false
        }
    }

    /** 发送任意 BODY（十六进制），用于试验 */
    fun sendRaw(hex: String): Boolean {
        val body = PodsCodec.parseHex(hex) ?: return false
        return send(body)
    }

    /** 降噪模式 */
    fun setNoiseMode(mode: Int): Boolean =
        send(byteArrayOf(0x09, mode.toByte()))

    /** 降噪强度 1..5 */
    fun setNoiseLevel(level: Int): Boolean =
        send(byteArrayOf(0x2C, level.toByte()))

    /** 通透强度 1..5 */
    fun setTransparencyLevel(level: Int): Boolean =
        send(byteArrayOf(0x2D, level.toByte()))

    /**
     * 请求基础信息（电量/模式等）。
     *
     * 注意：`FA 01` 的「读全部」应答里，模式段(`02 09 xx`)会被前一段的长度字段吞掉，
     * 解析不出来（实测：73 字节的应答里 18 段，没有 0x09）。所以这里再补发
     * 单条读命令：`FA 09` 读模式、`FA 0C` 读电量、`FA 2C`/`FA 2D` 读强度。
     */
    fun requestInfo(): Boolean {
        val ok = send(
            byteArrayOf(
                0xFA.toByte(), 0x01, 0x07, 0x08, 0x09, 0x0C, 0x0D, 0x0E, 0x12, 0x2A,
                0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x31, 0x32, 0x33, 0x36, 0x37,
                0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3F, 0x45, 0x46, 0x49,
            )
        )
        // 单条读：读全部的应答解析不出模式
        send(byteArrayOf(0xFA.toByte(), 0x09))
        send(byteArrayOf(0xFA.toByte(), 0x0C))
        send(byteArrayOf(0xFA.toByte(), 0x2C))
        send(byteArrayOf(0xFA.toByte(), 0x2D))
        return ok
    }

    // ---------------------------------------------------------------- 接收

    private fun readLoop() {
        val buf = ByteArray(1024)
        val acc = ArrayList<Byte>(4096)
        try {
            while (running) {
                val n = input?.read(buf) ?: break
                if (n <= 0) continue
                for (i in 0 until n) acc.add(buf[i])
                // 按 AA 结尾切帧，容忍粘包/半包
                val (frames, rest) = PodsCodec.splitFrames(acc)
                frames.forEach { handleFrame(it) }
                acc.clear()
                acc.addAll(rest)
                if (acc.size > 4096) acc.clear()
            }
        } catch (e: Exception) {
            trace?.invoke("读取中断 ${e.javaClass.simpleName}")
        } finally {
            val wasRunning = running
            running = false
            connected = false
            trace?.invoke("连接已断开")
            if (wasRunning) runCatching { onClosed?.invoke() }
        }
    }

    /**
     * 解析接收帧：HEADER SEQ LEN [L TYPE VALUE...] CS AA
     * 这里按 LTV 遍历取 TYPE
     */
    private fun handleFrame(f: ByteArray) {
        if (f.size < 5) return
        trace?.invoke("RX ${PodsCodec.toHex(f)}")

        if (!PodsCodec.isChecksumValid(f)) {
            val expected = PodsCodec.checksum(f, f.size - 2)
            val actual = f[f.size - 2].toInt() and 0xFF
            trace?.invoke("CS 不匹配 期望=${expected.toString(16)} 实际=${actual.toString(16)}")
        }

        for (entry in PodsCodec.parseEntries(f)) {
            if (entry.type == 0x0C || entry.type == 0x09 || entry.type == 0x2C) {
                trace?.invoke("条目 0x${entry.type.toString(16)} len=${entry.value.size} val=${entry.value.joinToString("") { "%02x".format(it) }}")
            }
            when (entry.type) {
                0x0C -> if (entry.value.size >= 3) {
                    val l0 = entry.value[0].toInt() and 0xFF
                    val r0 = entry.value[1].toInt() and 0xFF
                    val c0 = entry.value[2].toInt() and 0xFF
                    // 0xFF / 0x7F 表示该耳未上报（关机、脱离）——按未知处理，不能当 127%
                    val left = if ((l0 and 0x7F) in 1..100) l0 and 0x7F else -1
                    val right = if ((r0 and 0x7F) in 1..100) r0 and 0x7F else -1
                    val case = if ((c0 and 0x7F) in 1..100) c0 and 0x7F else -1
                    // 三个字节的最高位都是充电标志（右下角实测：0xDE = 94% 充电中）
                    val leftCharging = left >= 0 && (l0 and 0x80) != 0
                    val rightCharging = right >= 0 && (r0 and 0x80) != 0
                    val caseCharging = case >= 0 && (c0 and 0x80) != 0
                    trace?.invoke(
                        "电量 左=$left 右=$right 仓=$case " +
                            "充电(L=$leftCharging R=$rightCharging C=$caseCharging) " +
                            "raw ${l0.toString(16)}/${r0.toString(16)}/${c0.toString(16)}"
                    )
                    onBattery(left, right, case, leftCharging)
                    onBatteryDetail?.invoke(left, right, case, leftCharging, rightCharging, caseCharging)
                }
                0x09 -> if (entry.value.isNotEmpty()) {
                    val m = entry.value[0].toInt() and 0xFF
                    trace?.invoke("降噪模式=$m")
                    onNoiseMode(m)
                }
            }
        }
    }
}
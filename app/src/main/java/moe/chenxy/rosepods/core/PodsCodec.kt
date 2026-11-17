package moe.chenxy.rosepods.core

/**
 * 协议帧编解码（纯 Kotlin，不依赖 Android，可在 JVM 上直接跑单测）
 *
 * 帧格式：`FF SEQ LEN BODY... CS AA`
 *  - LEN = len(BODY)
 *  - CS  = (FF + SEQ + LEN + BODY 全部字节) & 0xFF
 *
 * 接收帧：`DD SEQ ...`，BODY 为 LTV 结构（1 字节长度 + 1 字节类型 + 值）。
 */
object PodsCodec {

    const val HEADER_HOST_TO_DEVICE = 0xFF
    const val HEADER_DEVICE_TO_HOST = 0xDD
    const val TAIL = 0xAA

    /** 组帧 */
    fun buildFrame(seq: Int, body: ByteArray): ByteArray {
        val frame = ByteArray(3 + body.size + 2)
        frame[0] = HEADER_HOST_TO_DEVICE.toByte()
        frame[1] = (seq and 0xFF).toByte()
        frame[2] = body.size.toByte()
        System.arraycopy(body, 0, frame, 3, body.size)
        frame[3 + body.size] = checksum(frame, 3 + body.size).toByte()
        frame[4 + body.size] = TAIL.toByte()
        return frame
    }

    /** 求和校验：对前 [length] 个字节求和取低 8 位 */
    fun checksum(bytes: ByteArray, length: Int): Int {
        var sum = 0
        for (i in 0 until length) sum += bytes[i].toInt() and 0xFF
        return sum and 0xFF
    }

    /**
     * 从累积缓冲里按 `AA` 结尾切出完整帧，返回 (完整帧列表, 剩余未完成字节)。
     * 容忍粘包与半包。
     */
    fun splitFrames(acc: List<Byte>): Pair<List<ByteArray>, List<Byte>> {
        val frames = ArrayList<ByteArray>()
        var start = 0
        var i = 0
        // 注意要走到 size-1：帧尾 AA 可能正好是缓冲区最后一个字节
        while (i < acc.size) {
            if ((acc[i].toInt() and 0xFF) == TAIL) {
                frames.add(ByteArray(i + 1 - start) { acc[start + it] })
                start = i + 1
            }
            i++
        }
        val rest = if (start > 0) acc.subList(start, acc.size).toList() else acc
        return frames to rest
    }

    data class Entry(val type: Int, val value: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Entry && other.type == type && other.value.contentEquals(value)

        override fun hashCode(): Int = 31 * type + value.contentHashCode()
    }

    /**
     * 解析接收帧的字段。
     *
     * 实测两种帧：单条上报 `dd 03 04 0C 5D 5E 2C …`（LEN=body 长度）
     * 与「读全部」应答 `dd 04 15 01 01 04 02 …`（**LEN=条目数 21，不是 body 长度**）。
     * 所以这里不用 LEN 字段，直接取 CS/AA 之间的全部内容当 body：
     *  - 先按完整 LTV（`L 类型 值…`，L 含类型字节）解析，能恰好消费完就采用；
     *  - 否则按「类型 + 值」解析（单条上报就是这种）。
     */
    fun parseEntries(frame: ByteArray): List<Entry> {
        if (frame.size < 5) return emptyList()
        val header = frame[0].toInt() and 0xFF
        if (header != HEADER_DEVICE_TO_HOST && header != HEADER_HOST_TO_DEVICE) return emptyList()

        val body = frame.copyOfRange(3, frame.size - 2)
        if (body.isEmpty()) return emptyList()

        parseLtv(body)?.let { return it }
        return listOf(Entry(body[0].toInt() and 0xFF, body.copyOfRange(1, body.size)))
    }

    /** 完整 LTV 解析；不能恰好消费完整个 body 时返回 null */
    private fun parseLtv(body: ByteArray): List<Entry>? {
        val entries = ArrayList<Entry>()
        var p = 0
        while (p < body.size) {
            if (p + 1 >= body.size) return null
            val l = body[p].toInt() and 0xFF
            if (l < 1 || p + 1 + l > body.size) return null
            val type = body[p + 1].toInt() and 0xFF
            entries.add(Entry(type, body.copyOfRange(p + 2, p + 1 + l)))
            p += 1 + l
        }
        return entries.ifEmpty { null }
    }

    /** 校验帧尾 CS 是否与内容匹配 */
    fun isChecksumValid(frame: ByteArray): Boolean {
        if (frame.size < 5) return false
        return checksum(frame, frame.size - 2) == (frame[frame.size - 2].toInt() and 0xFF)
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun parseHex(hex: String): ByteArray? {
        val h = hex.filter { it.isDigit() || it in "abcdefABCDEF" }
        if (h.length < 2 || h.length % 2 != 0) return null
        return ByteArray(h.length / 2) {
            Integer.parseInt(h.substring(it * 2, it * 2 + 2), 16).toByte()
        }
    }
}
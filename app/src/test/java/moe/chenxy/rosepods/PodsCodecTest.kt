package moe.chenxy.rosepods

import moe.chenxy.rosepods.core.PodsCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用文档里实机抓到的帧做回归测试（不需要手机/模拟器）：
 *  - 组帧 / 校验
 *  - LTV 解析（电量、降噪模式）
 *  - 粘包 / 半包切分
 */
class PodsCodecTest {

    @Test
    fun buildFrame_matchesProtocol() {
        // 09 01 = 降噪；FF + 00 + 02 + 09 + 01 = 0x0B
        val frame = PodsCodec.buildFrame(0, byteArrayOf(0x09, 0x01))
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0x00, 0x02, 0x09, 0x01, 0x0B, 0xAA.toByte()),
            frame
        )
    }

    @Test
    fun buildFrame_sequenceWraps() {
        val frame = PodsCodec.buildFrame(256, byteArrayOf(0x09, 0x02))
        assertEquals(0x00, frame[1].toInt() and 0xFF)
    }

    @Test
    fun checksum_batteryFrameFromDevice() {
        // 实机：dd 10 04 0c 39 39 3a a9 aa（左 0x39 右 0x39 仓 0x3a）
        val frame = hex("dd10040c39393aa9aa")
        assertTrue(PodsCodec.isChecksumValid(frame))
    }

    @Test
    fun checksum_modeFrameFromDevice() {
        // 实机：dd 0a 02 09 05 f7 aa（模式=5 自适应）
        val frame = hex("dd0a020905f7aa")
        assertTrue(PodsCodec.isChecksumValid(frame))
    }

    @Test
    fun checksum_detectsCorruption() {
        val frame = hex("dd10040c39393aa9aa")
        frame[4] = 0x38 // 篡改一个字节
        assertFalse(PodsCodec.isChecksumValid(frame))
    }

    @Test
    fun parseEntries_battery() {
        val entries = PodsCodec.parseEntries(hex("dd10040c39393aa9aa"))
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals(0x0C, entry.type)
        assertEquals(3, entry.value.size)
        assertEquals(0x39, entry.value[0].toInt() and 0x7F)
        assertEquals(0x39, entry.value[1].toInt() and 0x7F)
        assertEquals(0x3A, entry.value[2].toInt() and 0x7F)
        assertFalse((entry.value[0].toInt() and 0x80) != 0) // 未充电
    }

    @Test
    fun parseEntries_mode() {
        val entries = PodsCodec.parseEntries(hex("dd0a020905f7aa"))
        assertEquals(1, entries.size)
        assertEquals(0x09, entries[0].type)
        assertEquals(5, entries[0].value[0].toInt() and 0xFF)
    }

    @Test
    fun splitFrames_handlesStickyAndPartial() {
        val battery = hex("dd10040c39393aa9aa")
        val mode = hex("dd0a020905f7aa")

        // 粘包：两帧连在一起
        val sticky = battery.toList() + mode.toList()
        val (frames, rest) = PodsCodec.splitFrames(sticky)
        assertEquals(2, frames.size)
        assertArrayEquals(battery, frames[0])
        assertArrayEquals(mode, frames[1])
        assertTrue(rest.isEmpty())

        // 半包：只到一半
        val partial = battery.toList().subList(0, 4)
        val (none, leftover) = PodsCodec.splitFrames(partial)
        assertTrue(none.isEmpty())
        assertEquals(4, leftover.size)

        // 半包补齐后应能切出
        val (completed, rest2) = PodsCodec.splitFrames(partial + battery.toList().subList(4, battery.size))
        assertEquals(1, completed.size)
        assertTrue(rest2.isEmpty())
    }

    @Test
    fun parseEntries_multipleLtvSegments() {
        // 模拟「读全部信息」的应答：body 里两段 LTV
        //   [04 0C 39 39 3A] 电量 ; [02 09 05] 模式
        val body = hex("040c39393a" + "02" + "0905")
        val frame = deviceFrame(0x0A, body)

        val entries = PodsCodec.parseEntries(frame)
        assertEquals(2, entries.size)
        assertEquals(0x0C, entries[0].type)
        assertEquals(3, entries[0].value.size)
        assertEquals(0x09, entries[1].type)
        assertEquals(5, entries[1].value[0].toInt() and 0xFF)
    }

    @Test
    fun parseEntries_readAllResponse_containsBatteryAndMode() {
        // 实机（平板）读全部应答：LEN 字段是 0x15(=21 条目)，不是 body 长度
        val frame = hex(
            "dd0415010104020103020407050011051201130314081500020701020901040c606039" +
                "040d000308020e00021201022a04022b01022c05022d05022e01023101023200023300" +
                "053601010001b5aa"
        )
        assertTrue(PodsCodec.isChecksumValid(frame))

        val entries = PodsCodec.parseEntries(frame)
        val battery = entries.firstOrNull { it.type == 0x0C }
        assertEquals("电量段应存在", true, battery != null)
        assertEquals(3, battery!!.value.size)
        assertEquals(96, battery.value[0].toInt() and 0x7F)
        assertEquals(96, battery.value[1].toInt() and 0x7F)
        assertEquals(57, battery.value[2].toInt() and 0x7F)

        assertEquals(5, entries.first { it.type == 0x2C }.value[0].toInt()) // 降噪强度
        assertEquals(5, entries.first { it.type == 0x2D }.value[0].toInt()) // 通透强度
    }

    /** 构造耳机 → 手机方向的帧：DD SEQ LEN BODY CS AA */
    private fun deviceFrame(seq: Int, body: ByteArray): ByteArray {
        val frame = ByteArray(3 + body.size + 2)
        frame[0] = 0xDD.toByte()
        frame[1] = (seq and 0xFF).toByte()
        frame[2] = body.size.toByte()
        System.arraycopy(body, 0, frame, 3, body.size)
        frame[3 + body.size] = PodsCodec.checksum(frame, 3 + body.size).toByte()
        frame[4 + body.size] = 0xAA.toByte()
        return frame
    }

    @Test
    fun parseHex_rejectsOddLength() {
        assertEquals(null, PodsCodec.parseHex("abc"))
        assertEquals(null, PodsCodec.parseHex("zz"))
        assertArrayEquals(byteArrayOf(0x0A, 0x0B), PodsCodec.parseHex("0a0b"))
    }

    private fun hex(s: String): ByteArray = PodsCodec.parseHex(s)!!
}
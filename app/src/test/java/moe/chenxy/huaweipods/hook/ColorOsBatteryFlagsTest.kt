package moe.chenxy.huaweipods.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行时开关的位掩码解析与短 TTL 缓存。
 *
 * 这两个开关以前是编译期常量，验证一次要重装 + 重启宿主；改成 `Settings.Global` 位掩码之后，
 * "读得太频繁"（每次卡片 bind）和"读失败"（限额、异常）就成了必须锁住的两个行为。
 */
class ColorOsBatteryFlagsTest {
    private var now = 1_000L
    private var reads = 0
    private var mask = 0
    private var failing = false

    private fun reader(ttlMs: Long = 3_000) = ColorOsBatteryFlagReader(
        readMask = {
            reads++
            if (failing) error("settings unavailable") else mask
        },
        ttlMs = ttlMs,
        now = { now },
    )

    @Test
    fun `defaults to off`() {
        val reader = reader()
        assertFalse(reader.dtoEnabled())
        assertFalse(reader.cardEnabled())
        assertEquals(0, reader.mask())
    }

    @Test
    fun `mask bits map to the two switches`() {
        mask = ColorOsBatteryFlags.BIT_DTO
        var reader = reader()
        assertTrue(reader.dtoEnabled())
        assertFalse(reader.cardEnabled())

        mask = ColorOsBatteryFlags.BIT_CARD
        reader = reader()
        assertFalse(reader.dtoEnabled())
        assertTrue(reader.cardEnabled())

        mask = ColorOsBatteryFlags.BIT_DTO or ColorOsBatteryFlags.BIT_CARD
        reader = reader()
        assertTrue(reader.dtoEnabled())
        assertTrue(reader.cardEnabled())
    }

    @Test
    fun `reads are cached inside the ttl`() {
        val reader = reader(ttlMs = 3_000)
        reader.mask()
        reader.mask()
        reader.mask()
        assertEquals(1, reads)

        now += 2_999
        reader.mask()
        assertEquals(1, reads)

        // 过期后重新读，改设置几秒内生效。
        now += 2
        mask = ColorOsBatteryFlags.BIT_DTO
        assertEquals(ColorOsBatteryFlags.BIT_DTO, reader.mask())
        assertEquals(2, reads)
    }

    @Test
    fun `a failing read keeps the last known value`() {
        mask = ColorOsBatteryFlags.BIT_DTO
        val reader = reader(ttlMs = 0)
        assertTrue(reader.dtoEnabled())

        failing = true
        now += 1
        assertTrue(reader.dtoEnabled())
    }

    @Test
    fun `reset forces the next read`() {
        mask = ColorOsBatteryFlags.BIT_DTO
        val reader = reader()
        assertTrue(reader.dtoEnabled())
        reader.reset()
        mask = 0
        assertFalse(reader.dtoEnabled())
        assertEquals(2, reads)
    }
}

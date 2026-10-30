package moe.chenxy.huaweipods.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主多段电量块（AirPods 三格布局）的展示决策。这些值直接决定卡片上四格数字与图标，
 * 是 `MULTI` 电量唯一纯逻辑的部分，所以在这里锁死。
 */
class ColorOsAirpodsBatteryBlockTest {
    @Test
    fun `labels coerce and clamp`() {
        assertEquals("100%", ColorOsAirpodsBatteryBlock.label(100))
        assertEquals("0%", ColorOsAirpodsBatteryBlock.label(0))
        assertEquals("100%", ColorOsAirpodsBatteryBlock.label(140))
        assertEquals("0%", ColorOsAirpodsBatteryBlock.label(-5))
        assertEquals("--", ColorOsAirpodsBatteryBlock.label(null))
    }

    @Test
    fun `progress is never null and stays in range`() {
        assertEquals(0, ColorOsAirpodsBatteryBlock.progress(null))
        assertEquals(55, ColorOsAirpodsBatteryBlock.progress(55))
        assertEquals(100, ColorOsAirpodsBatteryBlock.progress(1000))
    }

    @Test
    fun `case takes the main slot when present`() {
        val slots = ColorOsAirpodsBatteryBlock.slots(left = 90, right = 80, case = 60)
        assertEquals(60, slots.main)
        assertEquals(90, slots.left)
        assertEquals(80, slots.right)
        assertEquals(60, slots.case)
    }

    @Test
    fun `missing case falls back to the larger earbud`() {
        assertEquals(90, ColorOsAirpodsBatteryBlock.slots(90, 80, null).main)
        assertEquals(80, ColorOsAirpodsBatteryBlock.slots(null, 80, null).main)
        assertEquals(null, ColorOsAirpodsBatteryBlock.slots(null, null, null).main)
    }

    @Test
    fun `charging icon needs both a value and the flag`() {
        assertTrue(ColorOsAirpodsBatteryBlock.shouldShowCharging(50, true))
        assertFalse(ColorOsAirpodsBatteryBlock.shouldShowCharging(50, false))
        assertFalse(ColorOsAirpodsBatteryBlock.shouldShowCharging(null, true))
    }

    @Test
    fun `low icon shows at the threshold only`() {
        assertTrue(ColorOsAirpodsBatteryBlock.shouldShowLow(20))
        assertTrue(ColorOsAirpodsBatteryBlock.shouldShowLow(1))
        assertFalse(ColorOsAirpodsBatteryBlock.shouldShowLow(21))
        assertFalse(ColorOsAirpodsBatteryBlock.shouldShowLow(null))
    }
}

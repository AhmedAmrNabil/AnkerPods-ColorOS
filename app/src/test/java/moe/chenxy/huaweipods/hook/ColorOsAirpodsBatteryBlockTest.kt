package moe.chenxy.huaweipods.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主多段电量块（AirPods 三格布局）的展示决策。这些值直接决定卡片上四格数字与图标，
 * 是 `MULTI` 电量唯一纯逻辑的部分，所以在这里锁死。
 *
 * id 名来自固件 `layout/include_bluetooth_card_airpods_battery_block`（aapt2 实dump）。
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
        assertEquals(60, ColorOsAirpodsBatteryBlock.mainValue(left = 90, right = 80, case = 60))
    }

    @Test
    fun `missing case falls back to the larger earbud`() {
        assertEquals(90, ColorOsAirpodsBatteryBlock.mainValue(90, 80, null))
        assertEquals(80, ColorOsAirpodsBatteryBlock.mainValue(null, 80, null))
        assertNull(ColorOsAirpodsBatteryBlock.mainValue(null, null, null))
    }

    @Test
    fun `only present parts are rendered`() {
        val full = ColorOsAirpodsBatteryBlock.slots(90, 80, 60)
        assertEquals(
            listOf(
                ColorOsAirpodsBatteryBlock.MAIN to 60,
                ColorOsAirpodsBatteryBlock.LEFT to 90,
                ColorOsAirpodsBatteryBlock.RIGHT to 80,
                ColorOsAirpodsBatteryBlock.CASE to 60,
            ),
            full,
        )
        // 没有盒子：主段退回较大那只耳，盒子行不出现。
        val noCase = ColorOsAirpodsBatteryBlock.slots(90, 80, null)
        assertEquals(listOf(ColorOsAirpodsBatteryBlock.MAIN to 90, ColorOsAirpodsBatteryBlock.LEFT to 90, ColorOsAirpodsBatteryBlock.RIGHT to 80), noCase)
        // 只有整机单段：只剩主段（盒子行不重复画同一段）。
        assertEquals(listOf(ColorOsAirpodsBatteryBlock.MAIN to 70), ColorOsAirpodsBatteryBlock.slots(null, null, 70))
        assertEquals(listOf(ColorOsAirpodsBatteryBlock.MAIN to null), ColorOsAirpodsBatteryBlock.slots(null, null, null))
    }

    @Test
    fun `slot ids match the firmware layout`() {
        // 布局里的行容器与控件名；写错就贴不上（之前用 "${textId}_charge" 拼名字是错的）。
        assertEquals("layout_airpods_max", ColorOsAirpodsBatteryBlock.MAIN.container)
        assertEquals("airpods_electricity_text", ColorOsAirpodsBatteryBlock.MAIN.label)
        assertEquals("airpods_electricity_progress", ColorOsAirpodsBatteryBlock.MAIN.progress)
        assertEquals("airpods_max_charging_icon", ColorOsAirpodsBatteryBlock.MAIN.charge)
        assertEquals("layout_left", ColorOsAirpodsBatteryBlock.LEFT.container)
        assertEquals("left_airpods_charge", ColorOsAirpodsBatteryBlock.LEFT.charge)
        assertEquals("airpods_left_low", ColorOsAirpodsBatteryBlock.LEFT.low)
        assertEquals("layout_right", ColorOsAirpodsBatteryBlock.RIGHT.container)
        assertEquals("right_airpods_charge", ColorOsAirpodsBatteryBlock.RIGHT.charge)
        assertEquals("airpods_right_low", ColorOsAirpodsBatteryBlock.RIGHT.low)
        assertEquals("layout_box", ColorOsAirpodsBatteryBlock.CASE.container)
        assertEquals("airpods_box_battery", ColorOsAirpodsBatteryBlock.CASE.label)
        assertEquals("airpods_box_charge", ColorOsAirpodsBatteryBlock.CASE.charge)
        assertEquals("airpods_box_low", ColorOsAirpodsBatteryBlock.CASE.low)
        assertEquals("airpods_image", ColorOsAirpodsBatteryBlock.PRODUCT_IMAGE)
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

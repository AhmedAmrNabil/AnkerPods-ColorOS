package moe.chenxy.huaweipods.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备空间首页卡片三段浮层的纯逻辑：文本拼接与卡片/耳机配对。
 *
 * 这一层必须独立于视图：真机上卡片里只有设备名、没有地址，配对错了就会把别的设备电量写到耳机卡上，
 * 所以"单段不显示"和"名字空就不匹配"这两条要钉死。
 */
class ColorOsHomeCardBatteryTest {

    @Test
    fun `three parts join with separator`() {
        assertEquals("L100 R98 C93", ColorOsHomeCardBattery.textFor(100, 98, 93))
    }

    @Test
    fun `two parts keep order left right case`() {
        assertEquals("L100 C93", ColorOsHomeCardBattery.textFor(100, null, 93))
        assertEquals("R98 C93", ColorOsHomeCardBattery.textFor(null, 98, 93))
        assertEquals("L100 R98", ColorOsHomeCardBattery.textFor(100, 98, null))
    }

    @Test
    fun `single part returns null so host keeps its own number`() {
        assertNull(ColorOsHomeCardBattery.textFor(100, null, null))
        assertNull(ColorOsHomeCardBattery.textFor(null, null, 93))
        assertNull(ColorOsHomeCardBattery.textFor(null, null, null))
    }

    @Test
    fun `zero percent still renders`() {
        assertEquals("L0 C5", ColorOsHomeCardBattery.textFor(0, null, 5))
    }

    @Test
    fun `card name matches broadcast name in both directions`() {
        assertTrue(ColorOsHomeCardBattery.matches("FreeClip", "HUAWEI FreeClip"))
        assertTrue(ColorOsHomeCardBattery.matches("HUAWEI FreeClip", "FreeClip"))
        assertTrue(ColorOsHomeCardBattery.matches("HUAWEI FreeClip", "HUAWEI FreeClip"))
    }

    @Test
    fun `matching ignores case spaces and punctuation`() {
        assertTrue(ColorOsHomeCardBattery.matches("free-clip", "HUAWEI  FreeClip"))
        assertTrue(ColorOsHomeCardBattery.matches("FREECLIP", "huawei freeclip"))
    }

    @Test
    fun `unrelated names do not match`() {
        assertFalse(ColorOsHomeCardBattery.matches("FreeClip", "FreeBuds Pro"))
        assertFalse(ColorOsHomeCardBattery.matches("OPPO Pad Mini", "HUAWEI FreeClip"))
    }

    @Test
    fun `blank names never match`() {
        assertFalse(ColorOsHomeCardBattery.matches(null, "HUAWEI FreeClip"))
        assertFalse(ColorOsHomeCardBattery.matches("FreeClip", null))
        assertFalse(ColorOsHomeCardBattery.matches("", ""))
        assertFalse(ColorOsHomeCardBattery.matches("   ", "FreeClip"))
    }

    @Test
    fun `device id is md5 of the uppercase mac`() {
        // 真机向量：HomeCardView.j / .s 上抓到的就是这个值。
        assertEquals(
            "f68a5d82181e48edd39eb1824f43c1fa",
            ColorOsHomeCardBattery.deviceIdFor("30:A9:98:CB:C4:54"),
        )
    }

    @Test
    fun `device id normalizes case and surrounding spaces`() {
        val expected = "f68a5d82181e48edd39eb1824f43c1fa"
        assertEquals(expected, ColorOsHomeCardBattery.deviceIdFor("30:a9:98:cb:c4:54"))
        assertEquals(expected, ColorOsHomeCardBattery.deviceIdFor("  30:A9:98:CB:C4:54  "))
    }

    @Test
    fun `device id is null for blank address`() {
        assertNull(ColorOsHomeCardBattery.deviceIdFor(null))
        assertNull(ColorOsHomeCardBattery.deviceIdFor(""))
        assertNull(ColorOsHomeCardBattery.deviceIdFor("   "))
    }

    @Test
    fun `card id matches the same device and rejects others`() {
        val cardId = "f68a5d82181e48edd39eb1824f43c1fa"
        assertTrue(ColorOsHomeCardBattery.matchesDeviceId(cardId, "30:A9:98:CB:C4:54"))
        assertTrue(ColorOsHomeCardBattery.matchesDeviceId("F68A5D82181E48EDD39EB1824F43C1FA", "30:A9:98:CB:C4:54"))
        assertFalse(ColorOsHomeCardBattery.matchesDeviceId(cardId, "30:A9:98:CB:C4:55"))
        assertFalse(ColorOsHomeCardBattery.matchesDeviceId(null, "30:A9:98:CB:C4:54"))
        assertFalse(ColorOsHomeCardBattery.matchesDeviceId(cardId, null))
    }
}

package moe.chenxy.huaweipods.hook

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorOsPodBatteryStoreTest {

    @After
    fun tearDown() {
        ColorOsPodBatteryStore.clear(null)
    }

    private fun pod(
        address: String = "30:A9:98:CB:C4:54",
        left: Int = 100,
        leftConnected: Boolean = true,
        right: Int = 90,
        rightConnected: Boolean = true,
        case: Int = 50,
        caseConnected: Boolean = true,
    ) = ColorOsPodBattery(address, left, leftConnected, right, rightConnected, case, caseConnected)

    private fun triple(pod: ColorOsPodBattery): ColorOsBatteryTriple =
        requireNotNull(colorOsBatteryTriple(pod))

    @Test
    fun `addresses are normalized to uppercase`() {
        ColorOsPodBatteryStore.put(pod(address = " 30:a9:98:cb:c4:54 "))
        assertEquals("30:A9:98:CB:C4:54", ColorOsPodBatteryStore.get("30:a9:98:cb:c4:54")?.address)
        assertEquals(1, ColorOsPodBatteryStore.size())
    }

    @Test
    fun `blank address is ignored and lookups fail closed`() {
        ColorOsPodBatteryStore.put(pod(address = "   "))
        assertEquals(0, ColorOsPodBatteryStore.size())
        assertNull(ColorOsPodBatteryStore.get(null))
        assertNull(ColorOsPodBatteryStore.get(""))
        assertNull(ColorOsPodBatteryStore.get("11:22:33:44:55:66"))
    }

    @Test
    fun `newest write wins and clear removes a single device`() {
        ColorOsPodBatteryStore.put(pod(left = 100))
        ColorOsPodBatteryStore.put(pod(left = 42))
        assertEquals(42, ColorOsPodBatteryStore.get("30:A9:98:CB:C4:54")?.left ?: -1)

        ColorOsPodBatteryStore.put(pod(address = "AA:BB:CC:DD:EE:FF"))
        ColorOsPodBatteryStore.clear("30:a9:98:cb:c4:54")
        assertNull(ColorOsPodBatteryStore.get("30:A9:98:CB:C4:54"))
        assertEquals(1, ColorOsPodBatteryStore.size())
    }

    @Test
    fun `clear without address drops every device`() {
        ColorOsPodBatteryStore.put(pod())
        ColorOsPodBatteryStore.put(pod(address = "AA:BB:CC:DD:EE:FF"))
        ColorOsPodBatteryStore.clear(null)
        assertEquals(0, ColorOsPodBatteryStore.size())
    }

    @Test
    fun `triple keeps whatever part is online`() {
        assertNull(colorOsBatteryTriple(null))
        // 一只耳在盒里不报电量：剩下的段照样能用，缺的段是 null。
        val onlyRight = triple(pod(leftConnected = false))
        assertNull(onlyRight.left)
        assertEquals(90, onlyRight.right)
        assertEquals(50, onlyRight.case ?: -1)
        assertTrue(onlyRight.isMulti)

        val onlyLeft = triple(pod(rightConnected = false, caseConnected = false))
        assertEquals(100, onlyLeft.left)
        assertNull(onlyLeft.right)
        assertNull(onlyLeft.case)
        assertFalse(onlyLeft.isMulti)

        // 全场离线才作废。
        assertNull(colorOsBatteryTriple(pod(leftConnected = false, rightConnected = false, caseConnected = false)))
    }

    @Test
    fun `isMulti needs at least two parts`() {
        assertTrue(triple(pod()).isMulti)
        assertTrue(triple(pod(caseConnected = false)).isMulti)
        assertFalse(triple(pod(rightConnected = false, caseConnected = false)).isMulti)
    }

    @Test
    fun `triple omits the case when the box is offline`() {
        val triple = triple(pod(caseConnected = false, case = 0))
        assertNull(triple.case)
        assertEquals(100, triple.main)
        assertEquals(90, triple.minimum)
    }

    @Test
    fun `percentages are clamped into zero to one hundred`() {
        val triple = triple(pod(left = 130, right = -3, case = 250))
        assertEquals(100, triple.left)
        assertEquals(0, triple.right)
        assertEquals(100, triple.case ?: -1)
    }

    @Test
    fun `minimum uses the box when it is the lowest part`() {
        val triple = triple(pod(left = 100, right = 95, case = 20))
        assertEquals(20, triple.minimum)
        assertEquals("BOX", triple.minPart.channel)
        assertEquals(100, triple.main)
        assertEquals("LEFT", triple.mainPart.channel)
    }

    @Test
    fun `main and minimum parts carry the matching battery channel`() {
        val triple = triple(pod(left = 60, right = 96, case = 40))
        assertEquals(listOf("LEFT", "RIGHT", "BOX"), triple.parts.map { it.channel })
        assertEquals(ColorOsBatteryPart("RIGHT", 96), triple.mainPart)
        assertEquals(ColorOsBatteryPart("BOX", 40), triple.minPart)
    }

    @Test
    fun `ties resolve in left right box order`() {
        val triple = triple(pod(left = 80, right = 80, case = 80))
        assertEquals("LEFT", triple.mainPart.channel)
        assertEquals("LEFT", triple.minPart.channel)
    }
}

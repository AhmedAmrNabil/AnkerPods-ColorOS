package moe.chenxy.huaweipods.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorOsBluetoothTileTextTest {
    private val pod = ColorOsPodBattery("30:A9:98:CB:C4:54", 100, true, 99, true, 88, true)

    private fun rewrite(
        raw: String? = "Connected",
        enabled: Boolean = true,
        connected: Boolean = true,
        addresses: List<String?> = listOf(pod.address),
        battery: ColorOsPodBattery? = pod,
    ) = rewriteBluetoothTileSubtitle(raw, enabled, connected, addresses, battery, chinese = false)

    @Test
    fun `unique connected address gets all available channels`() {
        assertEquals("L 100% R 99% Case 88%", rewrite())
        assertEquals("L 100% R 99% Case 88%", rewrite(addresses = listOf(pod.address.lowercase())))
    }

    @Test
    fun `stock percentage suffixes are accepted in Chinese and English`() {
        assertEquals("L 100% R 99% Case 88%", rewrite("100% 的电量"))
        assertEquals("L 100% R 99% Case 88%", rewrite("100% battery"))
        assertNull(rewrite("Connection failed, 100% battery"))
    }

    @Test
    fun `disabled disconnected or transitional tiles preserve host text`() {
        assertNull(rewrite(enabled = false))
        assertNull(rewrite(connected = false))
        for (raw in listOf(null, "", "Connecting", "Connection failed", "Disconnected", "Off", "正在连接", "连接失败")) {
            assertNull(rewrite(raw))
        }
    }

    @Test
    fun `missing ambiguous or other device identity never consumes cached battery`() {
        assertNull(rewrite(addresses = emptyList()))
        assertNull(rewrite(addresses = listOf(null)))
        assertNull(rewrite(addresses = listOf("")))
        assertNull(rewrite(addresses = listOf("AA:BB:CC:DD:EE:FF")))
        assertNull(rewrite(addresses = listOf(pod.address, "AA:BB:CC:DD:EE:FF")))
        assertNull(rewrite(battery = null))
    }

    @Test
    fun `single channel stays stock and zero remains a valid value`() {
        assertNull(rewrite(battery = pod.copy(rightConnected = false, caseConnected = false)))
        assertEquals("L 0% R 99%", rewrite(battery = pod.copy(left = 0, caseConnected = false)))
    }

    @Test
    fun `accessibility updates only the confirmed subtitle segment`() {
        val replacement = "L 100% R 99% Case 88%"
        assertEquals(replacement, rewriteBluetoothTileDescription("Connected", "Connected", replacement))
        assertEquals("Bluetooth FreeClip, $replacement", rewriteBluetoothTileDescription("Bluetooth FreeClip, Connected", "Connected", replacement))
        assertEquals("Connected Headset, $replacement", rewriteBluetoothTileDescription("Connected Headset, Connected", "Connected", replacement))
        assertNull(rewriteBluetoothTileDescription("Connected Headset", "Connected", replacement))
        assertNull(rewriteBluetoothTileDescription("Bluetooth, Connecting", "Connected", replacement))
        assertNull(rewriteBluetoothTileDescription(null, "Connected", replacement))
    }
}

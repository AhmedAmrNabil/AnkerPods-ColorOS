package moe.chenxy.huaweipods.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorOsBatterySnapshotTest {

    private val pod = ColorOsPodBattery(
        address = "30:a9:98:cb:c4:54",
        left = 88,
        leftConnected = true,
        right = 92,
        rightConnected = true,
        case = 100,
        caseConnected = true,
        name = "HUAWEI FreeClip",
    )

    @Test
    fun `round trips a full snapshot`() {
        val decoded = ColorOsBatterySnapshot.decode(ColorOsBatterySnapshot.encode(pod))
        assertEquals("30:A9:98:CB:C4:54", decoded?.address)
        assertEquals(88, decoded?.left)
        assertEquals(92, decoded?.right)
        assertEquals(100, decoded?.case)
        assertEquals(true, decoded?.leftConnected)
        assertEquals(true, decoded?.rightConnected)
        assertEquals(true, decoded?.caseConnected)
        assertEquals("HUAWEI FreeClip", decoded?.name)
    }

    @Test
    fun `keeps disconnected parts disconnected`() {
        val half = pod.copy(rightConnected = false, caseConnected = false, case = 0, name = null)
        val decoded = ColorOsBatterySnapshot.decode(ColorOsBatterySnapshot.encode(half))
        assertEquals(88, decoded?.left)
        assertTrue(decoded?.leftConnected == true)
        assertFalse(decoded?.rightConnected == true)
        assertFalse(decoded?.caseConnected == true)
        assertNull(decoded?.name)
    }

    @Test
    fun `strips separators out of the device name`() {
        val weird = pod.copy(name = "Free|Clip|2")
        val decoded = ColorOsBatterySnapshot.decode(ColorOsBatterySnapshot.encode(weird))
        assertEquals("Free Clip 2", decoded?.name)
        assertEquals(100, decoded?.case)
    }

    @Test
    fun `rejects malformed snapshots instead of inventing values`() {
        assertNull(ColorOsBatterySnapshot.decode(null))
        assertNull(ColorOsBatterySnapshot.decode(""))
        assertNull(ColorOsBatterySnapshot.decode("30:A9:98:CB:C4:54|88|1|92"))
        assertNull(ColorOsBatterySnapshot.decode("|88|1|92|1|100|1|name"))
        assertNull(ColorOsBatterySnapshot.decode("30:A9:98:CB:C4:54|x|1|92|1|100|1|name"))
    }

    @Test
    fun `disconnect clears only the matching snapshot including lower case addresses`() {
        var persisted: String? = ColorOsBatterySnapshot.encode(pod)
        assertFalse(ColorOsBatterySnapshot.clearIfMatches("AA:BB:CC:DD:EE:FF", { persisted }) { persisted = it; true })
        assertEquals(pod.address.uppercase(), ColorOsBatterySnapshot.decode(persisted)?.address)
        assertTrue(ColorOsBatterySnapshot.clearIfMatches(pod.address, { persisted }) { persisted = it; true })
        assertNull(persisted)
    }

    @Test
    fun `invalid addresses or snapshots never issue a clear write`() {
        var writes = 0
        val write: (String?) -> Boolean = { writes++; true }
        assertFalse(ColorOsBatterySnapshot.clearIfMatches("", { ColorOsBatterySnapshot.encode(pod) }, write))
        assertFalse(ColorOsBatterySnapshot.clearIfMatches(pod.address, { "malformed" }, write))
        assertFalse(ColorOsBatterySnapshot.clearIfMatches(pod.address, { null }, write))
        assertEquals(0, writes)
    }

    @Test
    fun `failed storage write is not reported as a successful clear`() {
        assertFalse(ColorOsBatterySnapshot.clearIfMatches(pod.address, { ColorOsBatterySnapshot.encode(pod) }) { false })
    }
}

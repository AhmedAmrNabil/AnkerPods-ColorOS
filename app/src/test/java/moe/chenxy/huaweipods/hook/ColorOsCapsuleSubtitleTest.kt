package moe.chenxy.huaweipods.hook

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sun.misc.Unsafe

class ColorOsCapsuleSubtitleTest {
    private val pod = ColorOsPodBattery("30:A9:98:CB:C4:54", 100, true, 100, true, 88, true, "HUAWEI FreeClip")
    private val reader = ColorOsCapsuleSubtitle(Capsule::class.java)

    private enum class Kind { CONNECTED, LOW_BATTERY }
    private class Capsule(
        val mac: String,
        val title: String,
        val subTitle: String,
        val iconUri: String,
        val capsuleType: Kind,
    ) {
        init { constructions++ }
        companion object { var constructions = 0 }
    }

    @After
    fun clearStore() {
        ColorOsPodBatteryStore.clear(null)
    }

    @Test
    fun `read path supports objects allocated without a constructor and keeps other fields`() {
        ColorOsPodBatteryStore.put(pod)
        val before = Capsule.constructions
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        val capsule = unsafe.allocateInstance(Capsule::class.java) as Capsule
        val fields = mapOf(
            "mac" to pod.address,
            "title" to "FreeClip 100% 电量版",
            "subTitle" to "Connected",
            "iconUri" to "content://icons/FreeClip%20Blue",
            "capsuleType" to Kind.CONNECTED,
        )
        fields.forEach { (name, value) ->
            Capsule::class.java.getDeclaredField(name).apply { isAccessible = true }.set(capsule, value)
        }
        assertEquals(before, Capsule.constructions)
        assertEquals("L 100% R 100% Case 88%", reader.rewrite(capsule, reader.getter.invoke(capsule) as String, false))
        assertEquals("Connected", capsule.subTitle)
        assertEquals(fields["title"], capsule.title)
        assertEquals(fields["iconUri"], capsule.iconUri)
    }

    @Test
    fun `address chooses second earbuds even when first record has more channels`() {
        val second = pod.copy(address = "AA:BB:CC:DD:EE:FF", left = 12, right = 34, caseConnected = false)
        ColorOsPodBatteryStore.put(pod)
        ColorOsPodBatteryStore.put(second)
        val capsule = Capsule(second.address, pod.name!!, "已连接", "icon", Kind.CONNECTED)
        assertEquals("左 12% 右 34%", reader.rewrite(capsule, capsule.subTitle, true))
    }

    @Test
    fun `same name with another MAC or a low battery type stays original`() {
        ColorOsPodBatteryStore.put(pod)
        val other = Capsule("AA:BB:CC:DD:EE:FF", pod.name!!, "已连接", "icon", Kind.CONNECTED)
        assertNull(reader.rewrite(other, other.subTitle, true))
        val low = Capsule(pod.address, pod.name!!, "10%", "icon", Kind.LOW_BATTERY)
        assertNull(reader.rewrite(low, low.subTitle, true))
    }
}

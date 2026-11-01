package moe.chenxy.huaweipods.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorOsWirelessSettingsHookTest {
    @Test
    fun `defaults to built-in vendor list when property is null or empty`() {
        assertEquals(
            listOf(2688, 1356, 301, 2016, 3686),
            colorOsSettingsThirdPartyVendors(null),
        )
        assertEquals(
            listOf(2688, 1356, 301, 2016, 3686),
            colorOsSettingsThirdPartyVendors("   "),
        )
    }

    @Test
    fun `parses comma-separated hex vendor list from system property`() {
        assertEquals(
            listOf(0x0A80, 0x054C),
            colorOsSettingsThirdPartyVendors("a80, 54c"),
        )
    }
}

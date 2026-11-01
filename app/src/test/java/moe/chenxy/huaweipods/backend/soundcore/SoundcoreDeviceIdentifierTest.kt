package moe.chenxy.huaweipods.backend.soundcore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundcoreDeviceIdentifierTest {

    @Test
    fun matchesSoundcoreLiberty4NcByName() {
        assertTrue(SoundcoreDeviceIdentifier.isLiberty4Nc("Soundcore Liberty 4 NC"))
        assertTrue(SoundcoreDeviceIdentifier.isLiberty4Nc("soundcore liberty 4 nc"))
        assertTrue(SoundcoreDeviceIdentifier.isLiberty4Nc("soundcore a3947"))
        assertTrue(SoundcoreDeviceIdentifier.isLiberty4Nc("soundcore-liberty-4-nc"))
        assertTrue(SoundcoreDeviceIdentifier.isLiberty4Nc("Anker Soundcore A3947"))
    }

    @Test
    fun doesNotMatchUnrelatedDevices() {
        assertFalse(SoundcoreDeviceIdentifier.isLiberty4Nc("HUAWEI FreeBuds Pro 3"))
        assertFalse(SoundcoreDeviceIdentifier.isLiberty4Nc("Sony WH-1000XM4"))
        assertFalse(SoundcoreDeviceIdentifier.isLiberty4Nc(null))
        assertFalse(SoundcoreDeviceIdentifier.isLiberty4Nc(""))
    }
}

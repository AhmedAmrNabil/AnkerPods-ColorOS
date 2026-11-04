package moe.chenxy.huaweipods.backend.soundcore

import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import org.junit.Assert.assertEquals
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
    fun matchesSoundcoreLiberty3ProByName() {
        assertTrue(SoundcoreDeviceIdentifier.isLiberty3Pro("Soundcore Liberty 3 Pro"))
        assertTrue(SoundcoreDeviceIdentifier.isLiberty3Pro("soundcore liberty 3 pro"))
        assertTrue(SoundcoreDeviceIdentifier.isLiberty3Pro("soundcore a3952"))
        assertTrue(SoundcoreDeviceIdentifier.isLiberty3Pro("soundcore-liberty-3-pro"))
        assertTrue(SoundcoreDeviceIdentifier.isLiberty3Pro("Anker Soundcore A3952"))
        assertTrue(SoundcoreDeviceIdentifier.isLiberty3Pro("Liberty 3 Pro"))
        assertFalse(SoundcoreDeviceIdentifier.isLiberty3Pro("Soundcore Liberty 4 NC"))
    }

    @Test
    fun identifiesSoundcoreName() {
        assertTrue(SoundcoreDeviceIdentifier.isSoundcoreName("Soundcore Liberty 4 NC"))
        assertTrue(SoundcoreDeviceIdentifier.isSoundcoreName("Soundcore Liberty 3 Pro"))
        assertTrue(SoundcoreDeviceIdentifier.isSoundcoreName("Soundcore Earbuds"))
        assertFalse(SoundcoreDeviceIdentifier.isSoundcoreName("HUAWEI FreeBuds Pro 3"))
        assertFalse(SoundcoreDeviceIdentifier.isSoundcoreName(null))
    }

    @Test
    fun resolvesRouteAndModelCorrectly() {
        assertEquals(
            HuaweiDeviceRoute.SOUNDCORE_LIBERTY_3_PRO,
            SoundcoreDeviceIdentifier.resolveRoute("Soundcore Liberty 3 Pro"),
        )
        assertEquals(
            HuaweiDeviceRoute.SOUNDCORE_LIBERTY_4_NC,
            SoundcoreDeviceIdentifier.resolveRoute("Soundcore Liberty 4 NC"),
        )
        assertEquals(
            SoundcoreModel.A3952,
            SoundcoreDeviceIdentifier.resolveModel(HuaweiDeviceRoute.SOUNDCORE_LIBERTY_3_PRO),
        )
        assertEquals(
            SoundcoreModel.A3947,
            SoundcoreDeviceIdentifier.resolveModel(HuaweiDeviceRoute.SOUNDCORE_LIBERTY_4_NC),
        )
        assertEquals(
            SoundcoreModel.A3952,
            SoundcoreDeviceIdentifier.resolveModel(null, "Soundcore Liberty 3 Pro"),
        )
    }

    @Test
    fun doesNotMatchUnrelatedDevices() {
        assertFalse(SoundcoreDeviceIdentifier.isLiberty4Nc("HUAWEI FreeBuds Pro 3"))
        assertFalse(SoundcoreDeviceIdentifier.isLiberty4Nc("Sony WH-1000XM4"))
        assertFalse(SoundcoreDeviceIdentifier.isLiberty4Nc(null))
        assertFalse(SoundcoreDeviceIdentifier.isLiberty4Nc(""))
        assertFalse(SoundcoreDeviceIdentifier.isLiberty3Pro(null))
        assertFalse(SoundcoreDeviceIdentifier.isLiberty3Pro(""))
    }
}

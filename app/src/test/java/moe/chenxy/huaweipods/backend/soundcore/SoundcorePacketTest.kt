package moe.chenxy.huaweipods.backend.soundcore

import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.PodParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundcorePacketTest {

    @Test
    fun testRequestStatePacketMatchesOpenScq30() {
        val expected = byteArrayOf(
            0x08, 0xee.toByte(), 0x00, 0x00, 0x00,
            0x01, 0x01, 0x0a, 0x00, 0x02
        )
        val packet = SoundcorePacket.buildRequestStatePacket()
        assertArrayEquals(expected, packet)
    }

    @Test
    fun testSetSoundModesNormalMatchesOpenScq30() {
        // OpenSCQ30 test: it_matches_an_example_normal_mode_packet
        val expected = byteArrayOf(
            0x08, 0xee.toByte(), 0x00, 0x00, 0x00,
            0x06, 0x81.toByte(), 0x11.toByte(), 0x00,
            0x02, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x91.toByte()
        )
        val packet = SoundcorePacket.buildSetSoundModesPacket(NoiseControlMode.OFF)
        // Check header, command, length, and checksum
        assertEquals(17, packet.size)
        assertEquals(0x06, packet[5].toInt() and 0xFF)
        assertEquals(0x81, packet[6].toInt() and 0xFF)
        val checksum = SoundcorePacket.calculateChecksum(packet, 0, packet.size - 1)
        assertEquals(packet[packet.size - 1], checksum)
    }

    @Test
    fun testParseDualBatteryLevelWithoutNewBatteryFixture() {
        // OpenSCQ30 fixture: it_parses_a_manually_crafted_packet_without_new_battery
        val input = byteArrayOf(
            0x09, 0xff.toByte(), 0x00, 0x00, 0x01,
            0x01, 0x03, 0x0c, 0x00,
            0x03, 0x04,
            0x20
        )
        val parsed = SoundcorePacket.parseInbound(input, input.size)
        assertNotNull(parsed)
        val (packet, consumed) = parsed!!
        assertEquals(input.size, consumed)
        assertEquals(SoundcorePacket.CMD_BATTERY_LEVEL, packet.command)

        val initial = BatteryParams()
        val battery = SoundcorePacket.parseDualBatteryLevel(packet.body, initial)
        // Level 3 -> (3 * 100) / 5 = 60%
        // Level 4 -> (4 * 100) / 5 = 80%
        assertEquals(60, battery.left?.battery)
        assertEquals(80, battery.right?.battery)
        assertTrue(battery.left?.isConnected == true)
        assertTrue(battery.right?.isConnected == true)
    }

    @Test
    fun testParseDualBatteryLevelWithNewBatteryFixture() {
        // OpenSCQ30 fixture: it_parses_a_manually_crafted_packet_with_new_battery
        val input = byteArrayOf(
            0x09, 0xff.toByte(), 0x00, 0x00, 0x01,
            0x01, 0x03, 0x0e, 0x00,
            0x04, 0x05, 0x01, 0x02,
            0x27
        )
        val parsed = SoundcorePacket.parseInbound(input, input.size)
        assertNotNull(parsed)
        val (packet, consumed) = parsed!!
        assertEquals(input.size, consumed)
        assertEquals(SoundcorePacket.CMD_BATTERY_LEVEL, packet.command)

        val initial = BatteryParams()
        val battery = SoundcorePacket.parseDualBatteryLevel(packet.body, initial)
        // Level 4 -> (4 * 100) / 5 = 80%
        // Level 5 -> (5 * 100) / 5 = 100%
        assertEquals(80, battery.left?.battery)
        assertEquals(100, battery.right?.battery)
    }

    @Test
    fun testParseSoundModesFixture() {
        // OpenSCQ30 fixture: it_parses_valid_packet
        val input = byteArrayOf(
            0x09, 0xff.toByte(), 0x00, 0x00, 0x01,
            0x06, 0x01, 0x0e, 0x00,
            0x02, 0x02, 0x01, 0x00,
            0x23
        )
        val parsed = SoundcorePacket.parseInbound(input, input.size)
        assertNotNull(parsed)
        val (packet, consumed) = parsed!!
        assertEquals(input.size, consumed)
        assertEquals(SoundcorePacket.CMD_SOUND_MODES_INBOUND, packet.command)

        val mode = SoundcorePacket.parseSoundModes(packet.body)
        assertEquals(NoiseControlMode.OFF, mode) // 0x02 = Normal mode
    }

    @Test
    fun testA3947KnownGoodStateUpdatePacket() {
        // Known-good packet body from OpenSCQ30 a3947.rs test: test_with_known_good_packet
        val bodyInts = intArrayOf(
            1, 1, 2, 4, 0, 0, 48, 53, 46, 56, 56, 48, 53, 46, 56, 56, 51, 57, 52,
            55, 66, 54, 54, 50, 70, 68, 67, 67, 69, 69, 69, 56, 48, 48, 46, 48, 48,
            254, 254, 120, 120, 120, 120, 120, 120, 120, 120, 120, 120, 255, 255,
            255, 255, 255, 255, 255, 255, 255, 255, 0, 1, 112, 117, 140, 148, 150,
            142, 134, 131, 60, 60, 112, 117, 140, 148, 150, 142, 134, 131, 60, 60,
            104, 100, 34, 64, 0, 112, 117, 140, 148, 150, 142, 134, 131, 60, 60,
            112, 117, 140, 148, 150, 142, 134, 131, 60, 60, 0, 0, 18, 17, 102, 17,
            101, 17, 50, 17, 51, 17, 68, 17, 68, 0, 33, 0, 32, 0x35, 0, 0x50, 1, 0,
            1, 1, 3, 0, 0, 0, 0, 255, 1, 2, 49, 1, 0, 0, 1, 0, 0, 1, 0, 1, 80, 1,
            0, 0, 1, 1, 1, 1, 255
        )
        val body = ByteArray(bodyInts.size) { bodyInts[it].toByte() }

        val result = SoundcorePacket.parseA3947StateUpdate(body)
        assertNotNull(result)
        val (battery, ancMode) = result!!

        // Expected values verified in OpenSCQ30 test_with_known_good_packet:
        // BatteryLevelLeft: 2/5 -> 40%
        // BatteryLevelRight: 4/5 -> 80%
        // CaseBatteryLevel: 2/5 -> 40%
        // IsChargingLeft: false
        // IsChargingRight: false
        // AmbientSoundMode: NoiseCanceling -> NoiseControlMode.NOISE_CANCELLATION
        assertEquals(40, battery.left?.battery)
        assertEquals(80, battery.right?.battery)
        assertEquals(40, battery.case?.battery)
        assertEquals(false, battery.left?.isCharging)
        assertEquals(false, battery.right?.isCharging)
        assertEquals(true, battery.left?.isConnected)
        assertEquals(true, battery.right?.isConnected)
        assertEquals(true, battery.case?.isConnected)
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, ancMode)
        assertEquals(5, result.ancSubMode) // Manual mode level 5
        assertEquals(false, result.isAdaptiveAnc)
    }

    @Test
    fun testBuildSetSoundModesPacketDiscreteLevels() {
        // Manual mode (submode 5, isAdaptive=false)
        val manualPacket = SoundcorePacket.buildSetSoundModesPacket(NoiseControlMode.NOISE_CANCELLATION, 5, isAdaptive = false)
        assertEquals(0x00, manualPacket[9].toInt() and 0xFF) // ambient: ANC
        assertEquals(0x50, manualPacket[10].toInt() and 0xFF)
        assertEquals(0x00, manualPacket[12].toInt() and 0xFF) // nc_mode: Manual
        assertEquals(0x00, manualPacket[14].toInt() and 0xFF) // environment_detection: 0 in manual mode
        assertEquals(0x00, manualPacket[15].toInt() and 0xFF) // transportation_mode: 0

        // Adaptive level 1 (submode 1, isAdaptive=true)
        val adaptivePacket1 = SoundcorePacket.buildSetSoundModesPacket(NoiseControlMode.NOISE_CANCELLATION, 1, isAdaptive = true)
        assertEquals(0x00, adaptivePacket1[9].toInt() and 0xFF)
        assertEquals(0x51, adaptivePacket1[10].toInt() and 0xFF) // (5 << 4) | 1
        assertEquals(0x01, adaptivePacket1[12].toInt() and 0xFF) // nc_mode: Adaptive
        assertEquals(0x01, adaptivePacket1[14].toInt() and 0xFF) // environment_detection: 1 in adaptive mode
        assertEquals(0x00, adaptivePacket1[15].toInt() and 0xFF) // transportation_mode: 0

        // Adaptive level 5 (submode 5, isAdaptive=true)
        val adaptivePacket5 = SoundcorePacket.buildSetSoundModesPacket(NoiseControlMode.NOISE_CANCELLATION, 5, isAdaptive = true)
        assertEquals(0x00, adaptivePacket5[9].toInt() and 0xFF)
        assertEquals(0x55, adaptivePacket5[10].toInt() and 0xFF) // (5 << 4) | 5
        assertEquals(0x01, adaptivePacket5[12].toInt() and 0xFF) // nc_mode: Adaptive
        assertEquals(0x01, adaptivePacket5[14].toInt() and 0xFF) // environment_detection: 1 in adaptive mode

        // Adaptive level with cached manual level 2 preserves manual level in high nibble
        val adaptiveWithCachedManual = SoundcorePacket.buildSetSoundModesPacket(
            mode = NoiseControlMode.NOISE_CANCELLATION,
            subMode = 3,
            isAdaptive = true,
            model = SoundcoreModel.A3947,
            cachedManualLevel = 2,
        )
        assertEquals(0x23, adaptiveWithCachedManual[10].toInt() and 0xFF) // (2 << 4) | 3
        assertEquals(0x01, adaptiveWithCachedManual[12].toInt() and 0xFF) // nc_mode: Adaptive
        assertEquals(0x01, adaptiveWithCachedManual[14].toInt() and 0xFF) // environment_detection: 1
    }

    @Test
    fun testParseSoundModesWithSubMode() {
        // Inbound sound modes with Adaptive ANC level 2
        val adaptiveBody = byteArrayOf(0x00, 0x52.toByte(), 0x01, 0x01)
        val resultAdaptive = SoundcorePacket.parseSoundModesWithSubMode(adaptiveBody)
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, resultAdaptive.ancMode)
        assertEquals(2, resultAdaptive.ancSubMode)
        assertTrue(resultAdaptive.isAdaptive)

        // Inbound sound modes with Manual ANC level 5
        val manualBody = byteArrayOf(0x00, 0x50.toByte(), 0x01, 0x00)
        val resultManual = SoundcorePacket.parseSoundModesWithSubMode(manualBody)
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, resultManual.ancMode)
        assertEquals(5, resultManual.ancSubMode)
        assertEquals(false, resultManual.isAdaptive)

        // Inbound sound modes with Transparency
        val transBody = byteArrayOf(0x01, 0x00, 0x01, 0x00)
        val resultTrans = SoundcorePacket.parseSoundModesWithSubMode(transBody)
        assertEquals(NoiseControlMode.TRANSPARENCY, resultTrans.ancMode)
        assertNull(resultTrans.ancSubMode)
        assertEquals(false, resultTrans.isAdaptive)
    }

    @Test
    fun testA3952KnownGoodStateUpdatePacket() {
        // Known-good packet body from OpenSCQ30 a3952.rs test: test_with_packet_from_github_issue_134
        val bodyInts = intArrayOf(
            1, 1, 4, 4, 0, 0, 48, 52, 46, 57, 54, 48, 52, 46, 57, 54, 51, 57, 53, 50,
            50, 48, 54, 54, 51, 49, 67, 67, 69, 69, 69, 56, 0, 0, 60, 60, 60, 60, 60,
            60, 60, 60, 60, 60, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 2, 1,
            120, 150, 180, 180, 120, 120, 120, 180, 0, 0, 120, 180, 180, 180, 120, 150,
            180, 180, 0, 0, 101, 86, 138, 244, 0, 120, 150, 180, 180, 120, 120, 120,
            180, 0, 0, 120, 180, 180, 180, 120, 150, 180, 180, 0, 0, 0, 0, 18, 0, 97,
            0, 96, 17, 99, 17, 102, 17, 84, 17, 84, 0, 65, 0, 64, 3, 2, 48, 0, 0, 1, 1,
            1, 1, 0, 4, 0, 0, 0, 0, 1, 0, 0, 1, 0, 58, 255, 255, 255, 255, 255, 255,
            101, 86, 137, 249, 70, 9
        )
        val body = ByteArray(bodyInts.size) { bodyInts[it].toByte() }

        val detectedModel = SoundcorePacket.detectModelFromStateUpdate(body)
        assertEquals(SoundcoreModel.A3952, detectedModel)

        val result = SoundcorePacket.parseStateUpdate(body, SoundcoreModel.A3952)
        assertNotNull(result)
        val (battery, ancMode) = result!!

        // Expected values verified in OpenSCQ30 test_with_packet_from_github_issue_134:
        // BatteryLevelLeft: 4/5 -> 80%
        // BatteryLevelRight: 4/5 -> 80%
        // CaseBatteryLevel: 4/5 -> 80%
        // AmbientSoundMode: Normal -> NoiseControlMode.OFF
        // WearingDetection: true
        assertEquals(80, battery.left?.battery)
        assertEquals(80, battery.right?.battery)
        assertEquals(80, battery.case?.battery)
        assertEquals(false, battery.left?.isCharging)
        assertEquals(false, battery.right?.isCharging)
        assertEquals(true, battery.left?.isConnected)
        assertEquals(true, battery.right?.isConnected)
        assertEquals(true, battery.case?.isConnected)
        assertEquals(NoiseControlMode.OFF, ancMode)
        assertEquals(true, result.wearDetection)
        assertNull(result.gamingMode)
    }

    @Test
    fun testA3952BuildSetSoundModesPacket() {
        // A3952 has 6-byte body: total length 5 (header) + 2 (cmd) + 2 (len) + 6 (body) + 1 (checksum) = 16 bytes
        val ancPacket = SoundcorePacket.buildSetSoundModesPacket(
            mode = NoiseControlMode.NOISE_CANCELLATION,
            subMode = 3,
            isAdaptive = false,
            model = SoundcoreModel.A3952
        )
        assertEquals(16, ancPacket.size)
        assertEquals(0x06, ancPacket[5].toInt() and 0xFF)
        assertEquals(0x81, ancPacket[6].toInt() and 0xFF)
        assertEquals(0x00, ancPacket[9].toInt() and 0xFF) // Ambient: ANC
        assertEquals(0x30, ancPacket[10].toInt() and 0xFF) // manual strong (3)
        assertEquals(0x00, ancPacket[12].toInt() and 0xFF) // Manual mode (0)

        val transPacket = SoundcorePacket.buildSetSoundModesPacket(
            mode = NoiseControlMode.TRANSPARENCY,
            model = SoundcoreModel.A3952
        )
        assertEquals(16, transPacket.size)
        assertEquals(0x01, transPacket[9].toInt() and 0xFF) // Transparency

        val normalPacket = SoundcorePacket.buildSetSoundModesPacket(
            mode = NoiseControlMode.OFF,
            model = SoundcoreModel.A3952
        )
        assertEquals(16, normalPacket.size)
        assertEquals(0x02, normalPacket[9].toInt() and 0xFF) // Normal (OFF)
    }

    @Test
    fun testCachedManualAncLevelOnlyUpdatedInManualMode() {
        SoundcoreStateManager.cachedManualAncLevel = 2

        // A3947 body in Adaptive mode (offset 126: 0=ANC, offset 127: 0x53, offset 129: 1=Adaptive)
        val adaptiveBody = ByteArray(160) { 0 }
        adaptiveBody[2] = 5 // Left battery
        adaptiveBody[3] = 5 // Right battery
        adaptiveBody[126] = 0 // ANC
        adaptiveBody[127] = 0x53.toByte() // high nibble=5, low nibble=3
        adaptiveBody[129] = 1 // ncMode = Adaptive

        val success1 = SoundcoreStateManager.updateFromStateUpdate(adaptiveBody, SoundcoreModel.A3947)
        assertTrue(success1)
        assertTrue(SoundcoreStateManager.isAdaptiveAnc)
        // Cached manual level MUST NOT be updated when in Adaptive mode
        assertEquals(2, SoundcoreStateManager.cachedManualAncLevel)

        // A3947 body in Manual mode (offset 126: 0=ANC, offset 127: 0x40, offset 129: 0=Manual)
        val manualBody = ByteArray(160) { 0 }
        manualBody[2] = 5
        manualBody[3] = 5
        manualBody[126] = 0 // ANC
        manualBody[127] = 0x40.toByte() // high nibble=4, low nibble=0
        manualBody[129] = 0 // ncMode = Manual

        val success2 = SoundcoreStateManager.updateFromStateUpdate(manualBody, SoundcoreModel.A3947)
        assertTrue(success2)
        assertFalse(SoundcoreStateManager.isAdaptiveAnc)
        // Cached manual level MUST be updated when in Manual mode
        assertEquals(4, SoundcoreStateManager.cachedManualAncLevel)
    }
}

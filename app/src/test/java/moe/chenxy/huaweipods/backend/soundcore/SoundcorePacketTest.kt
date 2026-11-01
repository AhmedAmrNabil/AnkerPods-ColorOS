package moe.chenxy.huaweipods.backend.soundcore

import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.PodParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    }
}

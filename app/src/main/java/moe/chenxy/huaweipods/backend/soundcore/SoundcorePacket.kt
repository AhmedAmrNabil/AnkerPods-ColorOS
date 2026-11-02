package moe.chenxy.huaweipods.backend.soundcore

import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.PodParams

data class SoundcoreInboundPacket(
    val command: Int, // 16-bit command (cmd0 or (cmd1 shl 8))
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SoundcoreInboundPacket) return false
        return command == other.command && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        return 31 * command + body.contentHashCode()
    }
}

data class SoundcoreStateUpdate(
    val battery: BatteryParams,
    val ancMode: NoiseControlMode,
    val ancSubMode: Int? = null,
)

data class SoundcoreSoundModes(
    val ancMode: NoiseControlMode,
    val ancSubMode: Int? = null,
)

object SoundcorePacket {
    val OUTBOUND_HEADER = byteArrayOf(0x08.toByte(), 0xee.toByte(), 0x00, 0x00, 0x00)
    val INBOUND_HEADER = byteArrayOf(0x09.toByte(), 0xff.toByte(), 0x00, 0x00, 0x01)

    const val CMD_REQUEST_STATE = 0x0101 // [0x01, 0x01]
    const val CMD_STATE_UPDATE = 0x0101  // [0x01, 0x01]
    const val CMD_BATTERY_LEVEL = 0x0301 // [0x01, 0x03]
    const val CMD_BATTERY_CHARGING = 0x0401 // [0x01, 0x04]
    const val CMD_SOUND_MODES_INBOUND = 0x0106 // [0x06, 0x01]
    const val CMD_SET_SOUND_MODES_OUTBOUND = 0x8106 // [0x06, 0x81]

    fun calculateChecksum(data: ByteArray, offset: Int = 0, length: Int = data.size): Byte {
        var sum = 0
        for (i in offset until (offset + length)) {
            sum = (sum + (data[i].toInt() and 0xFF)) and 0xFF
        }
        return sum.toByte()
    }

    fun buildPacket(command: ByteArray, body: ByteArray = ByteArray(0)): ByteArray {
        val totalLength = OUTBOUND_HEADER.size + command.size + 2 + body.size + 1
        val packet = ByteArray(totalLength)

        System.arraycopy(OUTBOUND_HEADER, 0, packet, 0, OUTBOUND_HEADER.size)
        var pos = OUTBOUND_HEADER.size

        packet[pos++] = command[0]
        packet[pos++] = command[1]

        packet[pos++] = (totalLength and 0xFF).toByte()
        packet[pos++] = ((totalLength shr 8) and 0xFF).toByte()

        if (body.isNotEmpty()) {
            System.arraycopy(body, 0, packet, pos, body.size)
            pos += body.size
        }

        packet[pos] = calculateChecksum(packet, 0, pos)
        return packet
    }

    fun buildRequestStatePacket(): ByteArray {
        return buildPacket(byteArrayOf(0x01, 0x01))
    }

    /**
     * Builds the SetSoundModes packet for A3947 (7-byte body).
     *
     * SoundModes body structure:
     * - byte 0: ambient_sound_mode (0 = ANC, 1 = Transparency, 2 = Normal)
     * - byte 1: (manual_nc << 4) | adaptive_nc
     * - byte 2: transparency_mode
     * - byte 3: noise_canceling_mode (0: Manual, 1: Adaptive, 2: Transportation)
     * - byte 4: wind_noise
     * - byte 5: environment_detection
     * - byte 6: transportation_mode
     */
    fun buildSetSoundModesPacket(mode: NoiseControlMode, subMode: Int? = null): ByteArray {
        val body = when (mode) {
            NoiseControlMode.NOISE_CANCELLATION -> {
                when (subMode) {
                    0 -> byteArrayOf(0x00, 0x50.toByte(), 0x01, 0x00, 0x01, 0x01, 0x03) // Manual (nc_mode = 0, manual = 5)
                    in 1..5 -> byteArrayOf(
                        0x00,
                        ((0x5 shl 4) or subMode!!).toByte(),
                        0x01,
                        0x01, // Adaptive (nc_mode = 1, adaptive = subMode)
                        0x01,
                        0x01,
                        0x00
                    )
                    else -> byteArrayOf(0x00, 0x53.toByte(), 0x01, 0x01, 0x01, 0x01, 0x00) // Default Adaptive 3
                }
            }
            NoiseControlMode.TRANSPARENCY -> byteArrayOf(
                0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00
            )
            NoiseControlMode.OFF -> byteArrayOf(
                0x02, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00
            )
            NoiseControlMode.UNKNOWN -> byteArrayOf(
                0x02, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00
            )
        }
        return buildPacket(byteArrayOf(0x06, 0x81.toByte()), body)
    }

    /**
     * Attempts to parse an inbound packet from a buffer starting at offset 0.
     * Returns a pair of (parsed packet, consumed bytes count) or null if buffer is incomplete.
     */
    fun parseInbound(buffer: ByteArray, length: Int): Pair<SoundcoreInboundPacket, Int>? {
        if (length < 10) return null

        // Look for inbound header: 0x09, 0xff, 0x00, 0x00, 0x01
        var headerIndex = -1
        for (i in 0..(length - 10)) {
            if (buffer[i] == INBOUND_HEADER[0] &&
                buffer[i + 1] == INBOUND_HEADER[1] &&
                buffer[i + 2] == INBOUND_HEADER[2] &&
                buffer[i + 3] == INBOUND_HEADER[3] &&
                buffer[i + 4] == INBOUND_HEADER[4]
            ) {
                headerIndex = i
                break
            }
        }

        if (headerIndex == -1) return null

        val packetStart = headerIndex
        val remaining = length - packetStart
        if (remaining < 10) return null

        val cmd0 = buffer[packetStart + 5].toInt() and 0xFF
        val cmd1 = buffer[packetStart + 6].toInt() and 0xFF
        val command = cmd0 or (cmd1 shl 8)

        val packetLength = (buffer[packetStart + 7].toInt() and 0xFF) or
                ((buffer[packetStart + 8].toInt() and 0xFF) shl 8)

        if (packetLength < 10 || packetLength > 1024) {
            // Invalid length, skip header
            return null
        }

        if (remaining < packetLength) {
            // Incomplete packet in buffer
            return null
        }

        // Validate checksum
        val expectedChecksum = buffer[packetStart + packetLength - 1]
        val actualChecksum = calculateChecksum(buffer, packetStart, packetLength - 1)
        if (expectedChecksum != actualChecksum) {
            // Checksum failed, corrupt packet
            return null
        }

        val bodyLength = packetLength - 10
        val body = ByteArray(bodyLength)
        if (bodyLength > 0) {
            System.arraycopy(buffer, packetStart + 9, body, 0, bodyLength)
        }

        val consumed = packetStart + packetLength
        return Pair(SoundcoreInboundPacket(command, body), consumed)
    }

    private fun levelToPercent(level: Int, maxLevel: Int = 5): Int {
        if (level == 255 || level < 0) return 0
        return ((level.coerceIn(0, maxLevel) * 100) / maxLevel).coerceIn(0, 100)
    }

    /**
     * Parses the A3947 StateUpdate packet body (cmd [0x01, 0x01]).
     */
    fun parseA3947StateUpdate(
        body: ByteArray,
        currentBattery: BatteryParams? = null,
    ): SoundcoreStateUpdate? {
        if (body.size < 6) return null

        val leftRawLevel = body[2].toInt() and 0xFF
        val rightRawLevel = body[3].toInt() and 0xFF
        val leftCharging = (body[4].toInt() and 0xFF) == 1
        val rightCharging = (body[5].toInt() and 0xFF) == 1

        val leftConnected = leftRawLevel != 255
        val rightConnected = rightRawLevel != 255

        val leftPod = PodParams(
            battery = levelToPercent(leftRawLevel),
            isCharging = leftCharging,
            isConnected = leftConnected,
            rawStatus = leftRawLevel,
        )

        val rightPod = PodParams(
            battery = levelToPercent(rightRawLevel),
            isCharging = rightCharging,
            isConnected = rightConnected,
            rawStatus = rightRawLevel,
        )

        // Case battery is at body offset 139 for A3947 if body is long enough
        val casePod = if (body.size > 139) {
            val caseRawLevel = body[139].toInt() and 0xFF
            val caseConnected = caseRawLevel != 255
            PodParams(
                battery = levelToPercent(caseRawLevel),
                isCharging = false,
                isConnected = caseConnected,
                rawStatus = caseRawLevel,
            )
        } else {
            currentBattery?.case
        }

        val battery = BatteryParams(
            left = leftPod,
            right = rightPod,
            case = casePod,
        )

        // Sound mode at offset 126 if present
        var ancMode = NoiseControlMode.UNKNOWN
        var ancSubMode: Int? = null
        if (body.size > 126) {
            ancMode = when (body[126].toInt() and 0xFF) {
                0 -> NoiseControlMode.NOISE_CANCELLATION
                1 -> NoiseControlMode.TRANSPARENCY
                2 -> NoiseControlMode.OFF
                else -> NoiseControlMode.UNKNOWN
            }
            if (ancMode == NoiseControlMode.NOISE_CANCELLATION && body.size > 129) {
                val ncMode = body[129].toInt() and 0xFF
                if (ncMode == 0) {
                    ancSubMode = 0 // Manual
                } else {
                    val adaptive = body[127].toInt() and 0x0F
                    ancSubMode = if (adaptive in 1..5) adaptive else 3 // Adaptive
                }
            }
        }

        return SoundcoreStateUpdate(battery, ancMode, ancSubMode)
    }

    /**
     * Parses DualBatteryLevel update packet body (cmd [0x01, 0x03]).
     */
    fun parseDualBatteryLevel(
        body: ByteArray,
        currentBattery: BatteryParams,
    ): BatteryParams {
        if (body.size < 2) return currentBattery
        val leftRawLevel = body[0].toInt() and 0xFF
        val rightRawLevel = body[1].toInt() and 0xFF

        return currentBattery.copy(
            left = currentBattery.left?.copy(
                battery = levelToPercent(leftRawLevel),
                isConnected = leftRawLevel != 255,
                rawStatus = leftRawLevel,
            ) ?: PodParams(
                battery = levelToPercent(leftRawLevel),
                isConnected = leftRawLevel != 255,
                rawStatus = leftRawLevel,
            ),
            right = currentBattery.right?.copy(
                battery = levelToPercent(rightRawLevel),
                isConnected = rightRawLevel != 255,
                rawStatus = rightRawLevel,
            ) ?: PodParams(
                battery = levelToPercent(rightRawLevel),
                isConnected = rightRawLevel != 255,
                rawStatus = rightRawLevel,
            ),
        )
    }

    /**
     * Parses DualBatteryCharging update packet body (cmd [0x01, 0x04]).
     */
    fun parseDualBatteryCharging(
        body: ByteArray,
        currentBattery: BatteryParams,
    ): BatteryParams {
        if (body.size < 2) return currentBattery
        val leftCharging = (body[0].toInt() and 0xFF) == 1
        val rightCharging = (body[1].toInt() and 0xFF) == 1

        return currentBattery.copy(
            left = currentBattery.left?.copy(isCharging = leftCharging),
            right = currentBattery.right?.copy(isCharging = rightCharging),
        )
    }

    /**
     * Parses SoundModes update packet body (cmd [0x06, 0x01]).
     */
    fun parseSoundModes(body: ByteArray): NoiseControlMode =
        parseSoundModesWithSubMode(body).ancMode

    fun parseSoundModesWithSubMode(body: ByteArray): SoundcoreSoundModes {
        if (body.isEmpty()) return SoundcoreSoundModes(NoiseControlMode.UNKNOWN, null)
        val mode = when (body[0].toInt() and 0xFF) {
            0 -> NoiseControlMode.NOISE_CANCELLATION
            1 -> NoiseControlMode.TRANSPARENCY
            2 -> NoiseControlMode.OFF
            else -> NoiseControlMode.UNKNOWN
        }
        val subMode = if (mode == NoiseControlMode.NOISE_CANCELLATION && body.size > 3) {
            val ncMode = body[3].toInt() and 0xFF
            if (ncMode == 0) {
                0 // Manual
            } else {
                val adaptive = body[1].toInt() and 0x0F
                if (adaptive in 1..5) adaptive else 3 // Adaptive
            }
        } else null
        return SoundcoreSoundModes(mode, subMode)
    }
}

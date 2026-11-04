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
    val isAdaptiveAnc: Boolean = false,
    val gamingMode: Boolean? = null,
    val wearDetection: Boolean? = null,
)

data class SoundcoreSoundModes(
    val ancMode: NoiseControlMode,
    val ancSubMode: Int? = null,
    val isAdaptive: Boolean = false,
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
    const val CMD_SET_GAMING_MODE = 0x8510 // [0x10, 0x85]
    const val CMD_SET_WEARING_DETECTION = 0x8101 // [0x01, 0x81]

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
     * Builds the SetSoundModes packet for Soundcore earbuds (A3947 7-byte body or A3952 6-byte body).
     */
    fun buildSetSoundModesPacket(
        mode: NoiseControlMode,
        subMode: Int? = null,
        isAdaptive: Boolean = false,
        model: SoundcoreModel = SoundcoreModel.A3947,
        cachedManualLevel: Int? = null,
    ): ByteArray {
        val body = when (model) {
            SoundcoreModel.A3952 -> when (mode) {
                NoiseControlMode.NOISE_CANCELLATION -> {
                    if (isAdaptive) {
                        val adaptiveLevel = if (subMode != null && subMode in 0..2) subMode else 0
                        val manualLevel = (cachedManualLevel ?: 3).coerceIn(1, 3)
                        byteArrayOf(
                            0x00,
                            ((manualLevel shl 4) or adaptiveLevel).toByte(),
                            0x00,
                            0x01, // Adaptive (nc_mode = 1)
                            0x01,
                            0x00
                        )
                    } else {
                        val manualLevel = if (subMode != null && subMode in 1..3) subMode else (cachedManualLevel ?: 3).coerceIn(1, 3)
                        byteArrayOf(
                            0x00,
                            ((manualLevel shl 4) or 0x00).toByte(),
                            0x00,
                            0x00, // Manual (nc_mode = 0)
                            0x01,
                            0x00
                        )
                    }
                }
                NoiseControlMode.TRANSPARENCY -> byteArrayOf(
                    0x01, 0x00, 0x00, 0x00, 0x01, 0x00
                )
                NoiseControlMode.OFF, NoiseControlMode.UNKNOWN -> byteArrayOf(
                    0x02, 0x00, 0x00, 0x00, 0x00, 0x00
                )
            }
            SoundcoreModel.A3947 -> when (mode) {
                NoiseControlMode.NOISE_CANCELLATION -> {
                    if (isAdaptive) {
                        val adaptiveLevel = if (subMode != null && subMode in 1..5) subMode else 3
                        val manualLevel = (cachedManualLevel ?: 5).coerceIn(1, 5)
                        byteArrayOf(
                            0x00,
                            ((manualLevel shl 4) or adaptiveLevel).toByte(),
                            0x01,
                            0x01, // Adaptive (nc_mode = 1)
                            0x01,
                            0x01,
                            0x00
                        )
                    } else {
                        // Manual noise cancelling
                        val manualLevel = if (subMode != null && subMode in 1..5) subMode else (cachedManualLevel ?: 5).coerceIn(1, 5)
                        byteArrayOf(
                            0x00,
                            ((manualLevel shl 4) or 0x00).toByte(),
                            0x01,
                            0x00, // Manual (nc_mode = 0)
                            0x01,
                            0x01,
                            0x03
                        )
                    }
                }
                NoiseControlMode.TRANSPARENCY -> byteArrayOf(
                    0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00
                )
                NoiseControlMode.OFF, NoiseControlMode.UNKNOWN -> byteArrayOf(
                    0x02, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00
                )
            }
        }
        return buildPacket(byteArrayOf(0x06, 0x81.toByte()), body)
    }

    /**
     * Builds the SetGamingMode (low latency) packet for A3947 (cmd [0x10, 0x85]).
     */
    fun buildSetGamingModePacket(enabled: Boolean): ByteArray {
        return buildPacket(byteArrayOf(0x10, 0x85.toByte()), byteArrayOf(if (enabled) 0x01 else 0x00))
    }

    /**
     * Builds the SetWearingDetection (auto play/pause) packet for A3947 (cmd [0x01, 0x81]).
     */
    fun buildSetWearingDetectionPacket(enabled: Boolean): ByteArray {
        return buildPacket(byteArrayOf(0x01, 0x81.toByte()), byteArrayOf(if (enabled) 0x01 else 0x00))
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
            if (caseRawLevel != 255 && levelToPercent(caseRawLevel) > 0) {
                PodParams(
                    battery = levelToPercent(caseRawLevel),
                    isCharging = false,
                    isConnected = true,
                    rawStatus = caseRawLevel,
                )
            } else {
                currentBattery?.case
            }
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
        var isAdaptiveAnc = false
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
                    val manual = (body[127].toInt() and 0xF0) shr 4
                    ancSubMode = if (manual in 1..5) manual else 5 // Manual level 5
                    isAdaptiveAnc = false
                } else {
                    val adaptive = body[127].toInt() and 0x0F
                    ancSubMode = if (adaptive in 1..5) adaptive else 3 // Adaptive level 3
                    isAdaptiveAnc = true
                }
            }
        }

        // Gaming mode at offset 143 if present
        val gamingMode = if (body.size > 143) {
            (body[143].toInt() and 0xFF) == 1
        } else null

        // Auto play/pause (wear detection) at offset 152 if present
        val wearDetection = if (body.size > 152) {
            (body[152].toInt() and 0xFF) == 1
        } else null

        return SoundcoreStateUpdate(
            battery = battery,
            ancMode = ancMode,
            ancSubMode = ancSubMode,
            isAdaptiveAnc = isAdaptiveAnc,
            gamingMode = gamingMode,
            wearDetection = wearDetection,
        )
    }

    /**
     * Parses the A3952 (Liberty 3 Pro) StateUpdate packet body (cmd [0x01, 0x01]).
     */
    fun parseA3952StateUpdate(
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

        // Case battery is at body offset 130 for A3952
        val casePod = if (body.size > 130) {
            val caseRawLevel = body[130].toInt() and 0xFF
            if (caseRawLevel != 255 && levelToPercent(caseRawLevel) > 0) {
                PodParams(
                    battery = levelToPercent(caseRawLevel),
                    isCharging = false,
                    isConnected = true,
                    rawStatus = caseRawLevel,
                )
            } else {
                currentBattery?.case
            }
        } else {
            currentBattery?.case
        }

        val battery = BatteryParams(
            left = leftPod,
            right = rightPod,
            case = casePod,
        )

        // Sound mode at offset 121 for A3952
        var ancMode = NoiseControlMode.UNKNOWN
        var ancSubMode: Int? = null
        var isAdaptiveAnc = false
        if (body.size > 121) {
            ancMode = when (body[121].toInt() and 0xFF) {
                0 -> NoiseControlMode.NOISE_CANCELLATION
                1 -> NoiseControlMode.TRANSPARENCY
                2 -> NoiseControlMode.OFF
                else -> NoiseControlMode.UNKNOWN
            }
            if (ancMode == NoiseControlMode.NOISE_CANCELLATION && body.size > 124) {
                val ncMode = body[124].toInt() and 0xFF
                if (ncMode == 0) {
                    val manual = (body[122].toInt() and 0xF0) shr 4
                    ancSubMode = if (manual in 1..3) manual else 3
                    isAdaptiveAnc = false
                } else {
                    val adaptive = body[122].toInt() and 0x0F
                    ancSubMode = if (adaptive in 0..2) adaptive else 0
                    isAdaptiveAnc = true
                }
            }
        }

        // Wearing detection at offset 128 for A3952
        val wearDetection = if (body.size > 128) {
            (body[128].toInt() and 0xFF) == 1
        } else null

        return SoundcoreStateUpdate(
            battery = battery,
            ancMode = ancMode,
            ancSubMode = ancSubMode,
            isAdaptiveAnc = isAdaptiveAnc,
            gamingMode = null,
            wearDetection = wearDetection,
        )
    }

    /**
     * Auto-detects model from serial number prefix in body (bytes 16..19) or falls back to provided model.
     */
    fun detectModelFromStateUpdate(body: ByteArray, fallback: SoundcoreModel = SoundcoreModel.A3947): SoundcoreModel {
        if (body.size >= 20) {
            val sn = runCatching { String(body, 16, 4, Charsets.US_ASCII) }.getOrNull()
            if (sn != null) {
                if (sn.startsWith("3952")) return SoundcoreModel.A3952
                if (sn.startsWith("3947")) return SoundcoreModel.A3947
            }
        }
        return fallback
    }

    fun parseStateUpdate(
        body: ByteArray,
        model: SoundcoreModel = SoundcoreModel.A3947,
        currentBattery: BatteryParams? = null,
    ): SoundcoreStateUpdate? {
        val effectiveModel = detectModelFromStateUpdate(body, model)
        return when (effectiveModel) {
            SoundcoreModel.A3952 -> parseA3952StateUpdate(body, currentBattery)
            SoundcoreModel.A3947 -> parseA3947StateUpdate(body, currentBattery)
        }
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
    fun parseSoundModes(body: ByteArray, model: SoundcoreModel = SoundcoreModel.A3947): NoiseControlMode =
        parseSoundModesWithSubMode(body, model).ancMode

    fun parseSoundModesWithSubMode(
        body: ByteArray,
        model: SoundcoreModel = SoundcoreModel.A3947,
    ): SoundcoreSoundModes {
        if (body.isEmpty()) return SoundcoreSoundModes(NoiseControlMode.UNKNOWN, null, false)
        val mode = when (body[0].toInt() and 0xFF) {
            0 -> NoiseControlMode.NOISE_CANCELLATION
            1 -> NoiseControlMode.TRANSPARENCY
            2 -> NoiseControlMode.OFF
            else -> NoiseControlMode.UNKNOWN
        }
        var isAdaptive = false
        val subMode = if (mode == NoiseControlMode.NOISE_CANCELLATION && body.size > 3) {
            val ncMode = body[3].toInt() and 0xFF
            if (ncMode == 0) {
                val manual = (body[1].toInt() and 0xF0) shr 4
                isAdaptive = false
                if (model == SoundcoreModel.A3952) {
                    if (manual in 1..3) manual else 3
                } else {
                    if (manual in 1..5) manual else 5
                }
            } else {
                val adaptive = body[1].toInt() and 0x0F
                isAdaptive = true
                if (model == SoundcoreModel.A3952) {
                    if (adaptive in 0..2) adaptive else 0
                } else {
                    if (adaptive in 1..5) adaptive else 3
                }
            }
        } else null
        return SoundcoreSoundModes(mode, subMode, isAdaptive)
    }
}

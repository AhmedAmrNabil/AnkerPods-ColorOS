package moe.chenxy.huaweipods.backend.soundcore

import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.PodParams

/**
 * State gating and battery caching state machine for Soundcore earbuds (specifically Liberty 4 NC / A3947).
 *
 * Implements decoupled readiness gating and case battery caching:
 * 1. isReady depends strictly on Left and Right earbuds.
 * 2. Case battery NEVER blocks isReady.
 * 3. When rawCase == 255 (earbuds out of case), cachedCase is preserved and isCaseCharging is forced to false.
 * 4. When out of case, earbud charging states are forced to false to prevent phantom lightning bolts.
 */
object SoundcoreStateManager {
    var isReady: Boolean = false
        private set

    var leftBattery: Int = 100
        private set
    var rightBattery: Int = 100
        private set
    var isLeftCharging: Boolean = false
        private set
    var isRightCharging: Boolean = false
        private set

    private var cachedCase: Int? = null
    val caseBattery: Int
        get() = cachedCase ?: 100

    var isCaseCharging: Boolean = false
        private set

    var ancMode: NoiseControlMode = NoiseControlMode.OFF
    var ancSubMode: Int? = null
    var isAdaptiveAnc: Boolean = false
        internal set

    var gamingMode: Boolean? = null
        internal set
    var wearDetection: Boolean? = null
        internal set

    var activeModel: SoundcoreModel = SoundcoreModel.A3947
    var cachedManualAncLevel: Int = 5
        internal set

    fun levelToPercent(level: Int, maxLevel: Int = 5): Int {
        if (level == 255 || level < 0) return 0
        if (level > maxLevel) return level.coerceIn(0, 100)
        return ((level.coerceIn(0, maxLevel) * 100) / maxLevel).coerceIn(0, 100)
    }

    /**
     * Updates state from StateUpdate packet body (cmd [0x01, 0x01]).
     */
    fun updateFromStateUpdate(body: ByteArray, model: SoundcoreModel = activeModel): Boolean {
        if (body.size < 6) return false

        val effectiveModel = SoundcorePacket.detectModelFromStateUpdate(body, model)
        activeModel = effectiveModel

        val rawL = body[2].toInt() and 0xFF
        val rawR = body[3].toInt() and 0xFF
        val leftChg = (body[4].toInt() and 0xFF) == 1
        val rightChg = (body[5].toInt() and 0xFF) == 1

        val rawCase: Int
        val caseChg: Boolean

        when (effectiveModel) {
            SoundcoreModel.A3952 -> {
                rawCase = if (body.size > 130) body[130].toInt() and 0xFF else 255
                caseChg = false

                // Sound mode at offset 121 for A3952
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
                            if (body.size > 122) {
                                val manual = (body[122].toInt() and 0xF0) shr 4
                                if (manual in 1..3) {
                                    cachedManualAncLevel = manual
                                }
                            }
                            ancSubMode = cachedManualAncLevel
                            isAdaptiveAnc = false
                        } else {
                            val adaptive = if (body.size > 122) body[122].toInt() and 0x0F else 0
                            ancSubMode = if (adaptive in 0..2) adaptive else 0
                            isAdaptiveAnc = true
                        }
                    } else if (ancMode != NoiseControlMode.NOISE_CANCELLATION) {
                        ancSubMode = null
                        isAdaptiveAnc = false
                    }
                }

                // Wearing detection at offset 128 for A3952
                if (body.size > 128) {
                    wearDetection = (body[128].toInt() and 0xFF) == 1
                }
                gamingMode = null
            }
            SoundcoreModel.A3947 -> {
                rawCase = if (body.size > 139) body[139].toInt() and 0xFF else 255
                caseChg = if (body.size > 138) (body[138].toInt() and 0xFF) == 1 else false

                // Sound mode at offset 126 if present
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
                            if (body.size > 127) {
                                val manual = (body[127].toInt() and 0xF0) shr 4
                                if (manual in 1..5) {
                                    cachedManualAncLevel = manual
                                }
                            }
                            ancSubMode = cachedManualAncLevel
                            isAdaptiveAnc = false
                        } else {
                            val adaptive = if (body.size > 127) body[127].toInt() and 0x0F else 3
                            ancSubMode = if (adaptive in 1..5) adaptive else 3
                            isAdaptiveAnc = true
                        }
                    } else if (ancMode != NoiseControlMode.NOISE_CANCELLATION) {
                        ancSubMode = null
                        isAdaptiveAnc = false
                    }
                }

                // Gaming mode at offset 143 if present
                if (body.size > 143) {
                    gamingMode = (body[143].toInt() and 0xFF) == 1
                }

                // Auto play/pause (wear detection) at offset 152 if present
                if (body.size > 152) {
                    wearDetection = (body[152].toInt() and 0xFF) == 1
                }
            }
        }

        runCatching {
            android.util.Log.i(
                "SoundcoreState",
                "updateFromStateUpdate ($effectiveModel): rawL=$rawL rawR=$rawR rawCase=$rawCase caseChg=$caseChg ancMode=$ancMode wear=$wearDetection bodyLen=${body.size}"
            )
        }

        return processBatteryReading(rawL, rawR, rawCase, leftChg, rightChg, caseChg)
    }

    /**
     * Updates battery levels from DualBatteryLevel packet body (cmd [0x01, 0x03]).
     */
    fun updateFromDualBatteryLevel(body: ByteArray): Boolean {
        if (body.size < 2) return false
        val rawL = body[0].toInt() and 0xFF
        val rawR = body[1].toInt() and 0xFF
        return processBatteryReading(rawL, rawR, 255, isLeftCharging, isRightCharging, false)
    }

    /**
     * Updates charging states from DualBatteryCharging packet body (cmd [0x01, 0x04]).
     */
    fun updateFromDualBatteryCharging(body: ByteArray): Boolean {
        if (body.size < 2) return false
        val leftChg = (body[0].toInt() and 0xFF) == 1
        val rightChg = (body[1].toInt() and 0xFF) == 1
        isLeftCharging = leftChg
        isRightCharging = rightChg
        return true
    }

    /**
     * Core resolution and case caching state machine.
     */
    fun processBatteryReading(
        rawL: Int,
        rawR: Int,
        rawCase: Int,
        leftChg: Boolean,
        rightChg: Boolean,
        caseChg: Boolean,
    ): Boolean {
        val parsedL = levelToPercent(rawL)
        val parsedR = levelToPercent(rawR)

        // 1. Mandatory Gate: L & R must be valid (not 255)
        val validL = rawL != 255 && parsedL in 0..100
        val validR = rawR != 255 && parsedR in 0..100

        if (validL || validR) {
            if (validL) leftBattery = parsedL
            if (validR) rightBattery = parsedR

            val isOutOfCase = (rawCase == 255 || rawCase > 100)
            if (isOutOfCase) {
                // Out of case / in-ear / actively connected over A2DP:
                // charging is physically impossible -> force charging flags off
                isLeftCharging = false
                isRightCharging = false
            } else {
                if (validL) isLeftCharging = leftChg
                if (validR) isRightCharging = rightChg
            }
            isReady = true
        } else {
            return false
        }

        // 2. Opportunistic Case Handling
        if (rawCase != 255 && rawCase <= 100) {
            val parsedCase = levelToPercent(rawCase)
            if (parsedCase > 0) {
                cachedCase = parsedCase
            }
            isCaseCharging = caseChg
        } else {
            // Out of case (0xFF) -> preserve cache, force charging off
            isCaseCharging = false
        }

        val isOutOfCase = (rawCase == 255 || rawCase > 100)
        runCatching {
            android.util.Log.i(
                "SoundcoreState",
                "processBatteryReading result: rawCase=$rawCase isOutOfCase=$isOutOfCase L_chg=$isLeftCharging R_chg=$isRightCharging Case_chg=$isCaseCharging"
            )
        }

        return true
    }

    fun toBatteryParams(): BatteryParams {
        return BatteryParams(
            left = PodParams(
                battery = leftBattery,
                isCharging = isLeftCharging,
                isConnected = true,
                rawStatus = leftBattery,
            ),
            right = PodParams(
                battery = rightBattery,
                isCharging = isRightCharging,
                isConnected = true,
                rawStatus = rightBattery,
            ),
            case = PodParams(
                battery = caseBattery,
                isCharging = isCaseCharging,
                isConnected = true,
                rawStatus = caseBattery,
            ),
        )
    }

    fun onDisconnect() {
        isReady = false
        cachedCase = null
        isLeftCharging = false
        isRightCharging = false
        isCaseCharging = false
        leftBattery = 100
        rightBattery = 100
        ancMode = NoiseControlMode.OFF
        ancSubMode = null
        isAdaptiveAnc = false
        gamingMode = null
        wearDetection = null
        activeModel = SoundcoreModel.A3947
    }
}

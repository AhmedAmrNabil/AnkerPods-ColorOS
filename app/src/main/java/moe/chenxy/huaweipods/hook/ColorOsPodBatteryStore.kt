package moe.chenxy.huaweipods.hook

import java.util.concurrent.ConcurrentHashMap

/**
 * ColorOS「设备空间」卡片用的三段电量快照（左 / 右 / 盒）。
 *
 * `com.heytap.mydevices` 进程里只有字符串摘要（[colorOsBatterySummary]），而
 * `QuickCardDeviceData.batteryLeft/Right/Main` 需要的是 `BatteryInfo(BatteryType, value, charge)`，
 * 所以电量广播到达时把原始数字留在这里，卡片 DTO 构造时再取。
 */
internal data class ColorOsPodBattery(
    val address: String,
    val left: Int,
    val leftConnected: Boolean,
    val right: Int,
    val rightConnected: Boolean,
    val case: Int,
    val caseConnected: Boolean,
)

/** 卡片的一段电量；[channel] 取 `BatteryType` 的常量名（LEFT / RIGHT / BOX）。 */
internal data class ColorOsBatteryPart(val channel: String, val value: Int)

/** 卡片三段展示需要的三个数字；左右耳缺一段就不进入 MULTI 模式。 */
internal data class ColorOsBatteryTriple(
    val left: Int,
    val right: Int,
    val case: Int?,
) {
    val parts: List<ColorOsBatteryPart>
        get() = buildList {
            add(ColorOsBatteryPart(CHANNEL_LEFT, left))
            add(ColorOsBatteryPart(CHANNEL_RIGHT, right))
            case?.let { add(ColorOsBatteryPart(CHANNEL_BOX, it)) }
        }

    /** 卡片主电量所在的那一段（值最大者，并列时取左 / 右 / 盒的顺序）。 */
    val mainPart: ColorOsBatteryPart get() = parts.maxBy { it.value }

    /** 卡片最小电量所在的那一段（系统用它决定低电提示）。 */
    val minPart: ColorOsBatteryPart get() = parts.minBy { it.value }

    val main: Int get() = mainPart.value

    val minimum: Int get() = minPart.value

    companion object {
        const val CHANNEL_LEFT = "LEFT"
        const val CHANNEL_RIGHT = "RIGHT"
        const val CHANNEL_BOX = "BOX"
    }
}

internal fun colorOsBatteryTriple(pod: ColorOsPodBattery?): ColorOsBatteryTriple? {
    if (pod == null) return null
    if (!pod.leftConnected || !pod.rightConnected) return null
    return ColorOsBatteryTriple(
        left = pod.left.coerceIn(0, 100),
        right = pod.right.coerceIn(0, 100),
        case = if (pod.caseConnected) pod.case.coerceIn(0, 100) else null,
    )
}

internal object ColorOsPodBatteryStore {
    private val entries = ConcurrentHashMap<String, ColorOsPodBattery>()

    internal fun key(address: String): String = address.trim().uppercase()

    fun put(pod: ColorOsPodBattery) {
        if (pod.address.isBlank()) return
        entries[key(pod.address)] = pod.copy(address = key(pod.address))
    }

    fun get(address: String?): ColorOsPodBattery? {
        val normalized = address?.takeIf(String::isNotBlank)?.let(::key) ?: return null
        return entries[normalized]
    }

    /** 传 null / 空地址表示整表清空（断开全部耳机）。 */
    fun clear(address: String?) {
        val normalized = address?.takeIf(String::isNotBlank)?.let(::key)
        if (normalized == null) entries.clear() else entries.remove(normalized)
    }

    fun size(): Int = entries.size
}

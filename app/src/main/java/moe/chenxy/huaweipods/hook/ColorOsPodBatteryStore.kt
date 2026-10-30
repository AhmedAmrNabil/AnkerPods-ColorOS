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

/**
 * 卡片三段展示需要的数字；**缺失的段用 null 表示**。
 *
 * 之前的版本要求左右耳都在线才算有效，结果 FreeClip 最常见的状态——一只耳在盒里、只报另一只——
 * 直接拿不到任何电量。现在只要有一段在线就成立，缺的段由上层按行隐藏。
 */
internal data class ColorOsBatteryTriple(
    val left: Int?,
    val right: Int?,
    val case: Int?,
) {
    init {
        require(left != null || right != null || case != null) {
            "a triple needs at least one part"
        }
    }

    val parts: List<ColorOsBatteryPart>
        get() = buildList {
            left?.let { add(ColorOsBatteryPart(CHANNEL_LEFT, it)) }
            right?.let { add(ColorOsBatteryPart(CHANNEL_RIGHT, it)) }
            case?.let { add(ColorOsBatteryPart(CHANNEL_BOX, it)) }
        }

    /** 卡片主电量所在的那一段（值最大者，并列时取左 / 右 / 盒的顺序）。 */
    val mainPart: ColorOsBatteryPart get() = parts.maxBy { it.value }

    /** 卡片最小电量所在的那一段（系统用它决定低电提示）。 */
    val minPart: ColorOsBatteryPart get() = parts.minBy { it.value }

    val main: Int get() = mainPart.value

    val minimum: Int get() = minPart.value

    /** 至少两段才算"多段"，一段的时候不值得让宿主切多段布局。 */
    val isMulti: Boolean get() = parts.size >= 2

    companion object {
        const val CHANNEL_LEFT = "LEFT"
        const val CHANNEL_RIGHT = "RIGHT"
        const val CHANNEL_BOX = "BOX"
    }
}

internal fun colorOsBatteryTriple(pod: ColorOsPodBattery?): ColorOsBatteryTriple? {
    if (pod == null) return null
    val left = pod.left.takeIf { pod.leftConnected }?.coerceIn(0, 100)
    val right = pod.right.takeIf { pod.rightConnected }?.coerceIn(0, 100)
    val case = pod.case.takeIf { pod.caseConnected }?.coerceIn(0, 100)
    if (left == null && right == null && case == null) return null
    return ColorOsBatteryTriple(left, right, case)
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

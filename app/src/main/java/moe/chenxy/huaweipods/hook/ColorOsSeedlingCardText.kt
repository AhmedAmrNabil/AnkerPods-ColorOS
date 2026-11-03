package moe.chenxy.huaweipods.hook

/** 只有一个百分比的写法，例如 `secondaryInfo="100%"`。 */
private val SEEDLING_BARE_VALUE = Regex("""^\s*\d{1,3}\s*%\s*$""")

/**
 * seedling 卡片文本的三段改写规则。
 *
 * `SeedlingCardData` 天生只有一个电量槽位（`batteryProgress: Int`），宿主拼出来的文本可能长成
 * 三种样子，逐级兜底：
 *
 * 1. `"使用中，电量：左 100%，右 100%"` —— 已经带分段标记，交给 [ColorOsSettingsBatteryText.rewrite]；
 * 2. `"已连接。电量：100%"` —— 带连接态前缀的单值，交给 [ColorOsSettingsBatteryText.rewriteSingleValue]，
 *    它的前缀门禁能保证手机自己的 `电量：85%` 不被误伤；
 * 3. `"100%"` —— 连标签都没有的裸值，直接展开成 `左100%   右100%   盒88%`。
 *
 * 不足两段（例如只有耳机盒在位）时返回 null，让宿主保持原样，不做无意义的改写。
 */
internal fun rewriteSeedlingCardText(raw: String?, triple: ColorOsBatteryTriple?): String? {
    if (raw.isNullOrEmpty() || triple == null) return null
    if (raw.length > MAX_SEEDLING_TEXT_LENGTH) return null
    return ColorOsSettingsBatteryText.rewrite(raw, triple.left, triple.right, triple.case)
        ?: ColorOsSettingsBatteryText.rewriteSingleValue(raw, triple)
        ?: expandSeedlingBareValue(raw, triple)
}

/** 两段以上才值得展开；单段保持原样。 */
internal fun expandSeedlingBareValue(raw: String?, triple: ColorOsBatteryTriple?): String? {
    if (raw == null || triple == null) return null
    if (!SEEDLING_BARE_VALUE.matches(raw)) return null
    return seedlingTripleText(triple)
}

/** 与宿主自己的写法保持一致：`左 100%，右 100%，盒 88%`；不足两段返回 null。 */
internal fun seedlingTripleText(triple: ColorOsBatteryTriple?): String? {
    if (triple == null || triple.parts.size < 2) return null
    return triple.parts.joinToString("，") { part ->
        val label = when (part.channel) {
            ColorOsBatteryTriple.CHANNEL_LEFT -> "左"
            ColorOsBatteryTriple.CHANNEL_RIGHT -> "右"
            ColorOsBatteryTriple.CHANNEL_BOX -> "盒"
            else -> ""
        }
        "$label ${part.value}%"
    }
}

private const val MAX_SEEDLING_TEXT_LENGTH = 96

/** 设备名配对的最短长度，避免 `Free` 这类片段命中别人的卡。 */
private const val MIN_POD_NAME_LENGTH = 4

/**
 * 这张 seedling 卡片是不是我们的耳机。
 *
 * 卡片参数里既有 `deviceId`（宿主用 `md5(大写 MAC)`）也有 `primaryInfo`（设备名），两个条件命中
 * 一个即可。**必须先判掉身份再改写**：`SeedlingCardData` 是所有设备共用的模型，不判身份就会把
 * 别人的设备卡（比如手机卡片上的 `85%`）也换成我们耳机的数字。
 */
internal fun seedlingCardMentionsPod(values: List<String?>, pod: ColorOsPodBattery?): Boolean {
    if (pod == null) return false
    val strings = values.filterNotNull()
    if (strings.isEmpty()) return false
    if (strings.any { ColorOsHomeCardBattery.matchesDeviceId(it, pod.address) }) return true
    val podName = pod.name ?: return false
    return strings.any { value ->
        value.length >= MIN_POD_NAME_LENGTH && ColorOsHomeCardBattery.matches(value, podName)
    }
}

/** 连接态文案：胶囊卡在没有电量的场景下就显示这几个词。 */
private val CAPSULE_CONNECTION_LABELS = setOf("已连接", "已配对", "使用中", "正在使用")

/**
 * 控制中心那张胶囊卡（`CapsuleInfo`）不属于我们时返回 false。
 *
 * 比 `SeedlingCardData` 好判：`CapsuleInfo` 自带 `mac` 字段，可以按地址精确配对，不依赖
 * `md5(大写 MAC)` 的推断。
 */
internal fun capsuleMatchesPod(values: List<String?>, pod: ColorOsPodBattery?): Boolean {
    if (pod == null) return false
    val address = pod.address.trim().uppercase(java.util.Locale.ROOT)
    if (address.isNotEmpty() && values.filterNotNull().any { it.trim().uppercase(java.util.Locale.ROOT) == address }) {
        return true
    }
    return seedlingCardMentionsPod(values, pod)
}

/** 该不该被换成三段电量：连接态文案或已经带电量的字符串。 */
internal fun isCapsuleBatteryCandidate(raw: String?): Boolean {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text.length > MAX_SEEDLING_TEXT_LENGTH) return false
    if (text in CAPSULE_CONNECTION_LABELS) return true
    return text.contains('%') || text.contains("电量")
}

/**
 * 胶囊卡副标题用的三段写法：`左 100% 右 100% 盒 88%`。
 *
 * 用空格而不是全角逗号——胶囊卡比设置页窄，全角标点容易换行。不足两段返回 null（保持宿主原文）。
 */
internal fun capsuleBatteryText(triple: ColorOsBatteryTriple?): String? {
    if (triple == null || triple.parts.size < 2) return null
    return triple.parts.joinToString(" ") { part ->
        val label = when (part.channel) {
            ColorOsBatteryTriple.CHANNEL_LEFT -> "左"
            ColorOsBatteryTriple.CHANNEL_RIGHT -> "右"
            ColorOsBatteryTriple.CHANNEL_BOX -> "盒"
            else -> ""
        }
        "$label ${part.value}%"
    }
}

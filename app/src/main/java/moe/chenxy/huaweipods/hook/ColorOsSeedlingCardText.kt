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

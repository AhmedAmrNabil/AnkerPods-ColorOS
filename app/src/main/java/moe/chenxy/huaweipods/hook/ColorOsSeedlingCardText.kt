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
internal fun seedlingTripleText(
    triple: ColorOsBatteryTriple?,
    separator: String = "，",
    chinese: Boolean = true,
): String? {
    if (triple == null || triple.parts.size < 2) return null
    return triple.parts.joinToString(separator) { part ->
        val label = when (part.channel) {
            ColorOsBatteryTriple.CHANNEL_LEFT -> if (chinese) "左" else "L"
            ColorOsBatteryTriple.CHANNEL_RIGHT -> if (chinese) "右" else "R"
            ColorOsBatteryTriple.CHANNEL_BOX -> if (chinese) "盒" else "Case"
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

/** 完整匹配连接态，避免 Disconnected 被 Connected 的子串命中。 */
private val CAPSULE_CONNECTION_LABELS = setOf(
    "已连接", "已配对", "使用中", "正在使用", "connected", "paired", "in use", "currently in use",
)
private val CAPSULE_CONNECTION_PREFIX = Regex(
    "^(?:${CAPSULE_CONNECTION_LABELS.joinToString("|") { Regex.escape(it) }})[，,。.:：\\s]+",
    RegexOption.IGNORE_CASE,
)
private const val CAPSULE_BATTERY_PART =
    "(?:(?:左耳?|右耳?|耳机盒|盒|left|right|case|l|r)\\s*[:：]?\\s*)?(?:100|[1-9]?\\d)\\s*%"
private val CAPSULE_BATTERY_VALUE = Regex(
    "(?:电量\\s*[:：]?\\s*|battery\\s*:?\\s*)?$CAPSULE_BATTERY_PART" +
        "(?:[\\s，,、·]+$CAPSULE_BATTERY_PART)*(?:\\s*(?:的电量|battery))?[。.]?",
    RegexOption.IGNORE_CASE,
)

/** CapsuleInfo 的独立 MAC 是唯一身份，不按同型号名称回退。 */
internal fun capsuleMatchesPod(address: String?, pod: ColorOsPodBattery?): Boolean =
    !address.isNullOrBlank() && pod != null &&
        address.trim().equals(pod.address.trim(), ignoreCase = true)

/** 只接受连接态或完整的电量语法，URI、标题和错误提示中的百分号不算电量。 */
internal fun isCapsuleBatteryCandidate(raw: String?): Boolean {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text.length > MAX_SEEDLING_TEXT_LENGTH) return false
    if (text.lowercase(java.util.Locale.ROOT) in CAPSULE_CONNECTION_LABELS) return true
    return CAPSULE_BATTERY_VALUE.matches(CAPSULE_CONNECTION_PREFIX.replaceFirst(text, ""))
}

/** getter 的返回值策略同样适用于 Gson 绕过构造函数分配的模型。 */
internal fun rewriteCapsuleSubtitle(
    raw: String?,
    address: String?,
    capsuleType: String?,
    pod: ColorOsPodBattery?,
    chinese: Boolean,
): String? {
    if (capsuleType != "CONNECTED" || !capsuleMatchesPod(address, pod)) return null
    if (!isCapsuleBatteryCandidate(raw)) return null
    return capsuleBatteryText(colorOsBatteryTriple(pod), chinese)
}

/** 紧凑胶囊副标题；分段标签和最少段数规则与 seedling 共用。 */
internal fun capsuleBatteryText(triple: ColorOsBatteryTriple?, chinese: Boolean = true): String? =
    seedlingTripleText(triple, separator = " ", chinese = chinese)

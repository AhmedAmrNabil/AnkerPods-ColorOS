package moe.chenxy.huaweipods.hook

/** 未确认多设备选中规则的 ROM，只处理唯一在线设备，不能靠相同名称猜 MAC。 */
internal fun rewriteBluetoothTileSubtitle(
    raw: String?,
    enabled: Boolean,
    connected: Boolean,
    addresses: List<String?>,
    pod: ColorOsPodBattery?,
    chinese: Boolean,
): String? {
    if (!enabled || !connected || addresses.size != 1) return null
    return rewriteCapsuleSubtitle(raw, addresses.single(), "CONNECTED", pod, chinese)
}

/** 固件把副标题作为无障碍描述的末段；保留前面的设备名和操作提示。 */
internal fun rewriteBluetoothTileDescription(raw: String?, subtitle: String, replacement: String): String? {
    if (raw == subtitle) return replacement
    if (raw == null || subtitle.isBlank()) return null
    val suffix = ", $subtitle"
    return if (raw.endsWith(suffix)) raw.dropLast(subtitle.length) + replacement else null
}

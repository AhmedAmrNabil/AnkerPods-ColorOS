package moe.chenxy.huaweipods.hook

/**
 * 蓝牙设置列表行的电量摘要改写。
 *
 * 真机（PLK110 / ColorOS 17）上这一行是宿主自己拼的：
 * `使用中，电量：左 100%，右 100%` —— **只有左右，没有盒子**。
 * 同一份数据喂给连接弹窗时三段都在（`左耳 100% · 右耳 100% · 耳机盒 100%`），
 * 说明数据不缺，缺的是列表行的格式模板，所以只能在文本层补齐。
 *
 * 这个文件只放纯逻辑，方便单测：认得出"电量摘要"、拼得出三段、其它文本一律不碰。
 */
internal object ColorOsSettingsBatteryText {

    /** 摘要里出现这些字才算"电量行"，避免误改别的设置项文本。 */
    private val PART_MARKERS = listOf("左", "右", "盒")

    /**
     * 把 `prefix + 左/右[，盒]` 重写成三段；认不出来或没有可用数据时返回 `null`（保持原样）。
     *
     * [left] / [right] / [case] 为 `null` 表示该段不可用。
     */
    fun rewrite(text: CharSequence?, left: Int?, right: Int?, case: Int?): String? {
        val raw = text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val separator = raw.indexOfAny(charArrayOf('：', ':'))
        if (separator < 0 || separator == raw.lastIndex) return null
        val body = raw.substring(separator + 1)
        if (PART_MARKERS.none { body.contains(it) }) return null
        // 英文模板里冒号后面通常还有个空格（`battery: 左 98%`），改写时原样保留。
        val leading = body.takeWhile { it == ' ' }
        val parts = buildList {
            left?.let { add("左 ${it.coerceIn(0, 100)}%") }
            right?.let { add("右 ${it.coerceIn(0, 100)}%") }
            case?.let { add("盒 ${it.coerceIn(0, 100)}%") }
        }
        if (parts.size < 2) return null
        val rewritten = raw.substring(0, separator + 1) + leading + parts.joinToString("，")
        return rewritten.takeIf { it != raw }
    }
}

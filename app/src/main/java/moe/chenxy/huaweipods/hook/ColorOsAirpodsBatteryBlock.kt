package moe.chenxy.huaweipods.hook

/**
 * 宿主 `layout/include_bluetooth_card_airpods_battery_block` 的结构与展示决策。
 *
 * 结构和 id 全部来自固件实证（`aapt2 dump resources` + `dump xmltree`，2026-10-30）：
 *
 * ```
 * container_battery_and_hint_airpods
 * ├─ device_not_connected_hint_id_airpods
 * ├─ layout_airpods_max        ← 主段
 * │  ├─ airpods_electricity_progress  (ProgressBar)
 * │  ├─ airpods_max_charging_icon     (充电图标)
 * │  └─ airpods_electricity_text
 * └─ layout_airpods_battery
 *    ├─ layout_box   : airpods_box_image / airpods_box_battery / airpods_box_charge / airpods_box_low
 *    ├─ layout_left  : left_airpods_image / left_airpods_battery / left_airpods_charge / airpods_left_low
 *    ├─ layout_right : right_airpods_image / right_airpods_battery / right_airpods_charge / airpods_right_low
 *    ├─ view_airpods_battery_line
 *    └─ airpods_image       ← 产品图（AirPods 渲染），MULTI 模式下屏幕上那张图就是它
 * ```
 *
 * 输入是 `BlueToothCardView` 的多段容器（字段 `o`），由 ViewStub
 * `id/stub_airpods_multiline_battery` 按需 inflate。
 */
internal object ColorOsAirpodsBatteryBlock {
    /** 该段没有数据时宿主自己显示的样子：占位符，而不是 0%。 */
    const val PLACEHOLDER = "--"

    /** 低电阈值：与宿主 `*_low` / `airpods_*_low` 图标的触发条件一致（≤20%）。 */
    const val LOW_THRESHOLD = 20

    /** MULTI 模式下的产品图。宿主只为 AirPods 准备这张图，`heroImage()` 优先按这个名字取。 */
    const val PRODUCT_IMAGE = "airpods_image"

    /** 一块电量：行容器 + 数字 + 可选进度条/充电图标/低电图标。 */
    data class Slot(
        val container: String?,
        val label: String,
        val progress: String?,
        val charge: String?,
        val low: String?,
    )

    val MAIN = Slot(
        container = "layout_airpods_max",
        label = "airpods_electricity_text",
        progress = "airpods_electricity_progress",
        charge = "airpods_max_charging_icon",
        low = null,
    )
    val LEFT = Slot("layout_left", "left_airpods_battery", null, "left_airpods_charge", "airpods_left_low")
    val RIGHT = Slot("layout_right", "right_airpods_battery", null, "right_airpods_charge", "airpods_right_low")
    val CASE = Slot("layout_box", "airpods_box_battery", null, "airpods_box_charge", "airpods_box_low")

    fun label(value: Int?): String = value?.let { "${it.coerceIn(0, 100)}%" } ?: PLACEHOLDER

    /** 进度条取值，无数据时归零（宿主进度条不认 null）。 */
    fun progress(value: Int?): Int = value?.coerceIn(0, 100) ?: 0

    fun isPresent(value: Int?): Boolean = value != null

    fun shouldShowCharging(value: Int?, charging: Boolean): Boolean = value != null && charging

    fun shouldShowLow(value: Int?): Boolean = value != null && value <= LOW_THRESHOLD

    /**
     * 主段取值：FreeClip 这类没有"主段"的耳机，主段用盒子；没有盒子就退回较大的那只耳，
     * 这样主进度条不会空着。
     */
    fun mainValue(left: Int?, right: Int?, case: Int?): Int? =
        case ?: listOfNotNull(left, right).maxOrNull()

    /**
     * 实际要画的格子：**只画有数据的那几格**。宿主对"缺一段"的处理是整行隐藏
     * （`layout_box` 之类是独立行容器），所以缺数据时必须隐藏容器，而不是把数字写成 0%。
     *
     * 盒子行只在有左右耳数据时才画：只有一段整机电量时，主段已经显示它了，
     * 再画一行一模一样的"盒 70%"没有意义。
     */
    fun slots(left: Int?, right: Int?, case: Int?): List<Pair<Slot, Int?>> = buildList {
        add(MAIN to mainValue(left, right, case))
        left?.let { add(LEFT to it) }
        right?.let { add(RIGHT to it) }
        if (left != null || right != null) case?.let { add(CASE to it) }
    }
}

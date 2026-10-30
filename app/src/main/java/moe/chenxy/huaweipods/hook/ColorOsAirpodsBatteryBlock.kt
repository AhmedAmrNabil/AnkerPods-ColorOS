package moe.chenxy.huaweipods.hook

/**
 * 宿主 `layout/include_bluetooth_card_airpods_battery_block` 的展示决策（dexdump + aapt2 实证，2026-10-30）。
 *
 * 这个块就是 `BlueToothCardView` 在 `MULTI` 展示模式下 ViewStub
 * （`id/stub_airpods_multiline_battery`）按需 inflate 进来的内容，四格分别是：
 *
 * - `airpods_electricity_text` / `airpods_electricity_progress` —— 主段（AirPods 语境下是 Max 头戴/充电盒）
 * - `left_airpods_battery` / `right_airpods_battery` —— 左右耳
 * - `airpods_box_battery` —— 盒子
 *
 * 充电图标（`*_charge`、`airpods_max_charging_icon`）与低电图标（`*_low`）由宿主动态开关，
 * 这里只把"该不该显示"的判定抽成纯函数，方便单测覆盖，不需要连真机。
 */
internal object ColorOsAirpodsBatteryBlock {
    /** 该段没有数据时宿主自己显示的样子：一个占位符，而不是 0%。 */
    const val PLACEHOLDER = "--"

    /** 低电阈值：与宿主 `airpods_low_battery` 图标的触发条件一致（≤20%）。 */
    const val LOW_THRESHOLD = 20

    fun label(value: Int?): String = value?.let { "${it.coerceIn(0, 100)}%" } ?: PLACEHOLDER

    /** 进度条取值，无数据时归零（宿主进度条不认 null）。 */
    fun progress(value: Int?): Int = value?.coerceIn(0, 100) ?: 0

    fun isPresent(value: Int?): Boolean = value != null

    fun shouldShowCharging(value: Int?, charging: Boolean): Boolean = value != null && charging

    fun shouldShowLow(value: Int?): Boolean = value != null && value <= LOW_THRESHOLD

    /**
     * 三格（主/左/右）取值。FreeClip 这类没有"主段"的耳机，主段用盒子；没有盒子就退回较大的那只耳，
     * 这样主进度条不会空着。
     */
    fun slots(left: Int?, right: Int?, case: Int?): Slots = Slots(
        main = case ?: listOfNotNull(left, right).maxOrNull(),
        left = left,
        right = right,
        case = case,
    )

    internal data class Slots(val main: Int?, val left: Int?, val right: Int?, val case: Int?)
}

package moe.chenxy.huaweipods.hook

import android.content.Context
import android.provider.Settings

/**
 * 设备空间三段电量的运行时开关。
 *
 * 之前这两个开关是编译期 `const`：每换一种组合都要重新构建 + 重装 + 重启宿主进程，
 * 真机上验证一次的成本极高。现在改成读 `Settings.Global` 的位掩码，改一行 `adb shell settings put`
 * 就生效，不用重装：
 *
 * ```
 * adb shell settings put global huaweipods_multi_battery 1   # 只写 DTO（验证详情页吃不吃三段）
 * adb shell settings put global huaweipods_multi_battery 3   # DTO + 卡片切 MULTI
 * adb shell settings put global huaweipods_multi_battery 0   # 全关（默认）
 * ```
 *
 * 读取失败（取不到 `ContentResolver`、被限额等）一律按"关"处理：宁可少写，不可误写宿主卡片。
 */
internal object ColorOsBatteryFlags {
    const val SETTINGS_KEY = "huaweipods_multi_battery"

    /** 往 `QuickCardDeviceData` 的四个 `BatteryInfo` 槽位写数据。 */
    const val BIT_DTO = 1

    /** 把卡片视图切到 `MULTI` 并自己填多格块。 */
    const val BIT_CARD = 2

    @Volatile
    private var context: Context? = null

    private val reader = ColorOsBatteryFlagReader(
        readMask = { currentMask() },
    )

    /** 宿主进程首次拿到 Context 时登记一次，之后所有读取都走它。 */
    fun attach(context: Context?) {
        if (context != null && this.context == null) this.context = context.applicationContext
    }

    fun dtoEnabled(): Boolean = reader.dtoEnabled()

    fun cardEnabled(): Boolean = reader.cardEnabled()

    /** 仅供单测与日志：当前生效的位掩码。 */
    fun mask(): Int = reader.mask()

    fun resetForTest() {
        context = null
        reader.reset()
    }

    private fun currentMask(): Int {
        val resolver = context?.contentResolver ?: return 0
        return Settings.Global.getInt(resolver, SETTINGS_KEY, 0)
    }
}

/**
 * 带短 TTL 缓存的位掩码读取。
 *
 * 钩子挂在每次卡片 bind 上，不能每次都查 `Settings`；但要求"改了设置几秒内生效"，
 * 所以用 3 秒 TTL 的缓存。读取抛异常时保留上一次的值 —— 偶发查询失败不应该让卡片突然变样。
 */
internal class ColorOsBatteryFlagReader(
    private val readMask: () -> Int,
    private val ttlMs: Long = 3_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var cachedMask = 0

    @Volatile
    private var cachedAt = Long.MIN_VALUE

    fun mask(): Int {
        val timestamp = now()
        if (cachedAt != Long.MIN_VALUE && timestamp - cachedAt < ttlMs) return cachedMask
        cachedMask = runCatching(readMask).getOrDefault(cachedMask)
        cachedAt = timestamp
        return cachedMask
    }

    fun dtoEnabled(): Boolean = mask() and ColorOsBatteryFlags.BIT_DTO != 0

    fun cardEnabled(): Boolean = mask() and ColorOsBatteryFlags.BIT_CARD != 0

    fun reset() {
        cachedMask = 0
        cachedAt = Long.MIN_VALUE
    }
}

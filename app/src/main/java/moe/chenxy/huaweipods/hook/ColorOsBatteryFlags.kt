package moe.chenxy.huaweipods.hook

import android.content.Context
import android.provider.Settings
import moe.chenxy.huaweipods.config.ConfigManager

/**
 * 设备空间三段电量的运行时开关。
 *
 * 取值有两个来源，**adb 覆盖优先**：
 *
 * 1. `Settings.Global/huaweipods_multi_battery`（非 0 时生效）——调试/验证用，改一行命令就切，不用重装：
 *    ```
 *    adb shell settings put global huaweipods_multi_battery 1   # 只写 DTO
 *    adb shell settings put global huaweipods_multi_battery 3   # DTO + 卡片切 MULTI
 *    adb shell settings put global huaweipods_multi_battery 0   # 交还给 App 设置
 *    ```
 * 2. 模块设置里的开关（`AppConfig.multiBatteryMask`，经 LSPosed remote prefs 同步到宿主进程）——
 *    普通用户走这条，不需要 adb。
 *
 * 两个来源都取不到时按 0（全关）处理：宁可少写，不可误写宿主卡片。
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
        // adb 覆盖：非 0 就用它，0 表示"交还给 App 设置"。
        val resolver = context?.contentResolver
        if (resolver != null) {
            val override = runCatching { Settings.Global.getInt(resolver, SETTINGS_KEY, 0) }.getOrDefault(0)
            if (override != 0) return override
        }
        return ConfigManager.multiBatteryMask()
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

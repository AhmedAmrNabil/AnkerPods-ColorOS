package moe.chenxy.huaweipods.hook

import android.content.Context
import android.provider.Settings

/**
 * 最近一次耳机电量的跨进程快照。
 *
 * [ColorOsPodBatteryStore] 只在单个进程内有效，靠电量广播填充。对一个刚被拉起来的进程
 * （`com.android.settings`、`com.heytap.mydevices`）来说，在下一包广播到达之前它什么都不知道，
 * 界面就会显示宿主自己的单值电量，直到耳机恰好掉了 1% 才刷新。
 *
 * 所以蓝牙进程（唯一能读到华为私有电量协议的地方）每次发布电量时往 `Settings.Global` 写一行快照，
 * 别的进程启动时先读它，再等后续广播覆盖。
 */
internal object ColorOsBatterySnapshot {
    /** 与 ColorOS 自有的 `huaweipods_` 键区分开，避免和 [ColorOsBatteryFlags] 撞名。 */
    const val SETTINGS_KEY = "huaweipods_battery_snapshot"

    private const val SEPARATOR = '|'
    private const val MIN_FIELDS = 7

    /** 编码成一行的形式：`ADDR|left|leftConn|right|rightConn|case|caseConn|name`。 */
    fun encode(pod: ColorOsPodBattery): String = listOf(
        ColorOsPodBatteryStore.key(pod.address),
        pod.left.toString(),
        if (pod.leftConnected) "1" else "0",
        pod.right.toString(),
        if (pod.rightConnected) "1" else "0",
        pod.case.toString(),
        if (pod.caseConnected) "1" else "0",
        // 名字里可能出现分隔符，进编码前先剔除，保证解码时字段数固定。
        pod.name.orEmpty().replace(SEPARATOR, ' '),
    ).joinToString(SEPARATOR.toString())

    /** 解不出来（缺字段 / 数字非法 / 地址为空）就返回 null，绝不用默认值伪造电量。 */
    fun decode(raw: String?): ColorOsPodBattery? {
        val fields = raw?.trim()?.takeIf(String::isNotEmpty)?.split(SEPARATOR) ?: return null
        if (fields.size < MIN_FIELDS) return null
        val address = fields[0].trim().takeIf(String::isNotEmpty) ?: return null
        val left = fields[1].toIntOrNull() ?: return null
        val right = fields[3].toIntOrNull() ?: return null
        val case = fields[5].toIntOrNull() ?: return null
        return ColorOsPodBattery(
            address = address,
            left = left,
            leftConnected = fields[2] == "1",
            right = right,
            rightConnected = fields[4] == "1",
            case = case,
            caseConnected = fields[6] == "1",
            name = fields.getOrNull(7)?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    fun save(context: Context, pod: ColorOsPodBattery) {
        runCatching {
            Settings.Global.putString(context.contentResolver, SETTINGS_KEY, encode(pod))
        }
    }

    fun load(context: Context): ColorOsPodBattery? = runCatching {
        decode(Settings.Global.getString(context.contentResolver, SETTINGS_KEY))
    }.getOrNull()

    /** 把快照灌回内存表；已经有广播数据时不覆盖。返回是否发生了回填。 */
    fun restoreInto(context: Context, store: (ColorOsPodBattery) -> Unit = ColorOsPodBatteryStore::put): Boolean {
        val pod = load(context) ?: return false
        if (ColorOsPodBatteryStore.get(pod.address) != null) return false
        store(pod)
        return true
    }
}

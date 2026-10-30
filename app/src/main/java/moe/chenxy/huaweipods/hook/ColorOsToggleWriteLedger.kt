package moe.chenxy.huaweipods.hook

/** 详情页快捷开关的写入键。 */
internal enum class ColorOsToggleKey { LOW_LATENCY, WEAR_DETECTION }

/** 超时未确认的写入：调用方应把界面回滚到 `!requested`。 */
internal class ExpiredToggleWrite(
    val address: String,
    val key: ColorOsToggleKey,
    val requested: Boolean,
)

/**
 * 详情页开关的乐观写入记录。
 *
 * 点击后界面先按目标状态显示，随后以蓝牙进程的回读 / 写入结果广播为准；如果耳机没有响应
 * （AT 写入丢失、会话断开、机型不支持），超时后必须回滚，否则页面会长期显示相反状态。
 */
internal class ColorOsToggleWriteLedger(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private class Pending(val requested: Boolean, val deadline: Long)

    private val writes = HashMap<String, MutableMap<ColorOsToggleKey, Pending>>()

    /** 记录一次写入请求；同一开关的旧请求被新请求替换。 */
    @Synchronized
    fun begin(address: String, key: ColorOsToggleKey, requested: Boolean, now: Long) {
        val perDevice = writes.getOrPut(address.uppercase()) { HashMap() }
        perDevice[key] = Pending(requested, now + timeoutMillis)
    }

    /** 是否有仍在等待回报的写入。 */
    @Synchronized
    fun isPending(address: String, key: ColorOsToggleKey, now: Long): Boolean {
        val pending = writes[address.uppercase()]?.get(key) ?: return false
        return now < pending.deadline
    }

    /**
     * 收到回报：清除挂起记录。
     *
     * @return 挂起时的请求值；没有挂起返回 null。
     */
    @Synchronized
    fun complete(address: String, key: ColorOsToggleKey): Boolean? {
        val normalized = address.uppercase()
        val perDevice = writes[normalized] ?: return null
        val removed = perDevice.remove(key) ?: return null
        if (perDevice.isEmpty()) writes.remove(normalized)
        return removed.requested
    }

    /** 取走所有已超时的写入，供调用方回滚界面。 */
    @Synchronized
    fun expired(now: Long): List<ExpiredToggleWrite> {
        val result = mutableListOf<ExpiredToggleWrite>()
        val addresses = writes.keys.toList()
        addresses.forEach { address ->
            val perDevice = writes[address] ?: return@forEach
            val keys = perDevice.keys.toList()
            keys.forEach { key ->
                val pending = perDevice[key] ?: return@forEach
                if (now < pending.deadline) return@forEach
                perDevice.remove(key)
                result += ExpiredToggleWrite(address, key, pending.requested)
            }
            if (perDevice.isEmpty()) writes.remove(address)
        }
        return result
    }

    @Synchronized
    fun clear() {
        writes.clear()
    }

    companion object {
        /** 蓝牙进程一次写入加回读通常在一秒内完成，留出重试余量。 */
        const val DEFAULT_TIMEOUT_MILLIS = 3_000L
    }
}

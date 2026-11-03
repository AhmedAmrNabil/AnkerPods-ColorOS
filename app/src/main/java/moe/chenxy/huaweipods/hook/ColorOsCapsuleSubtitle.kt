package moe.chenxy.huaweipods.hook

/** 只读取胶囊身份、类型和副标题，不依赖构造函数是否执行。 */
internal class ColorOsCapsuleSubtitle(type: Class<*>) {
    val getter = type.getDeclaredMethod("getSubTitle").apply { isAccessible = true }
    private val getMac = type.getDeclaredMethod("getMac").apply { isAccessible = true }
    private val getType = type.getDeclaredMethod("getCapsuleType").apply { isAccessible = true }

    fun rewrite(capsule: Any, raw: String?, chinese: Boolean): String? {
        val address = getMac.invoke(capsule) as? String ?: return null
        return rewriteCapsuleSubtitle(
            raw = raw,
            address = address,
            capsuleType = (getType.invoke(capsule) as? Enum<*>)?.name,
            pod = ColorOsPodBatteryStore.get(address),
            chinese = chinese,
        )
    }
}

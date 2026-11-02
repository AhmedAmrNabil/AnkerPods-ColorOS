package moe.chenxy.huaweipods.hook

/**
 * 「我的设备」的 seedling 卡片数据注入。
 *
 * 设备空间的卡片条（控制中心里的设备卡）不是 SystemUI 自己拼的，也没有一个 `BlueToothCardView`：
 * `com.heytap.mydevices` 构建 `SeedlingCardData` 交给 seedling SDK，SystemUI 只按模板渲染
 * （见 docs §9.19/§9.21）。
 *
 * 实测 `SeedlingCardData` 一共 20 个字段：
 * ```
 * deviceId icon capsuleType capsuleInfoPrimaryText primaryInfo secondaryInfo
 * batteryProgress isCardShowBattery isShowBattery isLowPower isReConnectable
 * isShowButton buttonText buttonTextColor backgroundColor lightColor lightPosition
 * themeColor progressColor progressBgColor
 * ```
 * 电量只有一个 `batteryProgress: Int` 槽位——宿主天然只能显示一个数字。所以这里在构造函数
 * 执行前改**文本**参数：凡是电量形状的字符串，用模块的三段数据重写（规则见 [rewriteSeedlingCardText]）。
 */
internal object ColorOsSeedlingCardBatteryHook : HookContext() {
    private const val TAG = "HuaweiPods-SeedlingCard"
    private const val DATA_CLASS = "com.oplus.mydevices.domain.entities.cards.SeedlingCardData"
    private const val MAX_PROBES = 80

    private val DIGITS = Regex("\\d+")
    private val probed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun onHook() {
        runCatching { hookCardData() }
            .onSuccess { Log.i(TAG, "seedling card data hook installed") }
            .onFailure { Log.w(TAG, "seedling card data hook unavailable", it) }
    }

    private fun hookCardData() {
        val constructors = findClass(DATA_CLASS).declaredConstructors
        Log.i(TAG, "seedling card constructors=${constructors.size}")
        for (constructor in constructors) {
            constructor.isAccessible = true
            hookConstructorBefore(constructor) {
                runCatching {
                    val triple = ColorOsBatterySummaryInjector.currentTriple() ?: return@runCatching
                    val newArgs = args.toMutableList()
                    var changed = false
                    for (index in newArgs.indices) {
                        val raw = newArgs[index] as? String ?: continue
                        if (raw.isEmpty() || raw.length > 64) continue
                        if (!raw.contains('%') && !raw.contains("电量")) continue
                        val rewritten = rewriteSeedlingCardText(raw, triple)
                        if (probed.size < MAX_PROBES && probed.add(DIGITS.replace(raw, "#"))) {
                            Log.i(TAG, "card text=\"$raw\" -> \"${rewritten ?: raw}\"")
                        }
                        if (rewritten != null && rewritten != raw) {
                            newArgs[index] = rewritten
                            changed = true
                        }
                    }
                    if (changed) proceedWithArgs(*newArgs.toTypedArray())
                }
            }
        }
    }
}

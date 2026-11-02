package moe.chenxy.huaweipods.hook

import android.view.View
import android.widget.TextView

/**
 * SystemUI（控制中心 / 通知栏）电量文本探针。
 *
 * 背景：ColorOS 的「蓝牙卡片」既不读我们的数据，也没有一个叫 `BlueToothCardView` 的类——
 * 两个候选宿主（SystemUI 的 9 个 dex、`/system_ext/app/SystemUIPlugin`）里都没有按蓝牙命名的
 * 卡片类，卡片是 seedling 数据驱动 + 通用壳渲染的（见 docs §9.19）。所以先不猜类名，直接
 * 在 `TextView.setText` 上挂探针，把「谁在显示带 % 的文本」这件事打出来：
 *
 * ```
 * probe text="蓝牙" view=TextView chain=TextView <- LinearLayout <- ... id=bluetooth_name
 * ```
 *
 * 拿到 view 的类名/父链/资源 id 之后再回填真正的改写逻辑。
 *
 * 安全约束（SystemUI 崩了就没有状态栏，必须保守）：
 * - 全程 `runCatching`，任何异常都不往外抛；
 * - 只读 `args[0]`，不改写；本类不调用 `proceedWithArgs`；
 * - 同一条文本只记一次，最多 300 条，避免刷爆日志。
 */
internal object ColorOsSystemUiBatteryHook : HookContext() {
    private const val TAG = "HuaweiPods-SystemUi"
    private const val MAX_PROBES = 300
    private const val MAX_TEXT_LENGTH = 96

    private val DIGITS = Regex("\\d+")
    private val probed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun onHook() {
        runCatching { hookBatteryText() }
            .onSuccess { Log.i(TAG, "SystemUI battery text probe installed") }
            .onFailure { Log.w(TAG, "SystemUI battery text probe unavailable", it) }
    }

    private fun hookBatteryText() {
        val setText = TextView::class.java.getDeclaredMethod("setText", CharSequence::class.java)
        hookBefore(setText) {
            runCatching {
                val raw = (args[0] as? CharSequence)?.toString() ?: return@runCatching
                if (raw.length !in 1..MAX_TEXT_LENGTH) return@runCatching
                if (!raw.contains('%')) return@runCatching
                // 充电通知每秒刷新功率，按原文去重会被刷爆；按「数字归一化后的形状」去重。
                if (raw.contains('℃') || raw.contains(" W |")) return@runCatching
                val view = instance as? TextView
                val shape = DIGITS.replace(raw, "#")
                if (probed.size < MAX_PROBES && probed.add(shape)) {
                    Log.i(
                        TAG,
                        "probe text=\"$raw\" view=${view?.javaClass?.name} " +
                            "chain=${viewChain(view)} id=${viewId(view)}",
                    )
                }
            }
        }
    }

    private fun viewChain(view: View?): String {
        val builder = StringBuilder()
        var current: View? = view
        var depth = 0
        while (current != null && depth < 4) {
            if (depth > 0) builder.append(" <- ")
            builder.append(current.javaClass.simpleName)
            current = current.parent as? View
            depth++
        }
        return builder.toString()
    }

    private fun viewId(view: View?): String {
        val id = view?.id ?: return "-"
        return runCatching { view.resources.getResourceEntryName(id) }
            .getOrDefault("0x" + id.toString(16))
    }
}

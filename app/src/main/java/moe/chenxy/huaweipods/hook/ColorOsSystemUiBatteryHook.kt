package moe.chenxy.huaweipods.hook

import android.service.quicksettings.Tile
import android.view.View
import android.widget.TextView

/**
 * SystemUI（控制中心）里的耳机电量补位。
 *
 * 侦察结论先写清楚，免得后面又被名字带偏（证据见 docs §9.22）：
 *
 * - 手机上的「蓝牙 2 部设备」卡片在平板上不存在。平板这一格是 QS 磁贴，未连接显示
 *   `蓝牙 / 开`，连上耳机后显示 `HUAWEI FreeClip / 已连接`，**没有任何电量位**。
 * - 这一格不是 `TextView`：在 `TextView.setText` 上按「已连接」抓了一整轮，SystemUI 里一条
 *   都没命中；`Tile.setSubtitle` 全程也只出现 `null` / 空串 / `Enabled`。原因是 ColorOS 17 的
 *   QS 面板已经 Compose 化（`com/android/systemui/qs/panels/ui/compose/`、`qs/composefragment/`）。
 * - 卡片数据也不在 SystemUI：`SeedlingCardData` / `CapsuleInfo` 都在 `com.heytap.mydevices`
 *   进程里构建（§9.21），那边已经各挂了钩子。
 *
 * 所以这一格只做一件低风险的事：QS 磁贴本来就有副标题槽位，宿主拿它写「已连接」，我们用
 * `Tile.setSubtitle` 把它换成三段电量；判身份用 `Tile.label`（磁贴主标题就是设备名）。
 * Compose 文本不去碰——那要挂在 `AnnotatedString` 上，跟版本强绑定，收益配不上风险。
 *
 * 安全约束（SystemUI 崩了就没有状态栏，必须保守）：
 * - 全程 `runCatching`，异常一律不外抛；
 * - 只有「标签命中我们的耳机」且「手里有三段数据」时才改 `subtitle`，其余原样放行；
 * - `TextView.setText` 那条探针只在 DEBUG 日志级别出声。
 */
internal object ColorOsSystemUiBatteryHook : HookContext() {
    private const val TAG = "HuaweiPods-SystemUi"
    private const val MAX_PROBES = 300
    private const val MAX_TEXT_LENGTH = 96

    private val DIGITS = Regex("\\d+")
    private val probed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun onHook() {
        runCatching { hookTileSubtitle() }
            .onSuccess { Log.i(TAG, "quick settings tile subtitle hook installed") }
            .onFailure { Log.w(TAG, "quick settings tile subtitle hook unavailable", it) }
        runCatching { hookBatteryText() }
            .onSuccess { Log.d(TAG, "SystemUI battery text probe installed") }
            .onFailure { Log.w(TAG, "SystemUI battery text probe unavailable", it) }
    }

    /**
     * `Tile.setSubtitle`：宿主把「已连接」写进副标题时，改成三段电量。
     *
     * 用 `Tile.label` 判身份而不是维护 WeakHashMap——宿主每次刷新都会先 `setLabel` 再
     * `setSubtitle`，直接读当前标签最稳，也不怕对象被复用。
     */
    private fun hookTileSubtitle() {
        val setSubtitle = Tile::class.java.getDeclaredMethod("setSubtitle", CharSequence::class.java)
        hookBefore(setSubtitle) {
            runCatching {
                val tile = instance as? Tile ?: return@runCatching
                val label = runCatching { tile.label?.toString() }.getOrNull()
                val pod = ColorOsBatterySummaryInjector.currentPod() ?: return@runCatching
                if (!seedlingCardMentionsPod(listOf(label), pod)) return@runCatching
                val text = capsuleBatteryText(colorOsBatteryTriple(pod)) ?: return@runCatching
                val current = (args[0] as? CharSequence)?.toString()
                if (current == text) return@runCatching
                Log.i(TAG, "tile subtitle rewritten: label=\"$label\" \"$current\" -> \"$text\"")
                proceedWithArgs(text)
            }
        }
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
                    // 纯探针：只在 LogLevel=DEBUG 时出声，release 默认不刷日志。
                    Log.d(
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

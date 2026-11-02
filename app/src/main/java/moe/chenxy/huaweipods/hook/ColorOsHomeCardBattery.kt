package moe.chenxy.huaweipods.hook

import java.util.Locale

/**
 * 设备空间首页卡片（`HomeCardView`）的三段电量浮层逻辑。
 *
 * 首页卡片是 `HomeCardView` + `BatteryLottieView`（Lottie 电量动画），只画一个数字，
 * 既不读 `h()` 返回的 `QuickCardDeviceData` 电池槽，也不走 `BlueToothCardView` 的 `MULTI` 布局
 * （真机验证见 `docs/coloros17-audit-report-20261030.md` §8）。所以三段数字只能自己挂一行文本上去。
 *
 * 这个文件只放**纯逻辑**：文本怎么拼、卡片跟耳机怎么配对，方便单测覆盖；
 * 真正的视图插入在 [ColorOsDeviceSpaceHook] 里。
 */
internal object ColorOsHomeCardBattery {

    /** 三段之间的分隔符；卡片一行放不下太长的串，用空格分开就够。 */
    const val SEPARATOR = " "

    // 卡片一行只有一百多 dp：中文标签加数字实测会被截断成 `盒 100 …`，所以用单字母标签：
    // L = 左耳、R = 右耳、C = 盒子（case）。缺段时字母还在，位置不会错位。
    private const val LEFT_LABEL = "L"
    private const val RIGHT_LABEL = "R"
    private const val CASE_LABEL = "C"

    /**
     * 拼出浮层文本；只有一段时返回 `null` —— 宿主自己已经画了那个数字，再叠一行没意义。
     */
    fun textFor(left: Int?, right: Int?, case: Int?): String? {
        val parts = buildList {
            left?.let { add("$LEFT_LABEL$it") }
            right?.let { add("$RIGHT_LABEL$it") }
            case?.let { add("$CASE_LABEL$it") }
        }
        if (parts.size < 2) return null
        return parts.joinToString(SEPARATOR)
    }

    /**
     * 卡片和电量快照是否配对。
     *
     * 卡片上只有设备名（`FreeClip`），广播里带的是完整名（`HUAWEI FreeClip`），
     * 所以做规范化后双向包含匹配；任何一边为空都不匹配，宁可少画也不要画错卡。
     */
    fun matches(cardName: String?, podName: String?): Boolean {
        val card = normalize(cardName)
        val pod = normalize(podName)
        if (card.isEmpty() || pod.isEmpty()) return false
        return card.contains(pod) || pod.contains(card)
    }

    private fun normalize(raw: String?): String =
        raw.orEmpty()
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
}

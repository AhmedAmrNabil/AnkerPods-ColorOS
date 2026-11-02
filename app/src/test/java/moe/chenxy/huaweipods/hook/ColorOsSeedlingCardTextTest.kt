package moe.chenxy.huaweipods.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * seedling 卡片（控制中心设备卡）的三段文本规则测试。
 *
 * 这些用例锁的是「卡片的三个候选字段到底会被改成什么」：
 * 完整摘要、带连接态前缀的单值、裸百分比三条路径，加上不该动的情况。
 */
class ColorOsSeedlingCardTextTest {

    private val full = ColorOsBatteryTriple(left = 100, right = 100, case = 88)
    private val earbudsOnly = ColorOsBatteryTriple(left = 100, right = 100, case = null)
    private val caseOnly = ColorOsBatteryTriple(left = null, right = null, case = 88)

    @Test
    fun `bare percentage expands to three parts`() {
        assertEquals("左 100%，右 100%，盒 88%", rewriteSeedlingCardText("100%", full))
    }

    @Test
    fun `bare percentage with padding still expands`() {
        assertEquals("左 100%，右 100%，盒 88%", rewriteSeedlingCardText("  92 % ", full))
    }

    @Test
    fun `bare percentage expands when only earbuds are online`() {
        assertEquals("左 100%，右 100%", rewriteSeedlingCardText("100%", earbudsOnly))
    }

    @Test
    fun `bare percentage stays untouched when only the case is online`() {
        assertNull(rewriteSeedlingCardText("88%", caseOnly))
    }

    @Test
    fun `single value with connection prefix becomes three parts`() {
        assertEquals(
            "已连接。电量： 左 100%，右 100%，盒 88%。",
            rewriteSeedlingCardText("已连接。电量：100%。", full),
        )
    }

    @Test
    fun `single value without connection prefix is ignored`() {
        assertNull(rewriteSeedlingCardText("电量：85%", full))
    }

    @Test
    fun `two part summary gains the case`() {
        assertEquals(
            "使用中，电量：左 100%，右 100%，盒 88%",
            rewriteSeedlingCardText("使用中，电量：左100%，右100%", full),
        )
    }

    @Test
    fun `unrelated seedling text is ignored`() {
        assertNull(rewriteSeedlingCardText("已连接", full))
    }

    @Test
    fun `null and blank inputs are ignored`() {
        assertNull(rewriteSeedlingCardText(null, full))
        assertNull(rewriteSeedlingCardText("", full))
    }

    @Test
    fun `null triple is ignored`() {
        assertNull(rewriteSeedlingCardText("100%", null))
    }

    @Test
    fun `over long text is ignored`() {
        assertNull(rewriteSeedlingCardText("电量：" + "1".repeat(120), full))
    }
}

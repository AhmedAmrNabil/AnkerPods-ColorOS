package moe.chenxy.huaweipods.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---- 身份门禁：SeedlingCardData 是所有设备共用的模型 ----

    private val pod = ColorOsPodBattery(
        address = "30:A9:98:CB:C4:54",
        left = 100,
        leftConnected = true,
        right = 100,
        rightConnected = true,
        case = 88,
        caseConnected = true,
        name = "HUAWEI FreeClip",
    )

    @Test
    fun `card matches by md5 device id`() {
        val values = listOf("f68a5d82181e48edd39eb1824f43c1fa", "FreeClip", "已连接")
        assertTrue(seedlingCardMentionsPod(values, pod))
    }

    @Test
    fun `card matches by device name`() {
        assertTrue(seedlingCardMentionsPod(listOf("HUAWEI FreeClip", "100%"), pod))
    }

    @Test
    fun `other device card is rejected`() {
        val values = listOf("一加 15", "85%", "9f2c1b0a7d3e4f5a6b7c8d9e0f1a2b3c")
        assertFalse(seedlingCardMentionsPod(values, pod))
    }

    @Test
    fun `missing pod or empty values are rejected`() {
        assertFalse(seedlingCardMentionsPod(listOf("HUAWEI FreeClip"), null))
        assertFalse(seedlingCardMentionsPod(emptyList(), pod))
    }

    @Test
    fun `short fragments do not match by name`() {
        assertFalse(seedlingCardMentionsPod(listOf("Fre"), pod))
    }

    // ---- 控制中心胶囊卡（CapsuleInfo） ----

    @Test
    fun `capsule matches by mac`() {
        assertTrue(capsuleMatchesPod(listOf("30:A9:98:CB:C4:54", "HUAWEI FreeClip", "已连接"), pod))
    }

    @Test
    fun `capsule matches lower case mac`() {
        assertTrue(capsuleMatchesPod(listOf("30:a9:98:cb:c4:54"), pod))
    }

    @Test
    fun `other capsule is rejected`() {
        assertFalse(capsuleMatchesPod(listOf("AA:BB:CC:DD:EE:FF", "一加 15", "85%"), pod))
    }

    @Test
    fun `capsule subtitle uses compact three part text`() {
        assertEquals("左 100% 右 100% 盒 88%", capsuleBatteryText(full))
        assertEquals("左 100% 右 100%", capsuleBatteryText(earbudsOnly))
        assertNull(capsuleBatteryText(caseOnly))
        assertNull(capsuleBatteryText(null))
    }

    @Test
    fun `connection label and battery text are candidates`() {
        assertTrue(isCapsuleBatteryCandidate("已连接"))
        assertTrue(isCapsuleBatteryCandidate("使用中"))
        assertTrue(isCapsuleBatteryCandidate("电量：100%"))
        assertTrue(isCapsuleBatteryCandidate("85%"))
        assertFalse(isCapsuleBatteryCandidate("HUAWEI FreeClip"))
        assertFalse(isCapsuleBatteryCandidate("30:A9:98:CB:C4:54"))
        assertFalse(isCapsuleBatteryCandidate(""))
        assertFalse(isCapsuleBatteryCandidate(null))
    }
}

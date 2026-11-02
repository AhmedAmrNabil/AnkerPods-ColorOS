package moe.chenxy.huaweipods.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 宿主电量摘要改写：只在"认得出是电量摘要"时补段，其它文本一律不动。
 *
 * - 列表行：真机原文 `使用中，电量：左 100%，右 100%`，宿主没有盒子的格式化逻辑。
 * - 详细页：真机原文 `已开启。电量：98%。`，宿主只给单值。
 */
class ColorOsSettingsBatteryTextTest {

    @Test
    fun `appends case to the connected summary`() {
        assertEquals(
            "使用中，电量：左 100%，右 100%，盒 45%",
            ColorOsSettingsBatteryText.rewrite("使用中，电量：左 100%，右 100%", 100, 100, 45),
        )
    }

    @Test
    fun `keeps english colon separator`() {
        assertEquals(
            "Connected, battery: 左 98%，右 97%，盒 60%",
            ColorOsSettingsBatteryText.rewrite("Connected, battery: 左 98%，右 97%", 98, 97, 60),
        )
    }

    @Test
    fun `drops missing parts instead of writing placeholders`() {
        assertEquals(
            "使用中，电量：左 100%，盒 45%",
            ColorOsSettingsBatteryText.rewrite("使用中，电量：左 100%，右 12%", 100, null, 45),
        )
    }

    @Test
    fun `returns null when nothing changes`() {
        assertNull(ColorOsSettingsBatteryText.rewrite("使用中，电量：左 100%，右 100%，盒 45%", 100, 100, 45))
    }

    @Test
    fun `returns null when only one part is available`() {
        assertNull(ColorOsSettingsBatteryText.rewrite("使用中，电量：左 100%，右 100%", 100, null, null))
    }

    @Test
    fun `ignores text that is not a battery summary`() {
        assertNull(ColorOsSettingsBatteryText.rewrite("蓝牙", 100, 100, 45))
        assertNull(ColorOsSettingsBatteryText.rewrite("已连接", 100, 100, 45))
        assertNull(ColorOsSettingsBatteryText.rewrite("使用中", 100, 100, 45))
        assertNull(ColorOsSettingsBatteryText.rewrite(null, 100, 100, 45))
        assertNull(ColorOsSettingsBatteryText.rewrite("   ", 100, 100, 45))
    }

    @Test
    fun `ignores colon without a battery body`() {
        assertNull(ColorOsSettingsBatteryText.rewrite("使用中，电量：", 100, 100, 45))
        assertNull(ColorOsSettingsBatteryText.rewrite("手机名称：一加 15", 100, 100, 45))
    }

    @Test
    fun `clamps out of range values`() {
        assertEquals(
            "使用中，电量：左 100%，右 0%",
            ColorOsSettingsBatteryText.rewrite("使用中，电量：左 120%，右 -5%", 120, -5, null),
        )
    }

    @Test
    fun `expands the single value shown by the device detail page`() {
        assertEquals(
            "已开启。电量： 左 97%，右 98%，盒 100%。",
            ColorOsSettingsBatteryText.rewriteSingleValue(
                "已开启。电量：98%。",
                ColorOsBatteryTriple(left = 97, right = 98, case = 100),
            ),
        )
    }

    @Test
    fun `single value keeps the ascii period`() {
        assertEquals(
            "已开启。电量： 左 97%，右 98%.",
            ColorOsSettingsBatteryText.rewriteSingleValue(
                "已开启。电量：98%.",
                ColorOsBatteryTriple(left = 97, right = 98, case = null),
            ),
        )
    }

    @Test
    fun `single value needs at least two parts`() {
        assertNull(
            ColorOsSettingsBatteryText.rewriteSingleValue(
                "已开启。电量：98%。",
                ColorOsBatteryTriple(left = 97, right = null, case = null),
            ),
        )
        assertNull(ColorOsSettingsBatteryText.rewriteSingleValue("已开启。电量：98%。", null))
    }

    @Test
    fun `single value ignores text that is not a lone percentage`() {
        val triple = ColorOsBatteryTriple(left = 97, right = 98, case = 100)
        assertNull(ColorOsSettingsBatteryText.rewriteSingleValue("已开启。电量：左 97%，右 98%", triple))
        assertNull(ColorOsSettingsBatteryText.rewriteSingleValue("已开启。", triple))
        assertNull(ColorOsSettingsBatteryText.rewriteSingleValue("电量：98% 已连接", triple))
        assertNull(ColorOsSettingsBatteryText.rewriteSingleValue(null, triple))
    }

    @Test
    fun `single value is ignored without a connection prefix`() {
        // 手机自己的电池页就是这种形状，必须一个字符都不动。
        val triple = ColorOsBatteryTriple(left = 88, right = 92, case = 100)
        assertNull(ColorOsSettingsBatteryText.rewriteSingleValue("电量：85%", triple))
        assertNull(ColorOsSettingsBatteryText.rewriteSingleValue("电池电量：85%。", triple))
    }

    @Test
    fun `single value is rewritten for other connection prefixes`() {
        val triple = ColorOsBatteryTriple(left = 88, right = 92, case = 100)
        assertEquals(
            "已连接。电量： 左 88%，右 92%，盒 100%。",
            ColorOsSettingsBatteryText.rewriteSingleValue("已连接。电量：92%。", triple),
        )
    }
}

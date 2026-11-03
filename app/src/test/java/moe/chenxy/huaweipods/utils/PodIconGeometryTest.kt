package moe.chenxy.huaweipods.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PodIconGeometryTest {
    private val delta = 0.01f

    @Test
    fun `tall case image fits inside the square without cropping`() {
        // Issue #8: FreeClip 2 典藏版盒子图 237×365，原样当大图标时上下被 ColorOS 裁掉。
        val bounds = squareFitBounds(237, 365, 192, NOTIFICATION_ICON_CONTENT_SCALE)
        assertEquals(192 * NOTIFICATION_ICON_CONTENT_SCALE, bounds.height, delta)
        assertTrue(bounds.width < bounds.height)
        assertTrue(bounds.left >= 0f && bounds.top >= 0f)
        assertTrue(bounds.right <= 192f && bounds.bottom <= 192f)
        assertEquals(237f / 365f, bounds.width / bounds.height, delta)
    }

    @Test
    fun `content is centered`() {
        val bounds = squareFitBounds(237, 365, 192, NOTIFICATION_ICON_CONTENT_SCALE)
        assertEquals(192f - bounds.right, bounds.left, delta)
        assertEquals(192f - bounds.bottom, bounds.top, delta)
    }

    @Test
    fun `wide and square images keep the longer side at the content scale`() {
        val wide = squareFitBounds(640, 320, 192, 0.86f)
        assertEquals(192 * 0.86f, wide.width, delta)
        assertEquals(wide.width / 2f, wide.height, delta)
        val square = squareFitBounds(640, 640, 96, 0.9f)
        assertEquals(96 * 0.9f, square.width, delta)
        assertEquals(96 * 0.9f, square.height, delta)
    }

    @Test
    fun `degenerate sizes do not escape the canvas`() {
        val bounds = squareFitBounds(0, -5, 192, 0.86f)
        assertTrue(bounds.left >= 0f && bounds.right <= 192f)
        assertTrue(bounds.top >= 0f && bounds.bottom <= 192f)
    }
}

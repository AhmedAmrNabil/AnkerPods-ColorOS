package moe.chenxy.huaweipods.utils

/** 纯几何结果，不依赖 android.graphics，方便 JVM 单测。 */
internal data class IconTargetBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** 通知大图标内容占方形画布的比例；留出 ColorOS 圆角/圆形裁切所需的透明边距。 */
internal const val NOTIFICATION_ICON_CONTENT_SCALE = 0.86f

/**
 * 把 [sourceWidth]×[sourceHeight] 的内容等比缩放、居中放进边长 [canvasSize] 的方形画布，
 * 较长一边占画布的 [contentScale]。任何比例的输入都不会超出画布。
 */
internal fun squareFitBounds(
    sourceWidth: Int,
    sourceHeight: Int,
    canvasSize: Int,
    contentScale: Float,
): IconTargetBounds {
    val width = sourceWidth.coerceAtLeast(1)
    val height = sourceHeight.coerceAtLeast(1)
    val scale = minOf(canvasSize * contentScale / width, canvasSize * contentScale / height)
    val targetWidth = width * scale
    val targetHeight = height * scale
    val left = (canvasSize - targetWidth) / 2f
    val top = (canvasSize - targetHeight) / 2f
    return IconTargetBounds(left, top, left + targetWidth, top + targetHeight)
}

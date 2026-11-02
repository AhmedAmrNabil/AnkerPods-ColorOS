package moe.chenxy.huaweipods.hook

import android.app.Activity
import android.app.Application
import java.util.concurrent.atomic.AtomicInteger

/**
 * AOSP Settings 的蓝牙「设备详细信息」页（`BluetoothDeviceDetailActivity`）集成。
 *
 * ColorOS 17 把大部分设置搬进了 `com.oplus.wirelesssettings`，但点进某台设备的详细信息
 * 仍留在 `com.android.settings`。那一页只有一句 `已开启。电量：98%。` —— 单值，来自
 * `ColorOsSystemBatteryBridge` 写进 Android 标准电量的"在线耳机较低一侧"。
 *
 * 这里把同一个进程里的这句文本补成三段（`已开启。电量： 左 97%，右 98%，盒 100%。`）。
 * 只在**这一页处于前台**时才动窗口文本：`com.android.settings` 还管着手机自己的电池页，
 * 那些页面的「电量：xx%」是手机电量，绝不能拿耳机数据去覆盖。
 */
internal object ColorOsDeviceDetailBatteryHook : HookContext() {
    private const val TAG = "HuaweiPods-ColorOsDeviceDetail"
    private const val DETAIL_ACTIVITY_MARKER = "BluetoothDeviceDetail"

    /** 详细信息页在前台的实例数（同一页可能被重建，用计数比布尔更稳）。 */
    private val visible = AtomicInteger(0)

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val rewrite = ColorOsBatterySummaryInjector.Rewrite { text, triple ->
        ColorOsSettingsBatteryText.rewriteSingleValue(text, triple)
    }

    override fun onHook() {
        runCatching { hookActivityVisibility() }
            .onSuccess { Log.i(TAG, "device detail battery hook installed") }
            .onFailure { Log.w(TAG, "device detail battery hook unavailable", it) }
        runCatching { hookApplicationCreate() }
            .onFailure { Log.w(TAG, "device detail battery receiver unavailable", it) }
    }

    private fun hookActivityVisibility() {
        val onResume = Activity::class.java.getDeclaredMethod("onResume")
        hookAfter(onResume) {
            val activity = instance as? Activity ?: return@hookAfter
            if (!activity.isDetailPage()) return@hookAfter
            visible.incrementAndGet()
            Log.i(TAG, "detail page resumed class=${activity.javaClass.name}")
            // 宿主的 summary 由 Preference 在 onResume 之后才绑定，扫一次不够，补两刷。
            main.post { ColorOsBatterySummaryInjector.patch(TAG, rewrite) { visible.get() > 0 } }
            main.postDelayed({ ColorOsBatterySummaryInjector.patch(TAG, rewrite) { visible.get() > 0 } }, 400)
            main.postDelayed({ ColorOsBatterySummaryInjector.patch(TAG, rewrite) { visible.get() > 0 } }, 1_200)
        }
        val onPause = Activity::class.java.getDeclaredMethod("onPause")
        hookBefore(onPause) {
            val activity = instance as? Activity ?: return@hookBefore
            if (!activity.isDetailPage()) return@hookBefore
            visible.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        }
    }

    private fun hookApplicationCreate() {
        val onCreate = Application::class.java.getDeclaredMethod("onCreate")
        hookAfter(onCreate) {
            val app = instance as? Application ?: return@hookAfter
            ColorOsBatterySummaryInjector.install(app, TAG, rewrite) { visible.get() > 0 }
        }
    }

    private fun Activity.isDetailPage(): Boolean =
        javaClass.name.contains(DETAIL_ACTIVITY_MARKER, ignoreCase = true)
}

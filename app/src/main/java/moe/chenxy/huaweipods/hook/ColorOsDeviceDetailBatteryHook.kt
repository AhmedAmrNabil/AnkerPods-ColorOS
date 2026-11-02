package moe.chenxy.huaweipods.hook

import android.app.Activity
import android.app.Application
import android.widget.TextView
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

    private val rewrite = ColorOsBatterySummaryInjector.Rewrite { text, triple ->
        ColorOsSettingsBatteryText.rewriteSingleValue(text, triple)
    }

    override fun onHook() {
        runCatching { hookActivityVisibility() }
            .onSuccess { Log.i(TAG, "device detail battery hook installed") }
            .onFailure { Log.w(TAG, "device detail battery hook unavailable", it) }
        runCatching { hookApplicationCreate() }
            .onFailure { Log.w(TAG, "device detail battery receiver unavailable", it) }
        runCatching { hookSummarySetText() }
            .onSuccess { Log.i(TAG, "device detail summary setText hook installed") }
            .onFailure { Log.w(TAG, "device detail summary setText hook unavailable", it) }
    }

    private fun hookActivityVisibility() {
        val onResume = Activity::class.java.getDeclaredMethod("onResume")
        hookAfter(onResume) {
            val activity = instance as? Activity ?: return@hookAfter
            if (!activity.isDetailPage()) return@hookAfter
            visible.incrementAndGet()
            Log.i(TAG, "detail page resumed class=${activity.javaClass.name}")
            ColorOsBatterySummaryInjector.attach(activity, TAG, rewrite) { visible.get() > 0 }
        }
        val onPause = Activity::class.java.getDeclaredMethod("onPause")
        hookBefore(onPause) {
            val activity = instance as? Activity ?: return@hookBefore
            if (!activity.isDetailPage()) return@hookBefore
            visible.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        }
    }

    /**
     * 在宿主动手写文本的那一刻改写，比「扫窗口」可靠：宿主每次重新绑定摘要都会走到这里，
     * 不存在「我们改完又被覆盖回去」的时序问题。只在详细信息页前台时生效。
     */
    private fun hookSummarySetText() {
        val setText = TextView::class.java.getDeclaredMethod("setText", CharSequence::class.java)
        hookBefore(setText) {
            if (visible.get() <= 0) return@hookBefore
            val text = args[0] as? CharSequence ?: return@hookBefore
            val triple = ColorOsBatterySummaryInjector.currentTriple() ?: return@hookBefore
            rewrite.apply(text, triple)?.let { proceedWithArgs(it) }
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

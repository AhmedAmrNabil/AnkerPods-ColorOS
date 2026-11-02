package moe.chenxy.huaweipods.hook

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import moe.chenxy.huaweipods.broadcast.HuaweiPodsBroadcastTrustPolicy
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.addHuaweiPodsAction

/**
 * 宿主界面里的电量文本补全器：收模块的电量广播 → 遍历本进程窗口 → 按策略改写文本。
 *
 * 两处宿主各有各的模板缺陷，但拿数、找视图、重刷的流程完全一样，所以抽到这里：
 * - 蓝牙设置列表行 `使用中，电量：左 100%，右 100%`（缺盒子）→ [ColorOsSettingsBatteryText.rewrite]
 * - 蓝牙设备详细信息页 `已开启。电量：98%。`（单值）→ [ColorOsSettingsBatteryText.rewriteSingleValue]
 *
 * 只在展示层改文本：不碰 `IDevice`、不改宿主的数据源，认不出目标文本就一个字符都不动。
 */
internal object ColorOsBatterySummaryInjector {

    /** 改写策略：[text] 是视图中现有文本，[triple] 是本进程收到的最新三段电量。 */
    fun interface Rewrite {
        fun apply(text: CharSequence?, triple: ColorOsBatteryTriple): String?
    }

    private val main = Handler(Looper.getMainLooper())

    /** 在宿主 `Application.onCreate` 之后调用：注册接收器并补三刷（首屏渲染比 onCreate 晚）。 */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun install(
        app: Application,
        tag: String,
        rewrite: Rewrite,
        enabled: () -> Boolean = { true },
    ) {
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!isTrustedSender(context, intent.action, senderUid(), tag)) return
                    val address = intent.getStringExtra("address")?.uppercase() ?: return
                    ColorOsPodBatteryStore.put(
                        ColorOsPodBattery(
                            address = address,
                            left = intent.getIntExtra("left_battery", 0),
                            leftConnected = intent.getBooleanExtra("left_connected", false),
                            right = intent.getIntExtra("right_battery", 0),
                            rightConnected = intent.getBooleanExtra("right_connected", false),
                            case = intent.getIntExtra("case_battery", 0),
                            caseConnected = intent.getBooleanExtra("case_connected", false),
                            name = intent.getStringExtra("device_name")?.takeIf(String::isNotBlank),
                        ),
                    )
                    main.post { patch(tag, rewrite, enabled) }
                }
            }
            app.registerReceiver(
                receiver,
                IntentFilter().apply { addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED) },
                Context.RECEIVER_EXPORTED,
            )
            main.post { patch(tag, rewrite, enabled) }
            main.postDelayed({ patch(tag, rewrite, enabled) }, 1_200)
            main.postDelayed({ patch(tag, rewrite, enabled) }, 3_000)
            Log.i(tag, "battery summary injector installed")
        }.onFailure { Log.w(tag, "battery summary injector unavailable", it) }
    }

    /** 立刻扫一遍窗口，供 `Activity.onResume` 之类需要即时生效的地方调用。 */
    fun patch(tag: String, rewrite: Rewrite, enabled: () -> Boolean = { true }) {
        if (!enabled()) return
        runCatching {
            val triple = ColorOsPodBatteryStore.snapshot()
                .mapNotNull(::colorOsBatteryTriple)
                .maxByOrNull { it.parts.size }
                ?: return
            val manager = Class.forName("android.view.WindowManagerGlobal")
                .getDeclaredMethod("getInstance")
                .invoke(null)
            val roots = (getObjectField(manager, "mViews") as? List<*>)
                ?.filterIsInstance<View>()?.take(16) ?: return
            var patched = 0
            roots.forEach { root ->
                if (!root.isAttachedToWindow) return@forEach
                val pending = ArrayDeque<View>()
                pending.add(root)
                var visited = 0
                while (pending.isNotEmpty() && visited++ < 512) {
                    val view = pending.removeFirst()
                    if (view is TextView) {
                        val rewritten = rewrite.apply(view.text, triple)
                        if (rewritten != null) {
                            view.text = rewritten
                            patched++
                        }
                    }
                    if (view is ViewGroup) {
                        repeat(minOf(view.childCount, 64)) { index -> pending.add(view.getChildAt(index)) }
                    }
                }
            }
            if (patched > 0) {
                Log.i(tag, "battery summary patched rows=$patched triple=$triple")
            }
        }.onFailure { Log.w(tag, "unable to patch battery summary", it) }
    }

    /**
     * 只认蓝牙进程发来的状态广播（模块的 HFP 控制器就跑在那个进程里），
     * 与「我的设备」侧的 `shouldAcceptMyDevicesStateBroadcast` 同一套判断。
     */
    private fun isTrustedSender(context: Context, action: String?, senderUid: Int, tag: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return runCatching {
            val packages = context.packageManager.getPackagesForUid(senderUid)
            HuaweiPodsBroadcastTrustPolicy.shouldAcceptMyDevicesStateBroadcast(action, packages?.firstOrNull())
        }.getOrElse {
            Log.w(tag, "unable to resolve battery broadcast sender", it)
            false
        }
    }

    /** `getSentFromUid()` 只在接收器实例上可读，调用处包一层兼容判断。 */
    private fun BroadcastReceiver.senderUid(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) sentFromUid else -1
}

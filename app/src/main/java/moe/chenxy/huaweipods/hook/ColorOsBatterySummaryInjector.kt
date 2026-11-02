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
import java.util.concurrent.atomic.AtomicBoolean
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
 *
 * **入口为什么不是 `Application.onCreate`**：模块热重载（重装 APK 后 LSPosed 重新注入）时
 * `Application.onCreate` 早就跑过了。只在它里面注册接收器，等于新代码永远不生效——真机上表
 * 现为「hook installed 日志有、页面却没变」。所以入口是幂等的 [attach]，`Application.onCreate`
 * 与 `Activity.onResume` 都会调用它。
 */
internal object ColorOsBatterySummaryInjector {

    /** 改写策略：[text] 是视图中现有文本，[triple] 是本进程收到的最新三段电量。 */
    fun interface Rewrite {
        fun apply(text: CharSequence?, triple: ColorOsBatteryTriple): String?
    }

    private val main = Handler(Looper.getMainLooper())
    private val receiverRegistered = AtomicBoolean(false)

    /** 供快照回填与惰性注册接收器用的进程级 Context。 */
    @Volatile
    private var appContext: Context? = null

    /** 幂等入口：绑定 Context、按需注册接收器、立刻补刷三遍。 */
    fun attach(
        context: Context,
        tag: String,
        rewrite: Rewrite,
        enabled: () -> Boolean = { true },
    ) {
        appContext = context.applicationContext ?: context
        if (!enabled()) return
        runCatching { registerReceiverIfNeeded(tag, rewrite, enabled) }
            .onFailure { Log.w(tag, "battery summary receiver unavailable", it) }
        schedulePatches(tag, rewrite, enabled)
    }

    /** 兼容旧调用点：`Application.onCreate` 之后走同一条幂等路径。 */
    fun install(
        app: Application,
        tag: String,
        rewrite: Rewrite,
        enabled: () -> Boolean = { true },
    ) = attach(app, tag, rewrite, enabled)

    /** `onResume` 之后按 0/400/1200ms 各扫一次（宿主绑定文本常晚于 `onResume`）。 */
    fun schedulePatches(tag: String, rewrite: Rewrite, enabled: () -> Boolean = { true }) {
        main.post { patch(tag, rewrite, enabled) }
        main.postDelayed({ patch(tag, rewrite, enabled) }, 400)
        main.postDelayed({ patch(tag, rewrite, enabled) }, 1_200)
    }

    /**
     * 当前可用的三段电量（优先段数最多的那条），没数据时顺带回填一次快照。
     *
     * `TextView.setText` 钩子需要它：宿主每次重新绑定文本都要重新算一遍，
     * 不能只依赖广播到达那一刻的窗口扫描。
     */
    fun currentTriple(): ColorOsBatteryTriple? = colorOsBatteryTriple(currentPod())

    /**
     * 当前用于卡片的那条耳机记录。
     *
     * 与 [currentTriple] 同一条选择策略（段数最多者），但保留地址和名字——seedling 卡片要按
     * `md5(大写 MAC)` / 设备名先把「这张卡是不是我们的耳机」判掉，否则会把别的设备的电量也换成我们的数字。
     */
    fun currentPod(): ColorOsPodBattery? {
        appContext?.let { context -> ColorOsBatterySnapshot.restoreInto(context) }
        return ColorOsPodBatteryStore.snapshot()
            .mapNotNull { pod -> colorOsBatteryTriple(pod)?.let { pod to it } }
            .maxByOrNull { (_, triple) -> triple.parts.size }
            ?.first
    }
    /** 立刻扫一遍窗口。 */
    fun patch(tag: String, rewrite: Rewrite, enabled: () -> Boolean = { true }) {
        if (!enabled()) return
        runCatching {
            val triple = currentTriple()
            if (triple == null) {
                Log.i(tag, "battery summary scan skipped: no battery data")
                return
            }
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
            Log.i(tag, "battery summary scan roots=${roots.size} patched=$patched triple=$triple")
        }.onFailure { Log.w(tag, "unable to patch battery summary", it) }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiverIfNeeded(tag: String, rewrite: Rewrite, enabled: () -> Boolean) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val context = appContext
        if (context == null) {
            receiverRegistered.set(false)
            return
        }
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
        try {
            context.registerReceiver(
                receiver,
                IntentFilter().apply { addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED) },
                Context.RECEIVER_EXPORTED,
            )
            Log.i(tag, "battery summary injector installed")
        } catch (t: Throwable) {
            receiverRegistered.set(false)
            throw t
        }
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

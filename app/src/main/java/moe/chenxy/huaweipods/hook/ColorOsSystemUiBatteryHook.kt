package moe.chenxy.huaweipods.hook

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import moe.chenxy.huaweipods.BuildConfig

/**
 * 原生蓝牙磁贴直接写 QSTile.State，并不调用 TileService 的 Tile.setSubtitle。
 * 在渲染前补 secondaryLabel，不依赖 TextView 或 Compose 的具体实现（见 docs §9.22）。
 */
internal object ColorOsSystemUiBatteryHook : HookContext() {
    private const val TAG = "HuaweiPods-SystemUi"
    private const val MAX_PROBES = 300
    private const val MAX_TEXT_LENGTH = 96
    private const val TILE_CLASS = "com.oplus.systemui.qs.tiles.OplusBluetoothTile"
    private const val STATE_CLASS = "com.android.systemui.plugins.qs.QSTile\$BooleanState"

    private val DIGITS = Regex("\\d+")
    private val probed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val lifecycleLock = Any()
    private val tiles = WeakHashMap<Any, Unit>()
    @Volatile private var source: ColorOsSystemUiBatterySource? = null
    @Volatile private var stopped = false
    private val refresh = Runnable { if (!stopped) refreshTiles() }

    override fun onHook() {
        runCatching { hookTileState() }
            .onSuccess { Log.i(TAG, "native Bluetooth tile state hook installed") }
            .onFailure { Log.w(TAG, "native Bluetooth tile state hook unavailable", it) }
        runCatching {
            hookAfter(
                Application::class.java.getDeclaredMethod("attach", Context::class.java)
                    .apply { isAccessible = true },
            ) { (instance as? Application)?.let(::attachSource) }
        }.onFailure { Log.w(TAG, "SystemUI application hook unavailable", it) }
        currentApplicationOrNull()?.let(::attachSource)
        // 排查用探针：会在 SystemUI 每次写文本时运行，并记录带 % 的界面文字（可能含通知内容），
        // 所以只在 Debug 构建安装。
        if (BuildConfig.DEBUG) {
            runCatching { hookBatteryText() }
                .onSuccess { Log.d(TAG, "SystemUI battery text probe installed") }
                .onFailure { Log.w(TAG, "SystemUI battery text probe unavailable", it) }
        }
    }

    private fun attachSource(context: Context) {
        if (stopped) return
        main.post {
            synchronized(lifecycleLock) {
                if (stopped || source != null) return@synchronized
                runCatching {
                    val next = ColorOsSystemUiBatterySource.forContext(context.applicationContext) {
                        main.removeCallbacks(refresh)
                        if (!stopped) main.post(refresh)
                    }
                    next.start()
                    source = next
                    Log.i(TAG, "SystemUI battery source attached")
                    main.post(refresh)
                }.onFailure { Log.w(TAG, "SystemUI battery source unavailable", it) }
            }
        }
    }

    override fun onClose() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        try {
            synchronized(lifecycleLock) {
                val previous = source
                source = null
                previous?.close()
            }
        } finally {
            // HookContext 已关闭，宿主下一次刷新将重新生成原生文案。
            refreshTiles()
            synchronized(tiles) { tiles.clear() }
        }
    }

    private fun hookTileState() {
        val method = findClass(TILE_CLASS).getDeclaredMethod(
            "handleUpdateState", findClass(STATE_CLASS), Any::class.java,
        ).apply { isAccessible = true }
        hookAfter(method) {
            runCatching {
                val tile = instance ?: return@runCatching
                synchronized(tiles) { tiles[tile] = Unit }
                val state = args[0] ?: return@runCatching
                val controller = tile.field("mController").get(tile) ?: return@runCatching
                val devices = controller.method("getConnectedDevices").invoke(controller) as? List<*>
                    ?: return@runCatching
                if (devices.size != 1) return@runCatching
                val addresses = devices.map { device ->
                    device?.method("getAddress")?.invoke(device) as? String
                }
                val secondary = state.field("secondaryLabel")
                val current = (secondary.get(state) as? CharSequence)?.toString() ?: return@runCatching
                val text = rewriteBluetoothTileSubtitle(
                    raw = current,
                    enabled = state.field("value").getBoolean(state) &&
                        controller.field("mEnabled").getBoolean(controller),
                    connected = controller.field("mConnectionState").getInt(controller) == 2 &&
                        state.field("state").getInt(state) == 2,
                    addresses = addresses,
                    pod = source?.get(addresses.single()),
                    chinese = java.util.Locale.getDefault().language == "zh",
                ) ?: return@runCatching
                if (text == current) return@runCatching
                secondary.set(state, text)
                for (name in listOf("labelDesc", "contentDescription")) {
                    runCatching {
                        val field = state.field(name)
                        val raw = (field.get(state) as? CharSequence)?.toString()
                        val replacement = if (name == "labelDesc" && isCapsuleBatteryCandidate(raw)) {
                            text
                        } else {
                            rewriteBluetoothTileDescription(raw, current, text)
                        }
                        replacement?.let { field.set(state, it) }
                    }
                }
                Log.d(TAG, "Bluetooth tile subtitle=\"$current\" -> \"$text\"")
            }.onFailure {
                if (probed.add("native-state-error")) Log.w(TAG, "Bluetooth tile state unavailable", it)
            }
        }
    }

    private fun refreshTiles() {
        val snapshot = synchronized(tiles) { tiles.keys.toList() }
        snapshot.forEach { tile ->
            runCatching { tile.method("refreshState", Any::class.java).invoke(tile, null) }
                .onFailure { Log.d(TAG, "Bluetooth tile refresh unavailable: ${it.javaClass.simpleName}") }
        }
    }

    private fun Any.field(name: String): Field {
        var type: Class<*>? = javaClass
        while (type != null) {
            val current = type
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let {
                return it.apply { isAccessible = true }
            }
            type = current.superclass
        }
        throw NoSuchFieldException(name)
    }

    private fun Any.method(name: String, vararg parameters: Class<*>): Method {
        var type: Class<*>? = javaClass
        while (type != null) {
            val current = type
            runCatching { current.getDeclaredMethod(name, *parameters) }.getOrNull()?.let {
                return it.apply { isAccessible = true }
            }
            type = current.superclass
        }
        throw NoSuchMethodException(name)
    }

    private fun hookBatteryText() {
        val setText = TextView::class.java.getDeclaredMethod("setText", CharSequence::class.java)
        hookBefore(setText) {
            runCatching {
                val raw = (args[0] as? CharSequence)?.toString() ?: return@runCatching
                if (raw.length !in 1..MAX_TEXT_LENGTH) return@runCatching
                if (!raw.contains('%')) return@runCatching
                // 充电通知每秒刷新功率，按原文去重会被刷爆；按「数字归一化后的形状」去重。
                if (raw.contains('℃') || raw.contains(" W |")) return@runCatching
                val view = instance as? TextView
                val shape = DIGITS.replace(raw, "#")
                if (probed.size < MAX_PROBES && probed.add(shape)) {
                    Log.d(
                        TAG,
                        "probe text=\"$raw\" view=${view?.javaClass?.name} " +
                            "chain=${viewChain(view)} id=${viewId(view)}",
                    )
                }
            }
        }
    }

    private fun viewChain(view: View?): String {
        val builder = StringBuilder()
        var current: View? = view
        var depth = 0
        while (current != null && depth < 4) {
            if (depth > 0) builder.append(" <- ")
            builder.append(current.javaClass.simpleName)
            current = current.parent as? View
            depth++
        }
        return builder.toString()
    }

    private fun viewId(view: View?): String {
        val id = view?.id ?: return "-"
        return runCatching { view.resources.getResourceEntryName(id) }
            .getOrDefault("0x" + id.toString(16))
    }
}

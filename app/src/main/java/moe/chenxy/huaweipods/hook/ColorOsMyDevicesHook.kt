package moe.chenxy.huaweipods.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import moe.chenxy.huaweipods.BuildConfig
import moe.chenxy.huaweipods.broadcast.HuaweiPodsBroadcastTrustPolicy
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.HuaweiWearDetectionController
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.pods.displayName
import moe.chenxy.huaweipods.pods.encodeHuaweiDeviceRouteForBroadcast
import moe.chenxy.huaweipods.pods.supportsLowLatencyControl
import moe.chenxy.huaweipods.pods.isSupported
import moe.chenxy.huaweipods.pods.resolveHuaweiDeviceRoute
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.addHuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.sendIdentitySharingBroadcast

/**
 * ColorOS "My Devices" integration.
 *
 * - ColorOS 16.1（com.heytap.mydevices 17.4.15）：ViewBinding 详情页，复用 `row_bt_setting` 行。
 * - ColorOS 17（com.heytap.mydevices 17.25.10）：详情页改为 Preference 页面，复用
 *   `pref_bt_audio_settings`（打开系统蓝牙设备设置）这一项。
 *
 * 两代页面都只改写系统已有的入口，不新增依赖 COUI 私有资源的 View。17.x 的 androidx 类成员
 * 均被混淆，因此按类型结构定位绑定方法，并通过 `android.R.id.title/summary` 修改文字。
 * 所有反射都有兜底，未知版本保持系统原样。
 */
object ColorOsMyDevicesHook : HookContext() {
    private const val TAG = "HuaweiPods-ColorOsMyDevices"
    private const val LEGACY_DETAIL_FRAGMENT =
        "com.oplus.mydevices.bluetooth.fragment.BtDetailPageFragment"
    private const val PREFERENCE_DETAIL_FRAGMENT =
        "com.heytap.mydevices.plugin.bluetooth.fragment.BtDetailPageFragment"
    private const val PREFERENCE_CLASS = "androidx.preference.Preference"
    private const val SETTINGS_PREFERENCE_KEY = "pref_bt_audio_settings"
    private const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
    private val bluetoothAddressPattern = Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    private var statusReceiverContext: Context? = null
    private var statusReceiver: BroadcastReceiver? = null
    private val main = Handler(Looper.getMainLooper())
    private val activeRows = WeakHashMap<View, ColorOsHeadsetRow>()
    private val preferenceRows = WeakHashMap<Any, ColorOsHeadsetRow>()
    private val hostRows = WeakHashMap<View, HostRowSnapshot>()
    private val latestSummaries = ConcurrentHashMap<String, String>()
    private val latestAncModes = ConcurrentHashMap<String, NoiseControlMode>()
    private val latestLowLatency = ConcurrentHashMap<String, Boolean>()
    private val latestWearDetection = ConcurrentHashMap<String, Boolean>()
    private val toggleWrites = ColorOsToggleWriteLedger()
    private val hookedBindMethods = ConcurrentHashMap.newKeySet<Method>()

    override fun onHook() {
        val legacy = runCatching { hookLegacyDetailPage() }
        val preference = runCatching { hookPreferenceDetailPage() }
        if (legacy.isSuccess) Log.i(TAG, "My Devices 16.x detail hook installed")
        if (preference.isSuccess) Log.i(TAG, "My Devices 17.x preference detail hook installed")
        if (legacy.isFailure && preference.isFailure) {
            Log.w(
                TAG,
                "Unsupported My Devices build; leaving the stock page unchanged",
                preference.exceptionOrNull() ?: legacy.exceptionOrNull()!!,
            )
        }
    }

    override fun onClose() {
        hookedBindMethods.clear()
        val receiver = statusReceiver
        val context = statusReceiverContext
        if (receiver != null && context != null) {
            runCatching { context.unregisterReceiver(receiver) }
        }
        statusReceiver = null
        statusReceiverContext = null
        main.removeCallbacksAndMessages(null)
        // 关闭/热重载必须把宿主行还原：文字、点击和 chip 只属于当前世代。先还原再清状态，
        // 否则旧世代改过的文字会永久留在系统页面上。
        val rows = synchronized(activeRows) { activeRows.keys.toList() }
        val restored = rows.isEmpty() || runOnMainThreadBlocking {
            rows.forEach { row -> runCatching { restoreHostRow(row) } }
        }
        if (!restored) Log.w(TAG, "Unable to restore My Devices rows on close (rows=${rows.size})")
        synchronized(activeRows) { activeRows.clear() }
        synchronized(preferenceRows) { preferenceRows.clear() }
        synchronized(hostRows) { hostRows.clear() }
        toggleWrites.clear()
        latestSummaries.clear()
        latestAncModes.clear()
        latestLowLatency.clear()
        latestWearDetection.clear()
    }

    /**
     * 把宿主行恢复成注入前的样子：标题/摘要文字、摘要可见性、无障碍描述与点击监听。
     *
     * androidx `Preference` 会在下一次绑定里重新安装自己的点击监听，因此取不到原始监听时清空
     * 监听是安全的；取得到（隐藏 API 未被拦截）就精确还原。
     */
    private fun restoreHostRow(row: View) {
        removeQuickControls(row)
        val snapshot = synchronized(hostRows) { hostRows.remove(row) } ?: return
        row.contentDescription = snapshot.contentDescription
        snapshot.title?.let { (view, text) -> view.text = text }
        snapshot.summary?.let { (view, text) ->
            view.text = text
            view.visibility = snapshot.summaryVisibility
        }
        snapshot.labels.forEach { (view, text) -> view.text = text }
        row.setOnClickListener(snapshot.clickListener)
        row.isClickable = snapshot.clickable
    }

    private fun snapshotHostRow(row: View) {
        if (synchronized(hostRows) { hostRows.containsKey(row) }) return
        val title = row.findViewById<TextView>(android.R.id.title)
        val summary = row.findViewById<TextView>(android.R.id.summary)
        val snapshot = HostRowSnapshot(
            contentDescription = row.contentDescription,
            title = title?.let { it to it.text },
            summary = summary?.let { it to it.text },
            summaryVisibility = summary?.visibility ?: View.GONE,
            labels = if (title == null) collectTextViews(row).map { it to it.text } else emptyList(),
            clickListener = readHostClickListener(row),
            clickable = row.isClickable,
        )
        synchronized(hostRows) { hostRows[row] = snapshot }
    }

    /** `View.getListenerInfo()` 属于隐藏 API；被拦截时返回 null，由宿主下次绑定恢复点击。 */
    private fun readHostClickListener(row: View): View.OnClickListener? = runCatching {
        val info = View::class.java.getDeclaredMethod("getListenerInfo")
            .apply { isAccessible = true }
            .invoke(row) ?: return@runCatching null
        info.javaClass.getDeclaredField("mOnClickListener")
            .apply { isAccessible = true }
            .get(info) as? View.OnClickListener
    }.getOrNull()

    private fun hookLegacyDetailPage() {
        val method = findMethod(
            LEGACY_DETAIL_FRAGMENT,
            "onViewCreated",
            View::class.java,
            Bundle::class.java,
        )
        hookAfter(method) {
            val fragment = instance ?: return@hookAfter
            val root = args.firstOrNull() as? View ?: return@hookAfter
            runCatching { attachLegacyEntry(fragment, root) }
                .onFailure { Log.w(TAG, "Unable to attach HuaweiPods entry", it) }
        }
    }

    private fun hookPreferenceDetailPage() {
        val bindMethods = preferenceBindMethods(findClass(PREFERENCE_CLASS))
        check(bindMethods.isNotEmpty()) { "Preference bind method not found" }
        val onViewCreated = findMethod(
            PREFERENCE_DETAIL_FRAGMENT,
            "onViewCreated",
            View::class.java,
            Bundle::class.java,
        )
        hookAfter(onViewCreated) {
            val fragment = instance ?: return@hookAfter
            val root = args.firstOrNull() as? View ?: return@hookAfter
            runCatching { attachPreferenceEntry(fragment, root) }
                .onFailure { Log.w(TAG, "Unable to attach HuaweiPods preference entry", it) }
        }
        bindMethods.forEach(::hookPreferenceBind)
    }

    /**
     * COUIPreference 等子类会重写绑定方法并在 super 之后继续设置标题/摘要。沿目标 Preference 的
     * 类链逐层 Hook，最外层重写的 after 回调最后执行，保证入口文字不被子类覆盖。
     */
    private fun hookPreferenceBindChain(preferenceClass: Class<*>) {
        var type: Class<*>? = preferenceClass
        while (type != null && type != Any::class.java) {
            preferenceBindMethods(type).forEach(::hookPreferenceBind)
            if (type.name == PREFERENCE_CLASS) break
            type = type.superclass
        }
    }

    private fun hookPreferenceBind(method: Method) {
        if (!hookedBindMethods.add(method)) return
        hookAfter(method) {
            val preference = instance ?: return@hookAfter
            val holder = args.firstOrNull() ?: return@hookAfter
            runCatching { onPreferenceBound(preference, holder) }
                .onFailure { Log.w(TAG, "Unable to update bound HuaweiPods entry", it) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attachLegacyEntry(fragment: Any, root: View) {
        val address = getObjectField(fragment, "mMac") as? String
        val viewModel = getObjectField(fragment, "viewModel")
        val device = bluetoothDevice(address)
        val deviceName = runCatching { callMethod(viewModel, "u") as? String }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: runCatching { device?.name ?: device?.alias }.getOrNull()
        val route = resolveHuaweiDeviceRoute(address, deviceName)
        if (!route.isSupported || device == null) return

        val row = legacySettingsRow(fragment, root) ?: run {
            Log.w(TAG, "Huawei device found but row_bt_setting is unavailable")
            return
        }
        val entry = ColorOsHeadsetRow(
            address = device.address.uppercase(),
            deviceName = route.displayName,
            route = route,
            allowLabelFallback = true,
        )
        synchronized(activeRows) { activeRows[row] = entry }
        customizeRow(row, entry, latestSummaries[entry.address])
        row.setOnClickListener {
            launchHuaweiPods(row.context, device)
        }
        registerStatusReceiver(row.context)
        requestCurrentStatus(row.context)
        requestWearDetectionState(row.context, device, route)
        Log.i(TAG, "Attached entry for ${route.displayName}")
    }

    @SuppressLint("MissingPermission")
    private fun attachPreferenceEntry(fragment: Any, root: View) {
        val address = preferenceFragmentAddress(fragment) ?: return
        val device = bluetoothDevice(address) ?: return
        val deviceName = runCatching { device.alias ?: device.name }.getOrNull()
        val route = resolveHuaweiDeviceRoute(address, deviceName)
        if (!route.isSupported) return

        val preference = runCatching {
            callMethod(fragment, "findPreference", SETTINGS_PREFERENCE_KEY)
        }.getOrNull() ?: run {
            Log.w(TAG, "Huawei device found but $SETTINGS_PREFERENCE_KEY is unavailable")
            return
        }
        runCatching { hookPreferenceBindChain(preference.javaClass) }
            .onFailure { Log.w(TAG, "Unable to hook ${preference.javaClass.name} binding", it) }
        synchronized(preferenceRows) {
            preferenceRows[preference] = ColorOsHeadsetRow(
                address = device.address.uppercase(),
                deviceName = route.displayName,
                route = route,
            )
        }
        registerStatusReceiver(root.context)
        requestCurrentStatus(root.context)
        requestWearDetectionState(root.context, device, route)
        Log.i(TAG, "Attached preference entry for ${route.displayName}")
    }

    private fun onPreferenceBound(preference: Any, holder: Any) {
        val itemView = getObjectField(holder, "itemView") as? View ?: return
        val entry = synchronized(preferenceRows) { preferenceRows[preference] }
        if (entry == null) {
            // RecyclerView 复用了原来承载 HuaweiPods 入口的 View；系统绑定已恢复其文字和点击，
            // 只需移除注入的降噪切换。
            synchronized(activeRows) { activeRows.remove(itemView) }
            removeQuickControls(itemView)
            return
        }
        synchronized(activeRows) { activeRows[itemView] = entry }
        customizeRow(itemView, entry, latestSummaries[entry.address])
        val device = bluetoothDevice(entry.address) ?: return
        itemView.setOnClickListener { launchHuaweiPods(it.context, device) }
    }

    private fun legacySettingsRow(fragment: Any, root: View): LinearLayout? {
        val bindingRow = runCatching {
            val binding = getObjectField(fragment, "_binding")
            getObjectField(binding, "h") as? LinearLayout
        }.getOrNull()
        if (bindingRow != null) return bindingRow

        val rowId = root.resources.getIdentifier("row_bt_setting", "id", packageName)
        return rowId.takeIf { it != 0 }?.let(root::findViewById)
    }

    /** 17.x 详情页只有一个保存 MAC 的 String 字段；按取值校验，不依赖混淆后的字段名。 */
    private fun preferenceFragmentAddress(fragment: Any): String? {
        var type: Class<*>? = fragment.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields
                .filter { it.type == String::class.java && !Modifier.isStatic(it.modifiers) }
                .forEach { field ->
                    val value = runCatching {
                        field.isAccessible = true
                        field.get(fragment) as? String
                    }.getOrNull()
                    if (value != null && bluetoothAddressPattern.matches(value)) return value
                }
            type = type.superclass
        }
        return null
    }

    private fun customizeRow(
        row: View,
        entry: ColorOsHeadsetRow,
        summary: String? = null,
    ) {
        snapshotHostRow(row)
        customizeRowText(row, entry.deviceName, summary, entry.allowLabelFallback)
        runCatching { attachQuickControls(row, entry) }
            .onFailure { Log.w(TAG, "Unable to attach quick controls", it) }
    }

    /**
     * @param allowLabelFallback 只有 16.x 的 `row_bt_setting` 缺少 `android.R.id.title/summary`，
     * 允许按文本顺序兜底。17.x 的 Preference 行必须命中系统 id：猜错 TextView 会改坏宿主其它
     * 文字，事后也无法判断归属。
     */
    private fun customizeRowText(
        row: View,
        deviceName: String,
        summary: String? = null,
        allowLabelFallback: Boolean = false,
    ) {
        row.isEnabled = true
        row.alpha = 1f
        row.contentDescription = "HuaweiPods, $deviceName"
        val summaryText = summary ?: if (
            Locale.getDefault().language == Locale.CHINESE.language
        ) {
            "$deviceName 电量与设置"
        } else {
            "$deviceName battery and settings"
        }
        val title = row.findViewById<TextView>(android.R.id.title)
        val summaryView = row.findViewById<TextView>(android.R.id.summary)
        if (title != null) {
            title.text = "HuaweiPods"
            summaryView?.apply {
                text = summaryText
                visibility = View.VISIBLE
            }
            return
        }
        if (!allowLabelFallback) {
            Log.w(TAG, "Row has no android.R.id.title; keeping the host text untouched")
            return
        }
        val labels = collectTextViews(row)
        when (labels.size) {
            0 -> Unit
            1 -> labels[0].text = "HuaweiPods · $deviceName"
            else -> {
                labels[0].text = "HuaweiPods"
                labels[1].text = summaryText
            }
        }
    }

    private fun registerStatusReceiver(context: Context) {
        if (statusReceiver != null) return
        val appContext = context.applicationContext ?: context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val received = intent ?: return
                if (!HuaweiPodsBroadcastTrustPolicy.shouldAcceptMyDevicesStateBroadcast(
                        received.action, sentFromPackage,
                    )
                ) {
                    Log.w(TAG, "Rejected My Devices state action=${received.action} sender=$sentFromPackage")
                    return
                }
                val action = HuaweiPodsAction.canonical(received.action)
                if (action == HuaweiPodsAction.ACTION_PODS_ANC_CHANGED) {
                    val address = received.getStringExtra("address")?.uppercase() ?: return
                    val mode = NoiseControlMode.fromBroadcastStatus(received.getIntExtra("status", 0))
                    if (!mode.isKnownMode()) return
                    latestAncModes[address] = mode
                    refreshQuickControls(address)
                    return
                }
                if (action == HuaweiPodsAction.ACTION_HUAWEI_LOW_LATENCY_CHANGED) {
                    val address = received.getStringExtra("address")?.uppercase() ?: return
                    if (!received.hasExtra(HuaweiPodsAction.EXTRA_HUAWEI_LOW_LATENCY_ENABLED)) return
                    // 蓝牙进程的回报（写入结果或回读）才算确认；没有回报的乐观写入交给超时回滚。
                    toggleWrites.complete(address, ColorOsToggleKey.LOW_LATENCY)
                    latestLowLatency[address] = received.getBooleanExtra(
                        HuaweiPodsAction.EXTRA_HUAWEI_LOW_LATENCY_ENABLED,
                        false,
                    )
                    if (!received.getBooleanExtra(
                            HuaweiPodsAction.EXTRA_HUAWEI_LOW_LATENCY_WRITE_SUCCESS,
                            true,
                        )
                    ) {
                        Log.i(TAG, "Low latency write rejected by the headset; showing the reported state")
                    }
                    refreshQuickControls(address)
                    return
                }
                if (action == HuaweiPodsAction.ACTION_HUAWEI_WEAR_DETECTION_CHANGED) {
                    val address = received.getStringExtra("address")?.uppercase() ?: return
                    if (!received.hasExtra(HuaweiPodsAction.EXTRA_HUAWEI_WEAR_DETECTION_ENABLED)) return
                    toggleWrites.complete(address, ColorOsToggleKey.WEAR_DETECTION)
                    latestWearDetection[address] = received.getBooleanExtra(
                        HuaweiPodsAction.EXTRA_HUAWEI_WEAR_DETECTION_ENABLED,
                        false,
                    )
                    refreshQuickControls(address)
                    return
                }
                if (action == HuaweiPodsAction.ACTION_PODS_DISCONNECTED) {
                    // 断连后不留旧状态：否则重新连上（甚至换成另一副耳机）前页面会一直显示上次的电量。
                    val address = received.getStringExtra("address")?.uppercase()
                    if (address != null) {
                        latestSummaries.remove(address)
                        latestAncModes.remove(address)
                        latestLowLatency.remove(address)
                        latestWearDetection.remove(address)
                    }
                    // 设备空间卡片的三段电量同源，断连时必须一起丢，否则卡上会留着上一副耳机的电量。
                    ColorOsPodBatteryStore.clear(address)
                    toggleWrites.clear()
                    if (address != null) refreshQuickControls(address)
                    return
                }
                if (action != HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED) return
                val address = received.getStringExtra("address")?.uppercase() ?: return
                ColorOsPodBatteryStore.put(
                    ColorOsPodBattery(
                        address = address,
                        left = received.getIntExtra("left_battery", 0),
                        leftConnected = received.getBooleanExtra("left_connected", false),
                        right = received.getIntExtra("right_battery", 0),
                        rightConnected = received.getBooleanExtra("right_connected", false),
                        case = received.getIntExtra("case_battery", 0),
                        caseConnected = received.getBooleanExtra("case_connected", false),
                    ),
                )
                val summary = colorOsBatterySummary(
                    leftBattery = received.getIntExtra("left_battery", 0),
                    leftConnected = received.getBooleanExtra("left_connected", false),
                    rightBattery = received.getIntExtra("right_battery", 0),
                    rightConnected = received.getBooleanExtra("right_connected", false),
                    caseBattery = received.getIntExtra("case_battery", 0),
                    caseConnected = received.getBooleanExtra("case_connected", false),
                    chinese = Locale.getDefault().language == Locale.CHINESE.language,
                ) ?: return
                latestSummaries[address] = summary
                synchronized(activeRows) { activeRows.entries.toList() }
                    .filter { (row, entry) -> row.isAttachedToWindow && entry.address == address }
                    .forEach { (row, entry) -> customizeRow(row, entry, summary) }
            }
        }
        runCatching {
            appContext.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED)
                    addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_ANC_CHANGED)
                    addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_DISCONNECTED)
                    addHuaweiPodsAction(HuaweiPodsAction.ACTION_HUAWEI_LOW_LATENCY_CHANGED)
                    addHuaweiPodsAction(HuaweiPodsAction.ACTION_HUAWEI_WEAR_DETECTION_CHANGED)
                },
                Context.RECEIVER_EXPORTED,
            )
        }.onSuccess {
            statusReceiver = receiver
            statusReceiverContext = appContext
        }.onFailure {
            Log.w(TAG, "Unable to register My Devices battery receiver", it)
        }
    }

    private fun requestCurrentStatus(context: Context) {
        runCatching {
            // 蓝牙进程按 sentFromPackage 校验控制广播，必须共享发送方身份。
            context.sendIdentitySharingBroadcast(Intent(HuaweiPodsAction.ACTION_REFRESH_STATUS).apply {
                setPackage(BLUETOOTH_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }.onFailure {
            Log.w(TAG, "Unable to request current headset status", it)
        }
    }

    /**
     * 在入口摘要下方加入按机型能力显示的快捷控制：降噪耳机为关闭 / 降噪 / 通透三段切换（ColorOS
     * 只给 AirPods 提供降噪控件），支持的型号另有低时延与佩戴检测开关。摘要所在容器不是纵向
     * LinearLayout 时不注入，保持系统原样。
     */
    private fun attachQuickControls(row: View, entry: ColorOsHeadsetRow) {
        val ancOptions = colorOsNoiseControlOptions(entry.route)
        val toggles = colorOsQuickToggles(entry.route)
        if (ancOptions.isEmpty() && toggles.isEmpty()) return
        val summary = row.findViewById<TextView>(android.R.id.summary)
            ?: collectTextViews(row).getOrNull(1)
            ?: return
        val container = summary.parent as? LinearLayout ?: return
        if (container.orientation != LinearLayout.VERTICAL) return
        val expectedChips = ancOptions.size + toggles.size
        val existing = container.findViewWithTag<LinearLayout>(QUICK_CONTROLS_TAG)
        if (existing == null || existing.chipCount() != expectedChips) {
            existing?.let(container::removeView)
            val controls = LinearLayout(row.context).apply {
                tag = QUICK_CONTROLS_TAG
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = row.dp(8) }
            }
            if (ancOptions.isNotEmpty()) {
                controls.addView(chipRow(row, ancOptions.map { mode ->
                    quickChip(row.context, mode, ancLabel(mode)) { view ->
                        val device = bluetoothDevice(entry.address) ?: return@quickChip
                        latestAncModes[entry.address] = mode
                        refreshQuickControls(entry.address)
                        requestAncMode(view.context, device, entry.route, mode)
                    }
                }))
            }
            if (toggles.isNotEmpty()) {
                controls.addView(chipRow(row, toggles.map { toggle ->
                    quickChip(row.context, toggle, toggleLabel(toggle, null)) { view ->
                        val device = bluetoothDevice(entry.address) ?: return@quickChip
                        onToggleClicked(view.context, device, entry, toggle)
                    }
                }).apply {
                    if (ancOptions.isNotEmpty()) {
                        (layoutParams as LinearLayout.LayoutParams).topMargin = row.dp(8)
                    }
                })
            }
            container.addView(controls)
        }
        updateQuickControls(row, entry)
    }

    private fun chipRow(row: View, chips: List<TextView>): LinearLayout =
        LinearLayout(row.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            chips.forEachIndexed { index, chip ->
                addView(chip, LinearLayout.LayoutParams(0, row.dp(32), 1f).apply {
                    if (index > 0) marginStart = row.dp(8)
                })
            }
        }

    private fun quickChip(
        context: Context,
        key: Any,
        label: String,
        onClick: (View) -> Unit,
    ): TextView = TextView(context).apply {
        tag = key
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        text = label
        isClickable = true
        setOnClickListener(onClick)
    }

    private fun LinearLayout.chipCount(): Int =
        (0 until childCount).sumOf { (getChildAt(it) as? ViewGroup)?.childCount ?: 0 }

    private fun refreshQuickControls(address: String) {
        synchronized(activeRows) { activeRows.entries.toList() }
            .filter { (row, entry) -> row.isAttachedToWindow && entry.address == address }
            .forEach { (row, entry) -> updateQuickControls(row, entry) }
    }

    /** 超时后把没拿到回报的开关回滚，避免页面长期显示与耳机相反的状态。 */
    private fun scheduleToggleExpiry() {
        main.removeCallbacks(toggleExpiry)
        main.postDelayed(toggleExpiry, ColorOsToggleWriteLedger.DEFAULT_TIMEOUT_MILLIS + 250L)
    }

    private val toggleExpiry = Runnable {
        val expired = toggleWrites.expired(SystemClock.elapsedRealtime())
        expired.forEach { write ->
            when (write.key) {
                ColorOsToggleKey.LOW_LATENCY -> latestLowLatency[write.address] = !write.requested
                ColorOsToggleKey.WEAR_DETECTION -> latestWearDetection[write.address] = !write.requested
            }
            Log.i(TAG, "Toggle ${write.key} unconfirmed; rolled back to ${!write.requested}")
            refreshQuickControls(write.address)
        }
    }

    private fun updateQuickControls(row: View, entry: ColorOsHeadsetRow) {
        val controls = row.findViewWithTag<LinearLayout>(QUICK_CONTROLS_TAG) ?: return
        val selectedAnc = latestAncModes[entry.address]
        val accent = row.context.themeColor(android.R.attr.colorAccent, Color.rgb(0x2A, 0x6D, 0xF4))
        val normalText = row.context.themeColor(android.R.attr.textColorPrimary, Color.DKGRAY)
        repeat(controls.childCount) { rowIndex ->
            val chipRow = controls.getChildAt(rowIndex) as? ViewGroup ?: return@repeat
            repeat(chipRow.childCount) { index ->
                val chip = chipRow.getChildAt(index) as? TextView ?: return@repeat
                val toggle = chip.tag as? ColorOsQuickToggle
                val toggleState = toggle?.let { toggleState(entry.address, it) }
                val active = if (toggle != null) toggleState == true else chip.tag == selectedAnc
                chip.background = GradientDrawable().apply {
                    cornerRadius = row.dp(16).toFloat()
                    if (active) {
                        setColor(accent)
                    } else {
                        setColor(Color.TRANSPARENT)
                        setStroke(row.dp(1), ColorStateList.valueOf(normalText).withAlpha(0x40))
                    }
                }
                chip.setTextColor(if (active) Color.WHITE else normalText)
                chip.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                chip.isSelected = active
                if (toggle != null) chip.text = toggleLabel(toggle, toggleState)
            }
        }
    }

    private fun removeQuickControls(itemView: View) {
        val controls = itemView.findViewWithTag<View>(QUICK_CONTROLS_TAG) ?: return
        (controls.parent as? ViewGroup)?.removeView(controls)
    }

    /** 蓝牙进程要求控制指令带地址和机型路由，并按 sentFromPackage 校验发送方。 */
    private fun sendControl(context: Context, action: String, device: BluetoothDevice, route: HuaweiDeviceRoute, fill: Intent.() -> Unit) {
        context.sendIdentitySharingBroadcast(Intent(action).apply {
            setPackage(BLUETOOTH_PACKAGE)
            putExtra("address", device.address)
            encodeHuaweiDeviceRouteForBroadcast(route)?.let {
                putExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE, it)
            }
            fill()
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    private fun requestAncMode(
        context: Context,
        device: BluetoothDevice,
        route: HuaweiDeviceRoute,
        mode: NoiseControlMode,
    ) {
        runCatching {
            sendControl(context, HuaweiPodsAction.ACTION_ANC_SELECT, device, route) {
                putExtra("status", mode.broadcastStatus)
            }
            Log.i(TAG, "Noise control requested mode=$mode")
        }.onFailure {
            Log.w(TAG, "Unable to request noise control mode=$mode", it)
        }
    }

    private fun toggleState(address: String, toggle: ColorOsQuickToggle): Boolean? = when (toggle) {
        // 低时延没有回读协议，未收到状态时按关闭显示（与模块界面的默认值一致）。
        ColorOsQuickToggle.LOW_LATENCY -> latestLowLatency[address] ?: false
        ColorOsQuickToggle.WEAR_DETECTION -> latestWearDetection[address]
    }

    private fun onToggleClicked(
        context: Context,
        device: BluetoothDevice,
        entry: ColorOsHeadsetRow,
        toggle: ColorOsQuickToggle,
    ) {
        when (toggle) {
            ColorOsQuickToggle.LOW_LATENCY -> {
                val enabled = latestLowLatency[entry.address] != true
                latestLowLatency[entry.address] = enabled
                toggleWrites.begin(
                    entry.address,
                    ColorOsToggleKey.LOW_LATENCY,
                    enabled,
                    SystemClock.elapsedRealtime(),
                )
                refreshQuickControls(entry.address)
                scheduleToggleExpiry()
                requestLowLatency(context, device, entry.route, enabled)
            }
            ColorOsQuickToggle.WEAR_DETECTION -> {
                // 状态未知时先回读，避免按猜测的状态写反。
                val current = latestWearDetection[entry.address] ?: run {
                    requestWearDetectionState(context, device, entry.route)
                    return
                }
                latestWearDetection[entry.address] = !current
                toggleWrites.begin(
                    entry.address,
                    ColorOsToggleKey.WEAR_DETECTION,
                    !current,
                    SystemClock.elapsedRealtime(),
                )
                refreshQuickControls(entry.address)
                scheduleToggleExpiry()
                runCatching {
                    sendControl(context, HuaweiPodsAction.ACTION_HUAWEI_WEAR_DETECTION_SET, device, entry.route) {
                        putExtra(HuaweiPodsAction.EXTRA_HUAWEI_WEAR_DETECTION_ENABLED, !current)
                    }
                    Log.i(TAG, "Wear detection requested enabled=${!current}")
                }.onFailure {
                    Log.w(TAG, "Unable to request wear detection enabled=${!current}", it)
                }
            }
        }
    }

    private fun requestWearDetectionState(context: Context, device: BluetoothDevice, route: HuaweiDeviceRoute) {
        if (!HuaweiWearDetectionController.supports(route)) return
        runCatching {
            sendControl(context, HuaweiPodsAction.ACTION_HUAWEI_WEAR_DETECTION_REFRESH, device, route) {}
        }.onFailure {
            Log.w(TAG, "Unable to request wear detection state", it)
        }
    }

    private fun requestLowLatency(
        context: Context,
        device: BluetoothDevice,
        route: HuaweiDeviceRoute,
        enabled: Boolean,
    ) {
        runCatching {
            sendControl(context, HuaweiPodsAction.ACTION_HUAWEI_LOW_LATENCY_SET, device, route) {
                putExtra(HuaweiPodsAction.EXTRA_HUAWEI_LOW_LATENCY_ENABLED, enabled)
            }
            Log.i(TAG, "Low latency requested enabled=$enabled")
        }.onFailure {
            Log.w(TAG, "Unable to request low latency enabled=$enabled", it)
        }
    }

    private fun ancLabel(mode: NoiseControlMode): String {
        val chinese = Locale.getDefault().language == Locale.CHINESE.language
        return when (mode) {
            NoiseControlMode.OFF -> if (chinese) "关闭" else "Off"
            NoiseControlMode.NOISE_CANCELLATION -> if (chinese) "降噪" else "Noise cancelling"
            NoiseControlMode.TRANSPARENCY -> if (chinese) "通透" else "Awareness"
            NoiseControlMode.UNKNOWN -> ""
        }
    }

    private fun toggleLabel(toggle: ColorOsQuickToggle, enabled: Boolean?): String =
        colorOsQuickToggleLabel(toggle, enabled, Locale.getDefault().language == Locale.CHINESE.language)

    private fun NoiseControlMode.isKnownMode(): Boolean = this != NoiseControlMode.UNKNOWN

    private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Context.themeColor(attr: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        if (!theme.resolveAttribute(attr, typedValue, true)) return fallback
        return if (typedValue.resourceId != 0) {
            runCatching { getColor(typedValue.resourceId) }.getOrDefault(fallback)
        } else {
            typedValue.data
        }
    }

    private fun collectTextViews(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) {
            repeat(view.childCount) { index -> addAll(collectTextViews(view.getChildAt(index))) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothDevice(address: String?): BluetoothDevice? {
        if (address.isNullOrBlank()) return null
        return runCatching { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) }.getOrNull()
    }

    private fun launchHuaweiPods(context: Context, device: BluetoothDevice) {
        val popupIntent = Intent(HuaweiPodsAction.ACTION_SHOW_PODS_UI).apply {
            setPackage(BuildConfig.APPLICATION_ID)
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(popupIntent) }
            .onFailure { error ->
                Log.w(TAG, "Popup unavailable; opening the module activity", error)
                runCatching {
                    context.startActivity(Intent().apply {
                        setClassName(
                            BuildConfig.APPLICATION_ID,
                            "moe.chenxy.huaweipods.MainActivity",
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }.onFailure { Log.e(TAG, "Unable to open HuaweiPods", it) }
            }
    }
}

/**
 * androidx `Preference.onBindViewHolder(PreferenceViewHolder)` 在厂商应用里会被 R8 改名；
 * 它是 Preference 上唯一接收 RecyclerView.ViewHolder 子类的实例方法，按结构定位。
 */
internal fun preferenceBindMethods(preferenceClass: Class<*>): List<Method> =
    preferenceClass.declaredMethods.filter { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.size == 1 &&
            isRecyclerViewHolderType(method.parameterTypes[0])
    }.onEach { it.isAccessible = true }

private fun isRecyclerViewHolderType(type: Class<*>): Boolean {
    var current: Class<*>? = type
    while (current != null && current != Any::class.java) {
        if (
            current.name.startsWith("androidx.recyclerview.widget.RecyclerView$") &&
            current.declaredFields.any { field ->
                field.name == "itemView" && View::class.java.isAssignableFrom(field.type)
            }
        ) {
            return true
        }
        current = current.superclass
    }
    return false
}

private data class ColorOsHeadsetRow(
    val address: String,
    val deviceName: String,
    val route: HuaweiDeviceRoute,
    /** 16.x 的 `row_bt_setting` 没有系统文本 id，允许按文本顺序兜底。 */
    val allowLabelFallback: Boolean = false,
)

/** 宿主行注入前的快照，用于关闭/热重载时精确还原。 */
private class HostRowSnapshot(
    val contentDescription: CharSequence?,
    val title: Pair<TextView, CharSequence>?,
    val summary: Pair<TextView, CharSequence>?,
    val summaryVisibility: Int,
    val labels: List<Pair<TextView, CharSequence>>,
    val clickListener: View.OnClickListener?,
    val clickable: Boolean,
)

private const val QUICK_CONTROLS_TAG = "huaweipods_quick_controls"

internal fun colorOsBatterySummary(
    leftBattery: Int,
    leftConnected: Boolean,
    rightBattery: Int,
    rightConnected: Boolean,
    caseBattery: Int,
    caseConnected: Boolean,
    chinese: Boolean,
): String? {
    val parts = buildList {
        if (leftConnected) add(if (chinese) "左 ${leftBattery.coerceIn(0, 100)}%" else "L ${leftBattery.coerceIn(0, 100)}%")
        if (rightConnected) add(if (chinese) "右 ${rightBattery.coerceIn(0, 100)}%" else "R ${rightBattery.coerceIn(0, 100)}%")
        if (caseConnected) add(if (chinese) "盒 ${caseBattery.coerceIn(0, 100)}%" else "Case ${caseBattery.coerceIn(0, 100)}%")
    }
    return parts.takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
}

package moe.chenxy.huaweipods.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.TextView
import android.util.Pair
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import moe.chenxy.huaweipods.BuildConfig
import moe.chenxy.huaweipods.backend.soundcore.SoundcoreDeviceIdentifier
import moe.chenxy.huaweipods.broadcast.HuaweiPodsBroadcastTrustPolicy
import moe.chenxy.huaweipods.pods.huaweiDeviceRoute
import moe.chenxy.huaweipods.pods.isSupported
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.addHuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.sendIdentitySharingBroadcast

/** ColorOS 17 蓝牙设置的默认三方耳机厂商白名单（`HfpAtVendorManager`）。 */
internal val COLOR_OS_SETTINGS_DEFAULT_3RD_VENDORS = listOf(2688, 1356, 301, 2016, 3686)

/** 与蓝牙设置相同的白名单解析：属性为逗号分隔的十六进制厂商 ID，缺省时使用内置列表。 */
internal fun colorOsSettingsThirdPartyVendors(property: String?): List<Int> {
    val raw = property?.trim().orEmpty()
    if (raw.isEmpty()) return COLOR_OS_SETTINGS_DEFAULT_3RD_VENDORS
    return raw.split(',').mapNotNull { token -> token.trim().takeIf(String::isNotEmpty)?.toIntOrNull(16) }
}

/**
 * ColorOS 蓝牙设置（`com.oplus.wirelesssettings`）集成。
 *
 * 蓝牙设置只对厂商 ID 在三方耳机白名单内的设备显示左右耳电量摘要和“耳机设置”入口，并把入口
 * 交给“我的设备”的三方耳机面板。这里仅在设置进程内让华为耳机通过白名单判断（不改动蓝牙栈的
 * 设备属性），电量摘要直接读取模块已写入系统的耳机电量；“耳机设置”则改为打开 HuaweiPods。
 */
@SuppressLint("MissingPermission")
object ColorOsWirelessSettingsHook : HookContext() {
    private const val TAG = "HuaweiPods-ColorOsWirelessSettings"
    private const val BLUETOOTH_NATIVE = "com.oplus.wirelesssettings.api.BluetoothNative"
    private const val CACHED_BLUETOOTH_DEVICE = "com.android.settingslib.bluetooth.CachedBluetoothDevice"
    private const val HFP_AT_VENDOR_PROPERTY = "persist.bluetooth.3rd_earbuds_hfp_at_vendor"
    private const val MY_DEVICES_DETAIL_PANEL_ACTION = "com.oplus.mydevices.ACTION_DEVICE_DETAILED_PANEL"
    private const val AIRPODS_SETTING_ACTION = "com.oplus.mydevices.AIRPODS_SETTING"
    private const val MELODY_DISPLAY_EARPHONE_OPLUS = "oplus.intent.action.MELODY_DISPLAY_EARPHONE"
    private const val MELODY_DISPLAY_EARPHONE_HEYTAP = "com.oplus.melody.action.DISPLAY_EARPHONE"
    private const val DEVICE_DETAILED_PANEL_SETTINGS = "android.settings.DEVICE_DETAILED_PANEL"
    private const val BLUETOOTH_PACKAGE = "com.android.bluetooth"

    private var statusReceiverContext: Context? = null
    private var statusReceiver: BroadcastReceiver? = null
    private val latestSummaries = ConcurrentHashMap<String, String>()
    private val latestBatteryArrays = ConcurrentHashMap<String, IntArray>()
    private val knownSupportedAddresses = ConcurrentHashMap.newKeySet<String>()

    private val allowedVendors: List<Int> by lazy {
        colorOsSettingsThirdPartyVendors(
            runCatching {
                Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java, String::class.java)
                    .invoke(null, HFP_AT_VENDOR_PROPERTY, "") as? String
            }.getOrNull(),
        )
    }

    override fun onHook() {
        runCatching { hookActivityLifecycle() }
            .onFailure { Log.w(TAG, "Activity lifecycle hook unavailable", it) }
        runCatching { hookAirPodsDetection() }
            .onSuccess { Log.i(TAG, "Bluetooth settings AirPods detection hook installed") }
            .onFailure { Log.w(TAG, "Bluetooth settings AirPods detection hook unavailable", it) }
        runCatching { hookBatteryInfoReflection() }
            .onSuccess { Log.i(TAG, "Bluetooth settings battery info reflection hook installed") }
            .onFailure { Log.w(TAG, "Bluetooth settings battery info reflection hook unavailable", it) }
        runCatching { hookConnectionSummary() }
            .onSuccess { Log.i(TAG, "Bluetooth settings connection summary hook installed") }
            .onFailure { Log.w(TAG, "Bluetooth settings connection summary hook unavailable", it) }
        runCatching { hookVendorId() }
            .onSuccess { Log.i(TAG, "Bluetooth settings vendor hook installed") }
            .onFailure { Log.w(TAG, "Bluetooth settings vendor hook unavailable", it) }
        runCatching { hookEarbudsIcon() }
            .onSuccess { Log.i(TAG, "Bluetooth settings earbuds icon hook installed") }
            .onFailure { Log.w(TAG, "Bluetooth settings earbuds icon hook unavailable", it) }
        runCatching { hookEarphoneEntryLaunch() }
            .onFailure { Log.w(TAG, "Bluetooth settings entry hook unavailable", it) }
        runCatching { hookBatterySummary() }
            .onSuccess { Log.i(TAG, "Bluetooth settings battery summary hook installed") }
            .onFailure { Log.w(TAG, "Bluetooth settings battery summary unavailable", it) }
        runCatching { hookBatterySummaryResume() }
            .onFailure { Log.w(TAG, "Bluetooth settings resume refresh unavailable", it) }
        runCatching { hookBatterySummarySetText() }
            .onSuccess { Log.i(TAG, "Bluetooth settings summary setText hook installed") }
            .onFailure { Log.w(TAG, "Bluetooth settings summary setText hook unavailable", it) }
    }

    override fun onClose() {
        val receiver = statusReceiver
        val context = statusReceiverContext
        if (receiver != null && context != null) {
            runCatching { context.unregisterReceiver(receiver) }
        }
        statusReceiver = null
        statusReceiverContext = null
        latestSummaries.clear()
        latestBatteryArrays.clear()
        knownSupportedAddresses.clear()
    }

    private fun hookActivityLifecycle() {
        val method = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
        hookAfter(method) {
            val activity = instance as? Activity ?: return@hookAfter
            registerStatusReceiver(activity.applicationContext ?: activity)
            requestCurrentStatus(activity)
        }
    }

    private fun hookAirPodsDetection() {
        val method = findMethod(BLUETOOTH_NATIVE, "isAirPodsDeviceByReflection", BluetoothDevice::class.java)
        hookAfter(method) {
            val device = args.firstOrNull() as? BluetoothDevice ?: return@hookAfter
            if (isSupportedDevice(device)) {
                result = true
            }
        }

        runCatching {
            val totalSupportMethod = findMethod(CACHED_BLUETOOTH_DEVICE, "isTotallySupportAirPods", Context::class.java)
            hookAfter(totalSupportMethod) {
                val cachedDevice = instance ?: return@hookAfter
                val device = runCatching {
                    callMethod(cachedDevice, "getDevice") as? BluetoothDevice
                        ?: getObjectField(cachedDevice, "mDevice") as? BluetoothDevice
                }.getOrNull() ?: return@hookAfter
                if (isSupportedDevice(device)) {
                    result = true
                }
            }
        }

        runCatching {
            val featureHMethod = findMethod("com.oplus.wirelesssettings.k", "H")
            hookAfter(featureHMethod) {
                result = true
            }
        }

        runCatching {
            val iotKMethod = findMethod("p024e3.AbstractC0431t", "k", String::class.java)
            hookAfter(iotKMethod) {
                val name = args.firstOrNull() as? String ?: return@hookAfter
                if (SoundcoreDeviceIdentifier.isLiberty4Nc(name) || name.contains("soundcore", ignoreCase = true)) {
                    result = true
                }
            }
        }

        runCatching {
            val cls = findClass(CACHED_BLUETOOTH_DEVICE)
            for (m in cls.declaredMethods.filter { it.name == "refreshInfo" }) {
                hookAfter(m) {
                    val cachedDevice = instance ?: return@hookAfter
                    val device = runCatching {
                        callMethod(cachedDevice, "getDevice") as? BluetoothDevice
                            ?: getObjectField(cachedDevice, "mDevice") as? BluetoothDevice
                    }.getOrNull() ?: return@hookAfter
                    if (isSupportedDevice(device)) {
                        runCatching { setObjectField(cachedDevice, "mIsAirPods", true) }
                    }
                }
            }
        }
    }

    private fun isDeviceConnected(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        return runCatching {
            val method = device.javaClass.methods.firstOrNull {
                it.name == "isConnected" && it.parameterCount in 0..1
            } ?: return@runCatching false
            when (method.parameterCount) {
                0 -> method.invoke(device) as? Boolean ?: false
                else -> method.invoke(device, BluetoothDevice.TRANSPORT_AUTO) as? Boolean ?: false
            }
        }.getOrElse { false }
    }

    private fun hookBatteryInfoReflection() {
        val method = findMethod(BLUETOOTH_NATIVE, "getBatteryInfoByReflection", BluetoothDevice::class.java)
        hookAfter(method) {
            val device = args.firstOrNull() as? BluetoothDevice ?: return@hookAfter
            if (isSupportedDevice(device)) {
                if (!isDeviceConnected(device)) return@hookAfter
                val array = latestBatteryArrays[device.address.uppercase()]
                if (array != null) {
                    result = array
                }
            }
        }
    }

    private fun formatSummary(cachedDevice: Any, addr: String): String? {
        val isConnected = runCatching {
            val dev = callMethod(cachedDevice, "getDevice") as? BluetoothDevice
                ?: getObjectField(cachedDevice, "mDevice") as? BluetoothDevice
            val connectedMethod = cachedDevice.javaClass.methods.firstOrNull { it.name == "isConnected" }
            (connectedMethod?.invoke(cachedDevice) as? Boolean == true) || (dev != null && isDeviceConnected(dev))
        }.getOrDefault(false)
        if (!isConnected) return null

        val summary = latestSummaries[addr]
            ?: latestBatteryArrays[addr]?.let { arr ->
                val chinese = Locale.getDefault().language == Locale.CHINESE.language
                colorOsBatterySummary(
                    leftBattery = arr[0],
                    leftConnected = arr[0] >= 0,
                    rightBattery = arr[1],
                    rightConnected = arr[1] >= 0,
                    caseBattery = arr[2],
                    caseConnected = arr[2] >= 0,
                    chinese = chinese,
                )
            }
            ?: return null
        val isActive = runCatching {
            callMethod(cachedDevice, "isActiveMediaDevice") as? Boolean == true ||
                callMethod(cachedDevice, "isActiveDeviceA2dp") as? Boolean == true ||
                callMethod(cachedDevice, "isActiveDeviceHeadset") as? Boolean == true
        }.getOrDefault(false)
        val chinese = Locale.getDefault().language == Locale.CHINESE.language
        return if (isActive) {
            if (chinese) "使用中，电量：$summary" else "In use. Battery: $summary"
        } else {
            if (chinese) "已连接，电量：$summary" else "Connected. Battery: $summary"
        }
    }

    private fun hookConnectionSummary() {
        val cls = findClass(CACHED_BLUETOOTH_DEVICE)
        val methods = cls.declaredMethods.filter { it.name == "getConnectionSummary" }
        for (m in methods) {
            hookAfter(m) {
                val cachedDevice = instance ?: return@hookAfter
                val device = runCatching {
                    callMethod(cachedDevice, "getDevice") as? BluetoothDevice
                        ?: getObjectField(cachedDevice, "mDevice") as? BluetoothDevice
                }.getOrNull() ?: return@hookAfter
                if (!isSupportedDevice(device)) return@hookAfter
                val addr = device.address.uppercase()
                val current = result as? String
                if (current.isNullOrBlank() || (!current.contains("%") && !current.contains("电量"))) {
                    formatSummary(cachedDevice, addr)?.let { result = it }
                }
            }
        }

        runCatching {
            val baseCls = findClass("com.oplus.settingslib.bluetooth.b")
            for (m in baseCls.declaredMethods.filter { it.name in setOf("getOplusTwsSummary", "getSummaryForOplus") }) {
                hookAfter(m) {
                    val cachedDevice = instance ?: return@hookAfter
                    val device = runCatching {
                        callMethod(cachedDevice, "getDevice") as? BluetoothDevice
                            ?: getObjectField(cachedDevice, "mDevice") as? BluetoothDevice
                    }.getOrNull() ?: return@hookAfter
                    if (!isSupportedDevice(device)) return@hookAfter
                    val addr = device.address.uppercase()
                    val current = result as? String
                    if (current.isNullOrBlank() || (!current.contains("%") && !current.contains("电量"))) {
                        formatSummary(cachedDevice, addr)?.let { result = it }
                    }
                }
            }
        }
    }

    /**
     * 设置列表行的电量摘要由宿主自己拼，只带左右耳（真机：`使用中，电量：左 100%，右 100%`），
     * 但同一份数据喂给连接弹窗时三段都在，说明缺的只是列表行的格式模板。
     *
     * 收模块电量广播 → 遍历窗口 → 用 [ColorOsSettingsBatteryText.rewrite] 补成 `左…，右…，盒…`；
     * 认不出是电量行就一个字符都不动。
     */
    private fun hookBatterySummary() {
        val onCreate = Application::class.java.getDeclaredMethod("onCreate")
        hookAfter(onCreate) {
            val app = instance as? Application ?: return@hookAfter
            val rewrite = ColorOsBatterySummaryInjector.Rewrite { text, triple ->
                ColorOsSettingsBatteryText.rewrite(text, triple.left, triple.right, triple.case)
            }
            ColorOsBatterySummaryInjector.install(app, TAG, rewrite)
        }
    }

    /**
     * 只等电量广播的话，进页面前没有新广播就一直是宿主的老文本（缺盒子）。
     * 这里在每次 `onResume` 后补三刷，配合 [ColorOsBatterySnapshot] 的跨进程回填，
     * 冷启动页面也能立刻显示三段。
     */
    private fun hookBatterySummaryResume() {
        val rewrite = ColorOsBatterySummaryInjector.Rewrite { text, triple ->
            ColorOsSettingsBatteryText.rewrite(text, triple.left, triple.right, triple.case)
        }
        val onResume = Activity::class.java.getDeclaredMethod("onResume")
        hookAfter(onResume) {
            val activity = instance as? Activity ?: return@hookAfter
            ColorOsBatterySummaryInjector.attach(activity, TAG, rewrite)
        }
    }

    /**
     * 宿主每 15 秒重绑一次列表摘要，扫窗口会和它抢时序（改完立刻被覆盖）。
     * 在它写文本那一刻改写，顺序就固定了：宿主先算，我们改，TextView 收到的是三段。
     */
    private fun hookBatterySummarySetText() {
        val rewrite = ColorOsBatterySummaryInjector.Rewrite { text, triple ->
            ColorOsSettingsBatteryText.rewrite(text, triple.left, triple.right, triple.case)
        }
        val setText = TextView::class.java.getDeclaredMethod("setText", CharSequence::class.java)
        hookBefore(setText) {
            val text = args[0] as? CharSequence ?: return@hookBefore
            ColorOsBatterySummaryInjector.rewriteForAnyPod(text, rewrite, (instance as? TextView)?.context)
                ?.let { proceedWithArgs(it) }
        }
    }

    private fun hookEarbudsIcon() {
        val utilsCls = runCatching { findClass("com.android.settingslib.bluetooth.BluetoothUtils") }.getOrNull() ?: return
        val cachedDeviceCls = runCatching { findClass(CACHED_BLUETOOTH_DEVICE) }.getOrNull() ?: return

        runCatching {
            val getDeviceTypeMethod = utilsCls.getDeclaredMethod("getDeviceType", cachedDeviceCls)
            hookBefore(getDeviceTypeMethod) {
                val cachedDevice = args.firstOrNull() ?: return@hookBefore
                val device = runCatching {
                    callMethod(cachedDevice, "getDevice") as? BluetoothDevice
                        ?: getObjectField(cachedDevice, "mDevice") as? BluetoothDevice
                }.getOrNull() ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    result = "oplusEarBuds"
                }
            }
        }.onFailure { Log.w(TAG, "BluetoothUtils.getDeviceType hook unavailable", it) }

        runCatching {
            val cacheOrUpdateMethod = utilsCls.getDeclaredMethod("cacheOrUpdateDeviceType", cachedDeviceCls)
            hookBefore(cacheOrUpdateMethod) {
                val cachedDevice = args.firstOrNull() ?: return@hookBefore
                val device = runCatching {
                    callMethod(cachedDevice, "getDevice") as? BluetoothDevice
                        ?: getObjectField(cachedDevice, "mDevice") as? BluetoothDevice
                }.getOrNull() ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    result = "oplusEarBuds"
                }
            }
        }.onFailure { Log.w(TAG, "BluetoothUtils.cacheOrUpdateDeviceType hook unavailable", it) }

        runCatching {
            val getIconMethod = utilsCls.getDeclaredMethod(
                "getIconFromDeviceType",
                Context::class.java,
                cachedDeviceCls,
                Boolean::class.javaPrimitiveType!!,
            ).apply { isAccessible = true }
            hookBefore(getIconMethod) {
                val context = args.firstOrNull() as? Context ?: return@hookBefore
                val cachedDevice = args.getOrNull(1) ?: return@hookBefore
                val isDark = args.getOrNull(2) as? Boolean ?: false
                val device = runCatching {
                    callMethod(cachedDevice, "getDevice") as? BluetoothDevice
                        ?: getObjectField(cachedDevice, "mDevice") as? BluetoothDevice
                }.getOrNull() ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    val drawableName = if (isDark) "bt_headset_hfp_ic_dark" else "bt_headset_hfp_ic_light"
                    val resId = context.resources.getIdentifier(drawableName, "drawable", context.packageName)
                    if (resId != 0) {
                        val drawable = context.getDrawable(resId)
                        if (drawable != null) {
                            result = Pair(drawable, null)
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "BluetoothUtils.getIconFromDeviceType hook unavailable", it) }
    }

    private fun hookVendorId() {
        val method = findMethod(BLUETOOTH_NATIVE, "getDeviceVendorId", BluetoothDevice::class.java)
        hookAfter(method) {
            val vendor = result as? Int ?: return@hookAfter
            if (vendor in allowedVendors) return@hookAfter
            val device = args.firstOrNull() as? BluetoothDevice ?: return@hookAfter
            if (!isSupportedDevice(device)) return@hookAfter
            allowedVendors.firstOrNull()?.let { result = it }
        }
    }

    private fun hookEarphoneEntryLaunch() {
        val method = Activity::class.java.getDeclaredMethod(
            "startActivityForResult",
            Intent::class.java,
            Int::class.javaPrimitiveType,
            Bundle::class.java,
        )
        val validActions = setOf(
            MY_DEVICES_DETAIL_PANEL_ACTION,
            AIRPODS_SETTING_ACTION,
            MELODY_DISPLAY_EARPHONE_OPLUS,
            MELODY_DISPLAY_EARPHONE_HEYTAP,
            DEVICE_DETAILED_PANEL_SETTINGS,
        )
        hookBefore(method) {
            val intent = args.getOrNull(0) as? Intent ?: return@hookBefore
            val action = intent.action
            if (action !in validActions) return@hookBefore
            val address = intent.getStringExtra("device_mac_info")
                ?: (intent.getParcelableExtra<BluetoothDevice>("device"))?.address
                ?: intent.getStringExtra("device_address")
                ?: return@hookBefore
            val device = runCatching { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) }
                .getOrNull() ?: return@hookBefore
            if (!isSupportedDevice(device)) return@hookBefore
            val popupIntent = Intent(HuaweiPodsAction.ACTION_SHOW_PODS_UI).apply {
                setPackage(BuildConfig.APPLICATION_ID)
                addCategory(Intent.CATEGORY_DEFAULT)
                putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                putExtra("bluetoothaddress", device.address)
                putExtra("device_address", device.address)
                putExtra("navigate_page", "earphone_detail")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            Log.i(TAG, "Bluetooth settings earphone entry redirected to HuaweiPods")
            proceedWithArgs(popupIntent, -1, args.getOrNull(2))
        }
    }

    private fun registerStatusReceiver(context: Context) {
        if (statusReceiver != null) return
        val appContext = context.applicationContext ?: context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val received = intent ?: return
                if (!HuaweiPodsBroadcastTrustPolicy.shouldAcceptWirelessSettingsStateBroadcast(received.action, sentFromPackage)) {
                    Log.w(TAG, "Rejected wireless settings state action=${received.action} sender=$sentFromPackage")
                    return
                }
                val action = HuaweiPodsAction.canonical(received.action) ?: received.action
                val address = (received.getStringExtra("address")
                    ?: (received.getParcelableExtra("device", BluetoothDevice::class.java)
                        ?: received.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java))?.address)?.uppercase() ?: return
                knownSupportedAddresses.add(address)
                if (action == HuaweiPodsAction.ACTION_PODS_DISCONNECTED || action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                    latestBatteryArrays.remove(address)
                    latestSummaries.remove(address)
                    return
                }
                if (action == HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED) {
                    val leftBattery = received.getIntExtra("left_battery", 0)
                    val leftConnected = received.getBooleanExtra("left_connected", false)
                    val leftCharging = received.getBooleanExtra("left_charging", false)
                    val rightBattery = received.getIntExtra("right_battery", 0)
                    val rightConnected = received.getBooleanExtra("right_connected", false)
                    val rightCharging = received.getBooleanExtra("right_charging", false)
                    val caseBattery = received.getIntExtra("case_battery", 0)
                    val caseConnected = received.getBooleanExtra("case_connected", false)
                    val caseCharging = received.getBooleanExtra("case_charging", false)

                    val summary = colorOsBatterySummary(
                        leftBattery = leftBattery,
                        leftConnected = leftConnected,
                        rightBattery = rightBattery,
                        rightConnected = rightConnected,
                        caseBattery = caseBattery,
                        caseConnected = caseConnected,
                        chinese = Locale.getDefault().language == Locale.CHINESE.language,
                    )
                    if (summary != null) {
                        latestSummaries[address] = summary
                    }

                    fun chargeVal(conn: Boolean, chg: Boolean) = when {
                        !conn -> COLOR_OS_CHARGE_DISCONNECTED
                        chg -> COLOR_OS_CHARGE_CHARGING
                        else -> COLOR_OS_CHARGE_NOT_CHARGING
                    }
                    val arr = intArrayOf(
                        if (leftConnected) leftBattery.coerceIn(0, 100) else COLOR_OS_BATTERY_UNKNOWN,
                        if (rightConnected) rightBattery.coerceIn(0, 100) else COLOR_OS_BATTERY_UNKNOWN,
                        if (caseConnected) caseBattery.coerceIn(0, 100) else COLOR_OS_BATTERY_UNKNOWN,
                        chargeVal(leftConnected, leftCharging),
                        chargeVal(rightConnected, rightCharging),
                        chargeVal(caseConnected, caseCharging),
                    )
                    latestBatteryArrays[address] = arr
                }
            }
        }
        runCatching {
            appContext.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED)
                    addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_CONNECTED)
                    addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_DISCONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                },
                Context.RECEIVER_EXPORTED,
            )
        }.onSuccess {
            statusReceiver = receiver
            statusReceiverContext = appContext
        }.onFailure {
            Log.w(TAG, "Unable to register wireless settings battery receiver", it)
        }
    }

    private fun requestCurrentStatus(context: Context) {
        runCatching {
            context.sendIdentitySharingBroadcast(Intent(HuaweiPodsAction.ACTION_REFRESH_STATUS).apply {
                setPackage(BuildConfig.APPLICATION_ID)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }.onFailure {
            Log.w(TAG, "Unable to request current headset status from app", it)
        }
        runCatching {
            context.sendIdentitySharingBroadcast(Intent(HuaweiPodsAction.ACTION_REFRESH_STATUS).apply {
                setPackage(BLUETOOTH_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }.onFailure {
            Log.w(TAG, "Unable to request current headset status from wireless settings", it)
        }
    }

    internal fun isSupportedDevice(device: BluetoothDevice): Boolean {
        val addr = device.address?.uppercase() ?: return false
        if (addr in knownSupportedAddresses) return true
        if (SoundcoreDeviceIdentifier.isSoundcoreDevice(device)) {
            knownSupportedAddresses.add(addr)
            return true
        }
        val isSupported = runCatching { device.huaweiDeviceRoute().isSupported }.getOrDefault(false)
        if (isSupported) knownSupportedAddresses.add(addr)
        return isSupported
    }
}

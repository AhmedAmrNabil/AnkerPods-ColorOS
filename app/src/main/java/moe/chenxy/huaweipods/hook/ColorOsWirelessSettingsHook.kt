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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import moe.chenxy.huaweipods.BuildConfig
import moe.chenxy.huaweipods.broadcast.HuaweiPodsBroadcastTrustPolicy
import moe.chenxy.huaweipods.pods.huaweiDeviceRoute
import moe.chenxy.huaweipods.pods.isSupported
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.addHuaweiPodsAction

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
    private const val HFP_AT_VENDOR_PROPERTY = "persist.bluetooth.3rd_earbuds_hfp_at_vendor"
    private const val MY_DEVICES_DETAIL_PANEL_ACTION = "com.oplus.mydevices.ACTION_DEVICE_DETAILED_PANEL"

    private val main = Handler(Looper.getMainLooper())

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
        runCatching { hookVendorId() }
            .onSuccess { Log.i(TAG, "Bluetooth settings vendor hook installed") }
            .onFailure { Log.w(TAG, "Bluetooth settings vendor hook unavailable", it) }
        runCatching { hookEarphoneEntryLaunch() }
            .onFailure { Log.w(TAG, "Bluetooth settings entry hook unavailable", it) }
        runCatching { hookBatterySummary() }
            .onSuccess { Log.i(TAG, "Bluetooth settings battery summary hook installed") }
            .onFailure { Log.w(TAG, "Bluetooth settings battery summary unavailable", it) }
    }

    /**
     * 设置列表行的电量摘要由宿主自己拼，只带左右耳（真机：`使用中，电量：左 100%，右 100%`），
     * 但同一份数据喂给连接弹窗时三段都在，说明缺的只是列表行的格式模板。
     *
     * 这里在设置进程里收模块的电量广播，拿到三段后把窗口里那条摘要补成 `左…，右…，盒…`。
     * 认不出是电量行就一个字符都不动（[ColorOsSettingsBatteryText.rewrite]）。
     */
    private fun hookBatterySummary() {
        val onCreate = Application::class.java.getDeclaredMethod("onCreate")
        hookAfter(onCreate) {
            val app = instance as? Application ?: return@hookAfter
            runCatching { registerBatteryReceiver(app) }
                .onFailure { Log.w(TAG, "Bluetooth settings battery receiver unavailable", it) }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerBatteryReceiver(app: Application) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // 只认蓝牙进程发来的状态广播（模块的 HFP 控制器就跑在那个进程里），
                // 和「我的设备」侧的 shouldAcceptMyDevicesStateBroadcast 一致。
                val trusted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    runCatching {
                        val packages = context.packageManager.getPackagesForUid(sentFromUid)
                        HuaweiPodsBroadcastTrustPolicy.shouldAcceptMyDevicesStateBroadcast(
                            intent.action,
                            packages?.firstOrNull(),
                        )
                    }.getOrDefault(false)
                } else {
                    false
                }
                if (!trusted) return
                val address = intent.getStringExtra("address")?.uppercase() ?: return
                val pod = ColorOsPodBattery(
                    address = address,
                    left = intent.getIntExtra("left_battery", 0),
                    leftConnected = intent.getBooleanExtra("left_connected", false),
                    right = intent.getIntExtra("right_battery", 0),
                    rightConnected = intent.getBooleanExtra("right_connected", false),
                    case = intent.getIntExtra("case_battery", 0),
                    caseConnected = intent.getBooleanExtra("case_connected", false),
                    name = intent.getStringExtra("device_name")?.takeIf(String::isNotBlank),
                )
                ColorOsPodBatteryStore.put(pod)
                main.post { patchBatterySummaries() }
            }
        }
        app.registerReceiver(
            receiver,
            IntentFilter().apply { addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED) },
            Context.RECEIVER_EXPORTED,
        )
        main.post { patchBatterySummaries() }
        // 列表第一次渲染比 Application.onCreate 晚，补两次即可，之后每次电量广播都会再刷。
        main.postDelayed({ patchBatterySummaries() }, 1_200)
        main.postDelayed({ patchBatterySummaries() }, 3_000)
    }

    /** 遍历本进程窗口，把能认出来的电量摘要补成三段。 */
    private fun patchBatterySummaries() {
        runCatching {
            val triple = ColorOsPodBatteryStore.snapshot()
                .mapNotNull(::colorOsBatteryTriple)
                .maxByOrNull { it.parts.size }
                ?: return
            val windowType = Class.forName("android.view.WindowManagerGlobal")
            val manager = windowType.getDeclaredMethod("getInstance").invoke(null)
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
                        val rewritten = ColorOsSettingsBatteryText.rewrite(
                            view.text,
                            triple.left,
                            triple.right,
                            triple.case,
                        )
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
                Log.i(TAG, "Bluetooth settings battery summary patched rows=$patched triple=$triple")
            }
        }.onFailure { Log.w(TAG, "Unable to patch Bluetooth settings battery summary", it) }
    }

    private fun hookVendorId() {
        val method = findMethod(BLUETOOTH_NATIVE, "getDeviceVendorId", BluetoothDevice::class.java)
        hookAfter(method) {
            val vendor = result as? Int ?: return@hookAfter
            if (vendor in allowedVendors) return@hookAfter
            val device = args.firstOrNull() as? BluetoothDevice ?: return@hookAfter
            if (!isHuaweiDevice(device)) return@hookAfter
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
        hookBefore(method) {
            val intent = args.getOrNull(0) as? Intent ?: return@hookBefore
            if (intent.action != MY_DEVICES_DETAIL_PANEL_ACTION) return@hookBefore
            val address = intent.getStringExtra("device_mac_info") ?: return@hookBefore
            val device = runCatching { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) }
                .getOrNull() ?: return@hookBefore
            if (!isHuaweiDevice(device)) return@hookBefore
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

    private fun isHuaweiDevice(device: BluetoothDevice): Boolean =
        runCatching { device.huaweiDeviceRoute().isSupported }.getOrDefault(false)
}

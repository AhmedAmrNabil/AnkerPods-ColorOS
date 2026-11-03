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
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import moe.chenxy.huaweipods.BuildConfig
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.utils.ModuleResourceResolver
import moe.chenxy.huaweipods.backend.soundcore.SoundcoreDeviceIdentifier
import moe.chenxy.huaweipods.broadcast.HuaweiPodsBroadcastTrustPolicy
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.HuaweiWearDetectionController
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.pods.displayName
import moe.chenxy.huaweipods.pods.encodeHuaweiDeviceRouteForBroadcast
import moe.chenxy.huaweipods.pods.supportsLowLatencyControl
import moe.chenxy.huaweipods.pods.isSupported
import moe.chenxy.huaweipods.pods.resolveHuaweiDeviceRoute
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.PodParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.addHuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.sendIdentitySharingBroadcast

/**
 * ColorOS "My Devices" integration.
 *
 * - ColorOS 16.1（com.heytap.mydevices 17.4.15）：ViewBinding 详情页，复用 `row_bt_setting` 行。
 * - ColorOS 17（com.heytap.mydevices 17.25.10）：详情页改为 Preference 页面，复用
 *   `pref_bt_audio_settings`（打开系统蓝牙设备设置）这一项。
 * - Quick Settings / Control Center / Lockscreen widget:
 *   通过拦截 OplusBluetoothAdapterWrapper、BlueToothScannerImpl 及 LiteCardWidget，将 Soundcore 与
 *   华为耳机注入为原生 BT_AIRPODS/TWS 设备，显示耳机图标、独立双耳电量，并避免锁屏单电量文字过长截断。
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
    private val latestBatteries = ConcurrentHashMap<String, BatteryParams>()
    private val latestSummaries = ConcurrentHashMap<String, String>()
    private val latestAncModes = ConcurrentHashMap<String, NoiseControlMode>()
    private val latestAncAdaptive = ConcurrentHashMap<String, Boolean>()
    private val latestLowLatency = ConcurrentHashMap<String, Boolean>()
    private val latestWearDetection = ConcurrentHashMap<String, Boolean>()
    private val toggleWrites = ColorOsToggleWriteLedger()
    private val hookedBindMethods = ConcurrentHashMap.newKeySet<Method>()
    private const val EXTEND_FEATURE_MASK = 1581 or 2048 or 8192

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

    private fun getFeatureStatus(address: String, feat: Int): Int? {
        val upper = address.uppercase()
        return when (feat) {
            4 -> {
                val mode = latestAncModes[upper] ?: NoiseControlMode.OFF
                val isAdaptive = latestAncAdaptive[upper] == true
                when (mode) {
                    NoiseControlMode.OFF -> 1
                    NoiseControlMode.NOISE_CANCELLATION -> if (isAdaptive) 4 else 2
                    NoiseControlMode.TRANSPARENCY -> 3
                    else -> 1
                }
            }
            2048 -> 0 // both earbuds worn (unblocks IconTextSelector clicks on ANC & Adaptive)
            8192 -> 1 // single-ear noise switch supported
            32 -> 1 // wear detection supported
            4096 -> if (latestLowLatency[upper] == true) 1 else 0
            else -> 0
        }
    }

    fun isSupportedDevice(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val addr = device.address
        val name = runCatching { device.name ?: device.alias }.getOrNull()
        if (SoundcoreDeviceIdentifier.isLiberty4Nc(name) || SoundcoreDeviceIdentifier.isSoundcoreDevice(device)) return true
        val route = resolveHuaweiDeviceRoute(addr, name)
        return route.isSupported
    }

    fun isSupportedAddress(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        val upper = address.uppercase()
        if (latestBatteries.containsKey(upper)) return true
        val dev = bluetoothDevice(upper)
        return isSupportedDevice(dev)
    }

    override fun onHook() {
        runCatching { hookOplusBluetoothAdapterWrapper() }
            .onSuccess { Log.i(TAG, "OplusBluetoothAdapterWrapper hook installed") }
            .onFailure { Log.w(TAG, "OplusBluetoothAdapterWrapper hook failed", it) }
        runCatching { hookOplusBluetoothDevice() }
            .onSuccess { Log.i(TAG, "OplusBluetoothDevice hook installed") }
            .onFailure { Log.w(TAG, "OplusBluetoothDevice hook failed", it) }
        runCatching { hookIconHandler() }
            .onSuccess { Log.i(TAG, "IconHandler hook installed") }
            .onFailure { Log.w(TAG, "IconHandler hook failed", it) }
        runCatching { hookBlueToothScannerImpl() }
            .onSuccess { Log.i(TAG, "BlueToothScannerImpl hook installed") }
            .onFailure { Log.w(TAG, "BlueToothScannerImpl hook failed", it) }
        runCatching { hookLiteCardWidget() }
            .onSuccess { Log.i(TAG, "LiteCardWidget hook installed") }
            .onFailure { Log.w(TAG, "LiteCardWidget hook failed", it) }
        runCatching { hookAirpodsFragment() }
            .onSuccess { Log.i(TAG, "AirpodsFragment hook installed") }
            .onFailure { Log.w(TAG, "AirpodsFragment hook failed", it) }
        runCatching { hookAirpodsSettingActivity() }
            .onSuccess { Log.i(TAG, "AirpodsSettingActivity hook installed") }
            .onFailure { Log.w(TAG, "AirpodsSettingActivity hook failed", it) }

        val legacy = runCatching { hookLegacyDetailPage() }
        val preference = runCatching { hookPreferenceDetailPage() }
        if (legacy.isSuccess) Log.i(TAG, "My Devices 16.x detail hook installed")
        if (preference.isSuccess) Log.i(TAG, "My Devices 17.x preference detail hook installed")

        val app = currentApplicationOrNull()
        if (app != null) {
            registerStatusReceiver(app)
            requestCurrentStatus(app)
            ensureSoundcoreImageCached(app)
        } else {
            hookApplicationOnCreate()
        }
    }

    private fun hookApplicationOnCreate() {
        runCatching {
            val appCls = findClass("android.app.Application")
            val method = appCls.getDeclaredMethod("onCreate")
            hookAfter(method) {
                val app = instance as? Application ?: return@hookAfter
                registerStatusReceiver(app)
                requestCurrentStatus(app)
                ensureSoundcoreImageCached(app)
            }
        }.onFailure { Log.w(TAG, "Unable to hook Application.onCreate", it) }
    }

    private fun dispatchAncSelect(context: Context, address: String, mode: NoiseControlMode, isAdaptive: Boolean, subMode: Int?) {
        val intent = Intent(HuaweiPodsAction.ACTION_ANC_SELECT).apply {
            putExtra("status", mode.broadcastStatus)
            putExtra("address", address)
            putExtra("is_adaptive", isAdaptive)
            if (subMode != null) putExtra("submode", subMode)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        runCatching { context.sendIdentitySharingBroadcast(Intent(intent).apply { setPackage(BuildConfig.APPLICATION_ID) }) }
        runCatching { context.sendIdentitySharingBroadcast(Intent(intent).apply { setPackage(BLUETOOTH_PACKAGE) }) }
    }

    private fun hookOplusBluetoothAdapterWrapper() {
        val cls = runCatching {
            findClass("com.heytap.mydevices.core.bluetooth.OplusBluetoothAdapterWrapper")
        }.getOrNull() ?: return

        // c(BluetoothDevice): checkIsAirpodsDevice
        runCatching {
            val method = cls.getDeclaredMethod("c", BluetoothDevice::class.java)
            hookBefore(method) {
                val dev = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(dev)) result = true
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothAdapterWrapper.c hook unavailable", it) }

        // d(String): checkIsAirpodsDevice by address
        runCatching {
            val method = cls.getDeclaredMethod("d", String::class.java)
            hookBefore(method) {
                val addr = args.firstOrNull() as? String ?: return@hookBefore
                if (isSupportedAddress(addr)) result = true
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothAdapterWrapper.d hook unavailable", it) }

        // o(String): getBatteryInfoSync -> int[]
        runCatching {
            val method = cls.getDeclaredMethod("o", String::class.java)
            hookBefore(method) {
                val addr = args.firstOrNull() as? String ?: return@hookBefore
                if (isSupportedAddress(addr)) {
                    val dev = bluetoothDevice(addr)
                    if (!isDeviceConnected(dev)) return@hookBefore
                    val battery = latestBatteries[addr.uppercase()] ?: return@hookBefore
                    val info = colorOsTwsBatteryInfo(battery)
                    if (info[2] <= 0 || info[5] == COLOR_OS_CHARGE_DISCONNECTED) {
                        val prevCase = battery.case ?: latestBatteries[addr.uppercase()]?.case
                        info[2] = if (prevCase != null && prevCase.isConnected && prevCase.battery > 0) prevCase.battery else 100
                        info[5] = if (prevCase?.isCharging == true) COLOR_OS_CHARGE_CHARGING else COLOR_OS_CHARGE_NOT_CHARGING
                    }
                    result = info
                }
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothAdapterWrapper.o hook unavailable", it) }

        runCatching {
            val nMethod = cls.declaredMethods.firstOrNull { it.name == "n" && it.parameterTypes.size == 2 }
            if (nMethod != null) {
                hookBefore(nMethod) {
                    val addr = args.firstOrNull() as? String ?: return@hookBefore
                    if (isSupportedAddress(addr)) {
                        val dev = bluetoothDevice(addr)
                        if (!isDeviceConnected(dev)) return@hookBefore
                        val battery = latestBatteries[addr.uppercase()] ?: return@hookBefore
                        val info = colorOsTwsBatteryInfo(battery)
                        if (info[2] <= 0 || info[5] == COLOR_OS_CHARGE_DISCONNECTED) {
                            val prevCase = battery.case ?: latestBatteries[addr.uppercase()]?.case
                            info[2] = if (prevCase != null && prevCase.isConnected && prevCase.battery > 0) prevCase.battery else 100
                            info[5] = if (prevCase?.isCharging == true) COLOR_OS_CHARGE_CHARGING else COLOR_OS_CHARGE_NOT_CHARGING
                        }
                        result = info
                    }
                }
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothAdapterWrapper.n hook unavailable", it) }

        // l(BluetoothDevice): getAirpodsModel -> String
        runCatching {
            val method = cls.getDeclaredMethod("l", BluetoothDevice::class.java)
            hookBefore(method) {
                val dev = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(dev)) result = "AirPods Pro 2"
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothAdapterWrapper.l hook unavailable", it) }

        // G(BluetoothDevice, int): getStringMetaData -> String
        runCatching {
            val method = cls.getDeclaredMethod("G", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
            hookBefore(method) {
                val dev = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                val id = args.getOrNull(1) as? Int ?: return@hookBefore
                if (id == 17 && isSupportedDevice(dev)) result = "oplusEarBuds"
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothAdapterWrapper.G hook unavailable", it) }

        // u(BluetoothDevice): getDeviceExtendFeatureMask -> int
        runCatching {
            val method = cls.getDeclaredMethod("u", BluetoothDevice::class.java)
            hookBefore(method) {
                val dev = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(dev)) result = EXTEND_FEATURE_MASK
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothAdapterWrapper.u hook unavailable", it) }

        // v(String): getDeviceExtendFeatureMask by address -> int
        runCatching {
            val method = cls.getDeclaredMethod("v", String::class.java)
            hookBefore(method) {
                val addr = args.firstOrNull() as? String ?: return@hookBefore
                if (isSupportedAddress(addr)) result = EXTEND_FEATURE_MASK
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothAdapterWrapper.v hook unavailable", it) }

        // y(String, int): getExtendFeatureStatus -> int
        runCatching {
            val method = cls.getDeclaredMethod("y", String::class.java, Int::class.javaPrimitiveType!!)
            hookBefore(method) {
                val addr = args.firstOrNull() as? String ?: return@hookBefore
                val feat = args.getOrNull(1) as? Int ?: return@hookBefore
                if (isSupportedAddress(addr)) {
                    getFeatureStatus(addr, feat)?.let { result = it }
                }
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothAdapterWrapper.y hook unavailable", it) }

        // x(BluetoothDevice, int): getExtendFeatureStatus -> int
        runCatching {
            val method = cls.getDeclaredMethod("x", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
            hookBefore(method) {
                val dev = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                val feat = args.getOrNull(1) as? Int ?: return@hookBefore
                if (isSupportedDevice(dev)) {
                    getFeatureStatus(dev.address, feat)?.let { result = it }
                }
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothAdapterWrapper.x hook unavailable", it) }

        // W(String, int, int): setExtendFeatureStatus
        runCatching {
            val method = cls.getDeclaredMethod(
                "W",
                String::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
            hookBefore(method) {
                val addr = args.firstOrNull() as? String ?: return@hookBefore
                val feat = args.getOrNull(1) as? Int ?: return@hookBefore
                val value = args.getOrNull(2) as? Int ?: return@hookBefore
                if (feat == 4 && isSupportedAddress(addr)) {
                    val (mode, isAdaptive, subMode) = when (value) {
                        1 -> Triple(NoiseControlMode.OFF, false, null)
                        2 -> Triple(NoiseControlMode.NOISE_CANCELLATION, false, 5)
                        3 -> Triple(NoiseControlMode.TRANSPARENCY, false, null)
                        4 -> Triple(NoiseControlMode.NOISE_CANCELLATION, true, 3)
                        else -> Triple(NoiseControlMode.OFF, false, null)
                    }
                    latestAncModes[addr.uppercase()] = mode
                    latestAncAdaptive[addr.uppercase()] = isAdaptive
                    refreshQuickControls(addr.uppercase())
                    notifyAirpodsNoiseMode(addr, mode, isAdaptive)
                    val context = currentApplicationOrNull() ?: statusReceiverContext
                    if (context != null) {
                        dispatchAncSelect(context, addr, mode, isAdaptive, subMode)
                    }
                    result = null
                }
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothAdapterWrapper.W hook unavailable", it) }
    }

    private fun hookOplusBluetoothDevice() {
        val cls = runCatching {
            findClass("android.bluetooth.OplusBluetoothDevice")
        }.getOrNull() ?: return

        fun extractDevice(inst: Any?): BluetoothDevice? {
            if (inst == null) return null
            if (inst is BluetoothDevice) return inst
            runCatching {
                val m = inst.javaClass.methods.firstOrNull { it.name == "getDevice" && it.parameterCount == 0 && it.returnType == BluetoothDevice::class.java }
                if (m != null) return m.invoke(inst) as? BluetoothDevice
            }
            var c: Class<*>? = inst.javaClass
            while (c != null && c != Any::class.java) {
                for (field in c.declaredFields) {
                    if (field.type == BluetoothDevice::class.java) {
                        field.isAccessible = true
                        val dev = field.get(inst) as? BluetoothDevice
                        if (dev != null) return dev
                    }
                }
                c = c.superclass
            }
            return null
        }

        runCatching {
            val method = cls.getDeclaredMethod("checkIsAirpodsDevice")
            hookBefore(method) {
                val device = extractDevice(instance)
                if (device != null && isSupportedDevice(device)) {
                    result = true
                }
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothDevice.checkIsAirpodsDevice hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getBatteryInfo")
            hookBefore(method) {
                val device = extractDevice(instance)
                if (device != null && isSupportedDevice(device)) {
                    if (!isDeviceConnected(device)) return@hookBefore
                    val battery = latestBatteries[device.address.uppercase()] ?: return@hookBefore
                    val info = colorOsTwsBatteryInfo(battery)
                    if (info[2] <= 0 || info[5] == COLOR_OS_CHARGE_DISCONNECTED) {
                        info[2] = 100
                        info[5] = COLOR_OS_CHARGE_NOT_CHARGING
                    }
                    result = info
                }
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothDevice.getBatteryInfo hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getDeviceModel")
            hookBefore(method) {
                val device = extractDevice(instance)
                if (device != null && isSupportedDevice(device)) {
                    result = "AirPods Pro 2"
                }
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothDevice.getDeviceModel hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getDeviceExtendFeatureMask")
            hookBefore(method) {
                val device = extractDevice(instance)
                if (device != null && isSupportedDevice(device)) {
                    result = EXTEND_FEATURE_MASK
                }
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothDevice.getDeviceExtendFeatureMask hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getExtendFeatureStatus", Int::class.javaPrimitiveType!!)
            hookBefore(method) {
                val device = extractDevice(instance)
                val featureId = args.firstOrNull() as? Int ?: return@hookBefore
                if (device != null && isSupportedDevice(device)) {
                    getFeatureStatus(device.address, featureId)?.let { result = it }
                }
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothDevice.getExtendFeatureStatus hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod(
                "setExtendFeatureStatus",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
            hookBefore(method) {
                val device = extractDevice(instance)
                val featureId = args.firstOrNull() as? Int ?: return@hookBefore
                val value = args.getOrNull(1) as? Int ?: return@hookBefore
                if (device != null && featureId == 4 && isSupportedDevice(device)) {
                    val (mode, isAdaptive, subMode) = when (value) {
                        1 -> Triple(NoiseControlMode.OFF, false, null)
                        2 -> Triple(NoiseControlMode.NOISE_CANCELLATION, false, 5)
                        3 -> Triple(NoiseControlMode.TRANSPARENCY, false, null)
                        4 -> Triple(NoiseControlMode.NOISE_CANCELLATION, true, 3)
                        else -> Triple(NoiseControlMode.OFF, false, null)
                    }
                    latestAncModes[device.address.uppercase()] = mode
                    latestAncAdaptive[device.address.uppercase()] = isAdaptive
                    refreshQuickControls(device.address.uppercase())
                    notifyAirpodsNoiseMode(device.address, mode, isAdaptive)
                    val context = currentApplicationOrNull() ?: statusReceiverContext
                    if (context != null) {
                        dispatchAncSelect(context, device.address, mode, isAdaptive, subMode)
                    }
                    result = true
                }
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothDevice.setExtendFeatureStatus hook unavailable", it) }
    }

    @Volatile
    private var cachedSoundcoreUri: Uri? = null

    @Volatile
    private var cachedSoundcoreBitmap: Bitmap? = null

    private fun ensureSoundcoreImageCached(context: Context): Uri? {
        cachedSoundcoreUri?.let { return it }
        return runCatching {
            val dir = File(context.cacheDir, "device_image")
            if (!dir.exists()) dir.mkdirs()

            val filenames = listOf(
                "IMG_soundcore_liberty_4_nc.webp",
                "IMG_airpods_pro_a2dp_homepage.webp",
                "IMG_airpods_a2dp_homepage.webp",
                "IMG_airpods_pro_pairing_capsule.webp",
                "IMG_airpods_pairing_capsule.webp",
                "IMG_bt_a2dp_homepage.webp",
                "IMG_bt_headset_homepage_gradient.webp",
            )

            val moduleRes = ModuleResourceResolver.resources(context)
            val resId = moduleRes?.runCatching {
                getIdentifier("soundcore_liberty_4_nc", "drawable", BuildConfig.APPLICATION_ID)
            }?.getOrDefault(0)?.takeIf { it != 0 } ?: R.drawable.soundcore_liberty_4_nc

            val bytes = moduleRes?.openRawResource(resId)?.use { it.readBytes() }
            if (bytes != null && bytes.isNotEmpty()) {
                var primaryFile: File? = null
                for (fn in filenames) {
                    val f = File(dir, fn)
                    if (!f.exists() || f.length() != bytes.size.toLong()) {
                        f.outputStream().use { it.write(bytes) }
                    }
                    if (primaryFile == null) primaryFile = f
                }

                val targetFile = primaryFile ?: File(dir, "IMG_soundcore_liberty_4_nc.webp")
                val uri = FileProvider.getUriForFile(context, "com.oplus.mydevices.fileprovider", targetFile)
                cachedSoundcoreUri = uri
                uri
            } else {
                val targetFile = File(dir, "IMG_soundcore_liberty_4_nc.webp")
                if (targetFile.exists() && targetFile.length() > 1000) {
                    val uri = FileProvider.getUriForFile(context, "com.oplus.mydevices.fileprovider", targetFile)
                    cachedSoundcoreUri = uri
                    uri
                } else null
            }
        }.onFailure {
            Log.w(TAG, "Failed to cache Soundcore images", it)
        }.getOrNull()
    }

    private fun getSoundcoreDrawable(context: Context): Drawable? {
        val bitmap = cachedSoundcoreBitmap ?: runCatching {
            val moduleRes = ModuleResourceResolver.resources(context)
            val resId = moduleRes?.runCatching {
                getIdentifier("soundcore_liberty_4_nc", "drawable", BuildConfig.APPLICATION_ID)
            }?.getOrDefault(0)?.takeIf { it != 0 } ?: R.drawable.soundcore_liberty_4_nc
            val bm = moduleRes?.let { BitmapFactory.decodeResource(it, resId) }
            bm ?: runCatching {
                val file = File(context.cacheDir, "device_image/IMG_soundcore_liberty_4_nc.webp")
                if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            }.getOrNull()
        }.getOrNull()?.also { cachedSoundcoreBitmap = it } ?: return null
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun hasActiveSupportedDevice(): Boolean {
        return latestBatteries.keys.any { isSupportedAddress(it) } ||
            runCatching {
                BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.any { dev ->
                    isSupportedDevice(dev) && isDeviceConnected(dev)
                }
            }.getOrNull() == true
    }

    private fun hookIconHandler() {
        val cls = runCatching { findClass("aa.EA") }.getOrNull() ?: return
        runCatching {
            val method = cls.getDeclaredMethod("f", BluetoothDevice::class.java)
            hookBefore(method) {
                val dev = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(dev)) result = 2001
            }
        }.onFailure { Log.w(TAG, "IconHandler.f hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("d", Int::class.javaPrimitiveType!!, BluetoothDevice::class.java)
            hookBefore(method) {
                val dev = args.getOrNull(1) as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(dev)) {
                    val context = runCatching {
                        cls.getDeclaredField("a").apply { isAccessible = true }.get(instance) as? Context
                    }.getOrNull() ?: currentApplicationOrNull() ?: statusReceiverContext
                    if (context != null) {
                        val uri = ensureSoundcoreImageCached(context)
                        if (uri != null) result = uri.toString()
                    }
                }
            }
        }.onFailure { Log.w(TAG, "IconHandler.d hook unavailable", it) }

        runCatching {
            val methodA = cls.getDeclaredMethod("a", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
            hookBefore(methodA) {
                if (hasActiveSupportedDevice()) {
                    val context = runCatching {
                        cls.getDeclaredField("a").apply { isAccessible = true }.get(instance) as? Context
                    }.getOrNull() ?: currentApplicationOrNull() ?: statusReceiverContext
                    if (context != null) {
                        val uri = ensureSoundcoreImageCached(context)
                        if (uri != null) result = uri.toString()
                    }
                }
            }
        }.onFailure { Log.w(TAG, "IconHandler.a hook unavailable", it) }

        runCatching {
            val methodB = cls.getDeclaredMethod("b", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
            hookBefore(methodB) {
                if (hasActiveSupportedDevice()) {
                    val context = runCatching {
                        cls.getDeclaredField("a").apply { isAccessible = true }.get(instance) as? Context
                    }.getOrNull() ?: currentApplicationOrNull() ?: statusReceiverContext
                    if (context != null) {
                        val uri = ensureSoundcoreImageCached(context)
                        if (uri != null) result = uri.toString()
                    }
                }
            }
        }.onFailure { Log.w(TAG, "IconHandler.b hook unavailable", it) }
    }

    private fun hookBlueToothScannerImpl() {
        val cls = runCatching { findClass("com.heytap.mydevices.core.device.BlueToothScannerImpl") }.getOrNull() ?: return
        runCatching {
            val method = cls.getDeclaredMethod("D", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
            hookBefore(method) {
                val dev = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                val inst = instance ?: return@hookBefore
                if (isSupportedDevice(dev)) {
                    val i = args.getOrNull(1) as? Int ?: -1
                    val deviceInfoCls = findClass("com.oplus.mydevices.domain.entities.device.DeviceInfo")
                    val bMethod = inst.javaClass.getDeclaredMethod("B", BluetoothDevice::class.java, deviceInfoCls, Int::class.javaPrimitiveType!!)
                    bMethod.isAccessible = true
                    result = bMethod.invoke(inst, dev, null, i)
                }
            }
        }.onFailure { Log.w(TAG, "BlueToothScannerImpl.D hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("onBatteryLevelChanged", BluetoothDevice::class.java, IntArray::class.java)
            hookBefore(method) {
                val dev = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                val iArr = args.getOrNull(1) as? IntArray ?: return@hookBefore
                if (isSupportedDevice(dev)) {
                    if (!isDeviceConnected(dev)) {
                        latestBatteries.remove(dev.address.uppercase())
                        return@hookBefore
                    }
                    val isAllInvalid = iArr.isEmpty() || (iArr[0] == -1 && iArr[1] == -1)
                    if (isAllInvalid) {
                        val battery = latestBatteries[dev.address.uppercase()]
                        if (battery != null) {
                            proceedWithArgs(dev, patchBatteryArray(dev, colorOsTwsBatteryInfo(battery)))
                        } else {
                            result = null // suppress clearing while connected and awaiting first packet
                        }
                        return@hookBefore
                    }
                    proceedWithArgs(dev, patchBatteryArray(dev, iArr))
                }
            }
        }.onFailure { Log.w(TAG, "BlueToothScannerImpl.onBatteryLevelChanged hook unavailable", it) }

        runCatching {
            val obsCls = findClass("com.heytap.mydevices.core.bluetooth.AirpodsStatusObserver")
            val method = obsCls.getDeclaredMethod("notifyBatteryLevelChanged", BluetoothDevice::class.java, IntArray::class.java).apply { isAccessible = true }
            hookBefore(method) {
                val dev = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                val iArr = args.getOrNull(1) as? IntArray ?: return@hookBefore
                if (isSupportedDevice(dev)) {
                    if (!isDeviceConnected(dev)) {
                        latestBatteries.remove(dev.address.uppercase())
                        return@hookBefore
                    }
                    val isAllInvalid = iArr.isEmpty() || (iArr[0] == -1 && iArr[1] == -1)
                    if (isAllInvalid) {
                        val battery = latestBatteries[dev.address.uppercase()]
                        if (battery != null) {
                            proceedWithArgs(dev, patchBatteryArray(dev, colorOsTwsBatteryInfo(battery)))
                        } else {
                            result = null // suppress invalid battery update while connected
                        }
                        return@hookBefore
                    }
                    proceedWithArgs(dev, patchBatteryArray(dev, iArr))
                }
            }
        }.onFailure { Log.w(TAG, "AirpodsStatusObserver.notifyBatteryLevelChanged hook unavailable", it) }

        runCatching {
            val dataCls = findClass("com.oplus.mydevices.deviceui.devicecard.airpods.AirpodsData")
            val method = dataCls.getDeclaredMethod("onBatteryLevelChanged", BluetoothDevice::class.java, IntArray::class.java)
            hookBefore(method) {
                val dev = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                val iArr = args.getOrNull(1) as? IntArray ?: return@hookBefore
                if (isSupportedDevice(dev)) {
                    if (!isDeviceConnected(dev)) {
                        latestBatteries.remove(dev.address.uppercase())
                        return@hookBefore
                    }
                    val isAllInvalid = iArr.isEmpty() || (iArr[0] == -1 && iArr[1] == -1)
                    if (isAllInvalid) {
                        val battery = latestBatteries[dev.address.uppercase()]
                        if (battery != null) {
                            proceedWithArgs(dev, patchBatteryArray(dev, colorOsTwsBatteryInfo(battery)))
                        } else {
                            result = null // suppress invalid battery update while connected
                        }
                        return@hookBefore
                    }
                    proceedWithArgs(dev, patchBatteryArray(dev, iArr))
                }
            }
        }.onFailure { Log.w(TAG, "AirpodsData.onBatteryLevelChanged hook unavailable", it) }
    }

    private fun patchBatteryArray(dev: BluetoothDevice, iArr: IntArray): IntArray {
        if (!isDeviceConnected(dev)) {
            Log.d(TAG, "patchBatteryArray dev=${dev.address} disconnected, preserving in=${iArr.joinToString()}")
            return iArr
        }
        val upper = dev.address.uppercase()
        val battery = latestBatteries[upper] ?: return iArr
        val prevCase = battery.case
        val patched = iArr.clone()
        if (patched.size >= 6) {
            val left = battery.left
            if (left != null && left.isConnected && left.battery > 0) {
                patched[0] = left.battery
                patched[3] = if (left.isCharging) COLOR_OS_CHARGE_CHARGING else COLOR_OS_CHARGE_NOT_CHARGING
            }

            val right = battery.right
            if (right != null && right.isConnected && right.battery > 0) {
                patched[1] = right.battery
                patched[4] = if (right.isCharging) COLOR_OS_CHARGE_CHARGING else COLOR_OS_CHARGE_NOT_CHARGING
            }

            if (prevCase != null && prevCase.isConnected && prevCase.battery > 0) {
                patched[2] = prevCase.battery
                patched[5] = if (prevCase.isCharging) COLOR_OS_CHARGE_CHARGING else COLOR_OS_CHARGE_NOT_CHARGING
            }
        }
        Log.i(TAG, "patchBatteryArray dev=${dev.address} in=${iArr.joinToString()} out=${patched.joinToString()}")
        return patched
    }

    private fun hookLiteCardWidget() {
        val cls = runCatching { findClass("com.oplus.mydevices.remotecards.litecardwidget.LiteCardWidget") }.getOrNull() ?: return
        val genMethod = cls.declaredMethods.firstOrNull { it.name == "generateDeviceInfo" } ?: return
        hookAfter(genMethod) {
            val cardInfo = result ?: return@hookAfter
            val devObj = args.lastOrNull() ?: return@hookAfter
            val addr = runCatching { callMethod(devObj, "getMacAddress") as? String }.getOrNull()
            if (isSupportedAddress(addr)) {
                cardInfo.javaClass.declaredFields.forEach { field ->
                    field.isAccessible = true
                    if (field.type == Boolean::class.javaPrimitiveType && field.name == "b") {
                        field.setBoolean(cardInfo, false)
                    }
                    if (field.type == String::class.java && field.name == "h") {
                        val curIcon = field.get(cardInfo) as? String
                        if (curIcon != null && curIcon.contains("HEADSET")) {
                            field.set(cardInfo, curIcon.replace("HEADSET", "LR"))
                        }
                    }
                }
            }
        }
    }

    private fun hookAirpodsFragment() {
        val fragCls = runCatching {
            findClass("com.oplus.mydevices.deviceui.devicecard.airpods.AirpodsFragment")
        }.getOrNull()

        if (fragCls != null) {
            runCatching {
                val method = fragCls.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
                hookAfter(method) {
                    val root = args.firstOrNull() as? View ?: return@hookAfter
                    val macField = runCatching { fragCls.getDeclaredField("mMac").apply { isAccessible = true } }.getOrNull()
                    val mac = macField?.get(instance) as? String
                    if (mac != null && !isSupportedAddress(mac)) return@hookAfter

                    val airpodsImageId = root.resources.getIdentifier("airpods_image", "id", root.context.packageName)
                    val imgView = (if (airpodsImageId != 0) root.findViewById(airpodsImageId) else null)
                        ?: runCatching {
                            val bindingField = fragCls.getDeclaredField("mBinding").apply { isAccessible = true }
                            val binding = bindingField.get(instance)
                            val nField = binding?.javaClass?.getDeclaredField("n")?.apply { isAccessible = true }
                            nField?.get(binding) as? ImageView
                        }.getOrNull() ?: return@hookAfter

                    fun applyHeroImage() {
                        val currentMac = runCatching { macField?.get(instance) as? String }.getOrNull()
                        if (currentMac != null && !isSupportedAddress(currentMac)) return
                        val target = getSoundcoreDrawable(imgView.context) ?: return
                        if (imgView.background != null || imgView.drawable !== target) {
                            imgView.background = null
                            imgView.setImageDrawable(target)
                            imgView.scaleType = ImageView.ScaleType.FIT_CENTER
                        }
                    }

                    applyHeroImage()

                    imgView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            if (!imgView.isAttachedToWindow) {
                                imgView.viewTreeObserver.removeOnPreDrawListener(this)
                                return true
                            }
                            applyHeroImage()
                            return true
                        }
                    })
                }
            }.onFailure { Log.w(TAG, "AirpodsFragment.onViewCreated hook unavailable", it) }

            runCatching {
                val initDataMethod = fragCls.declaredMethods.firstOrNull { it.name == "initData" }
                if (initDataMethod != null) {
                    hookAfter(initDataMethod) {
                        val macField = runCatching { fragCls.getDeclaredField("mMac").apply { isAccessible = true } }.getOrNull()
                        val mac = macField?.get(instance) as? String
                        if (mac != null && !isSupportedAddress(mac)) return@hookAfter
                        val bindingField = fragCls.getDeclaredField("mBinding").apply { isAccessible = true }
                        val binding = bindingField.get(instance) ?: return@hookAfter
                        val nField = runCatching { binding.javaClass.getDeclaredField("n").apply { isAccessible = true } }.getOrNull()
                        val imgView = nField?.get(binding) as? ImageView ?: return@hookAfter
                        val target = getSoundcoreDrawable(imgView.context) ?: return@hookAfter
                        imgView.background = null
                        imgView.setImageDrawable(target)
                        imgView.scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                }
            }.onFailure { Log.w(TAG, "AirpodsFragment.initData hook unavailable", it) }

            runCatching {
                val method = fragCls.getDeclaredMethod("showAirpodsBattery", IntArray::class.java)
                hookBefore(method) {
                    val iArr = args.firstOrNull() as? IntArray ?: return@hookBefore
                    if (iArr.size >= 6) {
                        val leftValid = iArr[0] >= 0 && iArr[3] != COLOR_OS_CHARGE_DISCONNECTED
                        val rightValid = iArr[1] >= 0 && iArr[4] != COLOR_OS_CHARGE_DISCONNECTED
                        if (leftValid || rightValid) {
                            if (iArr[2] <= 0 || iArr[5] == COLOR_OS_CHARGE_DISCONNECTED) {
                                val prevCase = latestBatteries.values.firstOrNull()?.case
                                iArr[2] = if (prevCase != null && prevCase.isConnected && prevCase.battery > 0) prevCase.battery else 100
                                iArr[5] = if (prevCase?.isCharging == true) 1 else 2
                            }
                        }
                    }
                }
                hookAfter(method) {
                    val iArr = args.firstOrNull() as? IntArray ?: return@hookAfter
                    if (iArr.size >= 6) {
                        val leftValid = iArr[0] >= 0 && iArr[3] != COLOR_OS_CHARGE_DISCONNECTED
                        val rightValid = iArr[1] >= 0 && iArr[4] != COLOR_OS_CHARGE_DISCONNECTED
                        if (!leftValid && !rightValid) return@hookAfter
                    }
                    runCatching {
                        val bindingField = fragCls.getDeclaredField("mBinding").apply { isAccessible = true }
                        val binding = bindingField.get(instance) ?: return@runCatching
                        val boxLayoutField = binding.javaClass.getDeclaredField("C").apply { isAccessible = true }
                        val boxLayout = boxLayoutField.get(binding) as? View
                        boxLayout?.visibility = View.VISIBLE
                        val boxTextField = binding.javaClass.getDeclaredField("b").apply { isAccessible = true }
                        val boxText = boxTextField.get(binding) as? TextView
                        val caseLevel = if (iArr.size >= 6 && iArr[2] > 0) iArr[2] else 100
                        boxText?.text = caseLevel.toString()
                    }
                }
            }.onFailure { Log.w(TAG, "AirpodsFragment.showAirpodsBattery hook unavailable", it) }

            runCatching {
                val method = fragCls.getDeclaredMethod("updateAirpodsComponentVisibility", IntArray::class.java)
                hookBefore(method) {
                    val iArr = args.firstOrNull() as? IntArray ?: return@hookBefore
                    if (iArr.size >= 6) {
                        val leftValid = iArr[0] >= 0 && iArr[3] != COLOR_OS_CHARGE_DISCONNECTED
                        val rightValid = iArr[1] >= 0 && iArr[4] != COLOR_OS_CHARGE_DISCONNECTED
                        if (leftValid || rightValid) {
                            if (iArr[2] <= 0 || iArr[5] == COLOR_OS_CHARGE_DISCONNECTED) {
                                val prevCase = latestBatteries.values.firstOrNull()?.case
                                iArr[2] = if (prevCase != null && prevCase.isConnected && prevCase.battery > 0) prevCase.battery else 100
                                iArr[5] = if (prevCase?.isCharging == true) 1 else 2
                            }
                        }
                    }
                }
                hookAfter(method) {
                    val iArr = args.firstOrNull() as? IntArray ?: return@hookAfter
                    if (iArr.size >= 6) {
                        val leftValid = iArr[0] >= 0 && iArr[3] != COLOR_OS_CHARGE_DISCONNECTED
                        val rightValid = iArr[1] >= 0 && iArr[4] != COLOR_OS_CHARGE_DISCONNECTED
                        if (!leftValid && !rightValid) return@hookAfter
                    }
                    runCatching {
                        val bindingField = fragCls.getDeclaredField("mBinding").apply { isAccessible = true }
                        val binding = bindingField.get(instance) ?: return@runCatching
                        val boxLayoutField = binding.javaClass.getDeclaredField("C").apply { isAccessible = true }
                        val boxLayout = boxLayoutField.get(binding) as? View
                        boxLayout?.visibility = View.VISIBLE
                    }
                }
            }.onFailure { Log.w(TAG, "AirpodsFragment.updateAirpodsComponentVisibility hook unavailable", it) }
        }

        val dataCls = runCatching {
            findClass("com.oplus.mydevices.deviceui.devicecard.airpods.AirpodsData")
        }.getOrNull()

        if (dataCls != null) {
            runCatching {
                val dMethod = dataCls.declaredMethods.firstOrNull { it.name == "d" && it.parameterTypes.size == 2 }
                if (dMethod != null) {
                    hookBefore(dMethod) {
                        val addr = args.firstOrNull() as? String ?: return@hookBefore
                        if (isSupportedAddress(addr)) {
                            val dev = bluetoothDevice(addr)
                            if (!isDeviceConnected(dev)) return@hookBefore
                            val battery = latestBatteries[addr.uppercase()] ?: return@hookBefore
                            val info = colorOsTwsBatteryInfo(battery)
                            if (info[2] <= 0 || info[5] == COLOR_OS_CHARGE_DISCONNECTED) {
                                val prevCase = battery.case ?: latestBatteries[addr.uppercase()]?.case
                                info[2] = if (prevCase != null && prevCase.isConnected && prevCase.battery > 0) prevCase.battery else 100
                                info[5] = if (prevCase?.isCharging == true) 1 else 2
                            }
                            val bField = dataCls.getDeclaredField("b").apply { isAccessible = true }
                            val bFlow = bField.get(instance)
                            bFlow?.javaClass?.getMethod("setValue", Any::class.java)?.invoke(bFlow, info)
                            result = Unit
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "AirpodsData.d hook unavailable", it) }
        }
    }

    private fun hookAirpodsSettingActivity() {
        val actCls = runCatching {
            findClass("com.oplus.mydevices.bluetooth.airpods.AirpodsSettingActivity")
        }.getOrNull() ?: return

        fun applySettingImage(instance: Any?) {
            if (instance == null) return
            val devField = runCatching { actCls.getDeclaredField("w0").apply { isAccessible = true } }.getOrNull()
            val dev = devField?.get(instance) as? BluetoothDevice
            val addr = dev?.address ?: runCatching {
                val act = instance as? Activity
                act?.intent?.getStringExtra("address")
                    ?: (act?.intent?.getParcelableExtra("device", BluetoothDevice::class.java) as? BluetoothDevice)?.address
            }.getOrNull()
            if (addr != null && !isSupportedAddress(addr)) return

            val p1Method = runCatching { actCls.getDeclaredMethod("p1").apply { isAccessible = true } }.getOrNull()
            val imgView = p1Method?.invoke(instance) as? ImageView ?: runCatching {
                val m0Field = actCls.getDeclaredField("m0").apply { isAccessible = true }
                val lazyVal = m0Field.get(instance)
                lazyVal?.javaClass?.getMethod("getValue")?.invoke(lazyVal) as? ImageView
            }.getOrNull() ?: return

            val target = getSoundcoreDrawable(imgView.context) ?: return
            if (imgView.background != null || imgView.drawable !== target) {
                imgView.background = null
                imgView.setImageDrawable(target)
                imgView.scaleType = ImageView.ScaleType.FIT_CENTER
            }
        }

        runCatching {
            val d2Method = actCls.declaredMethods.firstOrNull { it.name == "d2" && it.parameterTypes.size == 1 }
            if (d2Method != null) {
                hookAfter(d2Method) {
                    applySettingImage(instance)
                }
            }
        }.onFailure { Log.w(TAG, "AirpodsSettingActivity.d2 hook unavailable", it) }

        runCatching {
            val onResumeMethod = actCls.getDeclaredMethod("onResume")
            hookAfter(onResumeMethod) {
                applySettingImage(instance)
                val p1Method = runCatching { actCls.getDeclaredMethod("p1").apply { isAccessible = true } }.getOrNull()
                val imgView = p1Method?.invoke(instance) as? ImageView
                if (imgView != null && imgView.tag != "soundcore_predraw_attached") {
                    imgView.tag = "soundcore_predraw_attached"
                    imgView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            if (!imgView.isAttachedToWindow) {
                                imgView.viewTreeObserver.removeOnPreDrawListener(this)
                                return true
                            }
                            applySettingImage(instance)
                            return true
                        }
                    })
                }
            }
        }.onFailure { Log.w(TAG, "AirpodsSettingActivity.onResume hook unavailable", it) }
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
        latestBatteries.clear()
        latestSummaries.clear()
        latestAncModes.clear()
        latestAncAdaptive.clear()
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
        var route = resolveHuaweiDeviceRoute(address, deviceName)
        if (!route.isSupported && (SoundcoreDeviceIdentifier.isLiberty4Nc(deviceName) || deviceName?.lowercase()?.contains("soundcore") == true)) {
            route = HuaweiDeviceRoute.SOUNDCORE_LIBERTY_4_NC
        }
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
        var route = resolveHuaweiDeviceRoute(address, deviceName)
        if (!route.isSupported && (SoundcoreDeviceIdentifier.isLiberty4Nc(deviceName) || deviceName?.lowercase()?.contains("soundcore") == true)) {
            route = HuaweiDeviceRoute.SOUNDCORE_LIBERTY_4_NC
        }
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
                    val isAdaptive = received.getBooleanExtra("is_adaptive", false)
                    latestAncModes[address] = mode
                    latestAncAdaptive[address] = isAdaptive
                    refreshQuickControls(address)
                    notifyAirpodsNoiseMode(address, mode, isAdaptive)
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
                when (action) {
                    HuaweiPodsAction.ACTION_PODS_DISCONNECTED,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        val address = received.getStringExtra("address")
                            ?: (received.getParcelableExtra("device", BluetoothDevice::class.java)
                                ?: received.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java))?.address
                        if (address != null) {
                            val upper = address.uppercase()
                            latestBatteries.remove(upper)
                            latestSummaries.remove(upper)
                            latestAncModes.remove(upper)
                            latestLowLatency.remove(upper)
                            latestWearDetection.remove(upper)
                            ColorOsPodBatteryStore.clear(upper)
                            toggleWrites.clear()
                            runCatching {
                                val dev = bluetoothDevice(upper) ?: return@runCatching
                                val obsCls = findClass("com.heytap.mydevices.core.bluetooth.AirpodsStatusObserver")
                                val companionField = obsCls.getDeclaredField("Companion").apply { isAccessible = true }
                                val companion = companionField.get(null)
                                val instMethod = companion.javaClass.getMethod("getInstance")
                                val observer = instMethod.invoke(companion)
                                val notifyMethod = obsCls.getDeclaredMethod("notifyBatteryLevelChanged", BluetoothDevice::class.java, IntArray::class.java).apply { isAccessible = true }
                                notifyMethod.invoke(
                                    observer,
                                    dev,
                                    intArrayOf(
                                        -1,
                                        -1,
                                        -1,
                                        COLOR_OS_CHARGE_DISCONNECTED,
                                        COLOR_OS_CHARGE_DISCONNECTED,
                                        COLOR_OS_CHARGE_DISCONNECTED,
                                    ),
                                )
                            }
                            refreshQuickControls(upper)
                        }
                        return
                    }
                }
                if (action != HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED) return
                val isReady = received.getBooleanExtra("is_ready", true)
                if (!isReady) {
                    Log.d(TAG, "Ignoring battery broadcast before isReady in MyDevices")
                    return
                }
                val address = received.getStringExtra("address")?.uppercase() ?: return
                val left = received.getIntExtra("left_battery", -1)
                val leftConn = received.getBooleanExtra("left_connected", false)
                val leftChg = received.getBooleanExtra("left_charging", false)
                val right = received.getIntExtra("right_battery", -1)
                val rightConn = received.getBooleanExtra("right_connected", false)
                val rightChg = received.getBooleanExtra("right_charging", false)
                val case = received.getIntExtra("case_battery", -1)
                val caseConn = received.getBooleanExtra("case_connected", false)
                val caseChg = received.getBooleanExtra("case_charging", false)

                Log.i(TAG, "statusReceiver intent received: sender=${received.`package`} leftChg=$leftChg rightChg=$rightChg caseChg=$caseChg extras=${received.extras?.keySet()?.joinToString { "$it=${received.extras?.get(it)}" }}")

                if (!leftConn && !rightConn && !caseConn) return
                if (left < 0 && right < 0) return

                val existingCase = latestBatteries[address]?.case
                val effectiveCase = if (case in 1..100) {
                    PodParams(battery = case, isConnected = true, isCharging = caseChg)
                } else if (existingCase != null && existingCase.battery > 0) {
                    existingCase.copy(isConnected = true, isCharging = caseChg)
                } else {
                    PodParams(battery = 100, isConnected = true, isCharging = false)
                }

                val effectiveL = if (left in 0..100) left else 100
                val effectiveR = if (right in 0..100) right else 100

                val batteryParams = BatteryParams(
                    left = PodParams(battery = effectiveL, isConnected = true, isCharging = leftChg),
                    right = PodParams(battery = effectiveR, isConnected = true, isCharging = rightChg),
                    case = effectiveCase,
                )
                latestBatteries[address] = batteryParams
                notifyAirpodsBattery(address, batteryParams)

                ColorOsPodBatteryStore.put(
                    ColorOsPodBattery(
                        address = address,
                        left = if (leftConn && left >= 0) left else 0,
                        leftConnected = leftConn,
                        right = if (rightConn && right >= 0) right else 0,
                        rightConnected = rightConn,
                        case = if (caseConn && case >= 0) case else 0,
                        caseConnected = caseConn,
                    ),
                )
                val summary = colorOsBatterySummary(
                    leftBattery = left,
                    leftConnected = leftConn,
                    rightBattery = right,
                    rightConnected = rightConn,
                    caseBattery = case,
                    caseConnected = caseConn,
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
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
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

    private fun notifyAirpodsBattery(address: String, batteryParams: BatteryParams) {
        runCatching {
            val dev = bluetoothDevice(address) ?: return@runCatching
            val info = colorOsTwsBatteryInfo(batteryParams)
            if (info[2] <= 0 || info[5] == COLOR_OS_CHARGE_DISCONNECTED) {
                val prevCase = batteryParams.case ?: latestBatteries[address.uppercase()]?.case
                info[2] = if (prevCase != null && prevCase.isConnected && prevCase.battery > 0) prevCase.battery else 100
                info[5] = if (prevCase?.isCharging == true) COLOR_OS_CHARGE_CHARGING else COLOR_OS_CHARGE_NOT_CHARGING
            }
            val obsCls = findClass("com.heytap.mydevices.core.bluetooth.AirpodsStatusObserver")
            val companionField = obsCls.getDeclaredField("Companion").apply { isAccessible = true }
            val companion = companionField.get(null)
            val instMethod = companion.javaClass.getMethod("getInstance")
            val observer = instMethod.invoke(companion)
            val notifyMethod = obsCls.getDeclaredMethod("notifyBatteryLevelChanged", BluetoothDevice::class.java, IntArray::class.java).apply { isAccessible = true }
            Log.i(TAG, "notifyAirpodsBattery dev=$address info=${info.joinToString()}")
            notifyMethod.invoke(observer, dev, info)
        }
    }

    private fun notifyAirpodsNoiseMode(address: String, mode: NoiseControlMode, isAdaptive: Boolean = false) {
        runCatching {
            val dev = bluetoothDevice(address) ?: return@runCatching
            val obsCls = findClass("com.heytap.mydevices.core.bluetooth.AirpodsStatusObserver")
            val companionField = obsCls.getDeclaredField("Companion").apply { isAccessible = true }
            val companion = companionField.get(null)
            val instMethod = companion.javaClass.getMethod("getInstance")
            val observer = instMethod.invoke(companion)
            val notifyMethod = obsCls.getDeclaredMethod("notifyNoiseModeChanged", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!).apply { isAccessible = true }
            val ancVal = when (mode) {
                NoiseControlMode.OFF -> 1
                NoiseControlMode.NOISE_CANCELLATION -> if (isAdaptive) 4 else 2
                NoiseControlMode.TRANSPARENCY -> 3
                else -> 1
            }
            notifyMethod.invoke(observer, dev, ancVal)
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
        val baseIntent = Intent(action).apply {
            putExtra("address", device.address)
            encodeHuaweiDeviceRouteForBroadcast(route)?.let {
                putExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE, it)
            }
            fill()
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        runCatching { context.sendIdentitySharingBroadcast(Intent(baseIntent).apply { setPackage(BLUETOOTH_PACKAGE) }) }
        runCatching { context.sendIdentitySharingBroadcast(Intent(baseIntent).apply { setPackage(BuildConfig.APPLICATION_ID) }) }
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
                putExtra("is_adaptive", false)
                putExtra("submode", 5)
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

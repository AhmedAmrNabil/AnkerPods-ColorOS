package moe.chenxy.huaweipods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Build
import java.lang.reflect.Method
import moe.chenxy.huaweipods.platform.RomFamily
import moe.chenxy.huaweipods.platform.RomIntegrationPolicy
import moe.chenxy.huaweipods.utils.SystemApisUtils.setMetadata
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.PodParams

/** OPLUS 三方耳机电量数组：左、右、盒电量，随后是三者的充电状态；-1 表示未知。 */
internal const val COLOR_OS_BATTERY_UNKNOWN = -1
internal const val COLOR_OS_CHARGE_CHARGING = 1
internal const val COLOR_OS_CHARGE_NOT_CHARGING = 2
internal const val COLOR_OS_CHARGE_DISCONNECTED = 4
internal const val COLOR_OS_WEAR_WEARING = 0
internal const val COLOR_OS_WEAR_IN_CASE = 2
internal const val COLOR_OS_WEAR_UNKNOWN = 255

internal fun colorOsTwsBatteryInfo(battery: BatteryParams): IntArray {
    val rawLeft = battery.left?.battery ?: 100
    val leftLevel = if (battery.left?.isConnected == true && rawLeft in 1..100) rawLeft else 100
    val leftCharge = if (battery.left?.isCharging == true) COLOR_OS_CHARGE_CHARGING else COLOR_OS_CHARGE_NOT_CHARGING

    val rawRight = battery.right?.battery ?: 100
    val rightLevel = if (battery.right?.isConnected == true && rawRight in 1..100) rawRight else 100
    val rightCharge = if (battery.right?.isCharging == true) COLOR_OS_CHARGE_CHARGING else COLOR_OS_CHARGE_NOT_CHARGING

    val rawCase = battery.case?.battery ?: 100
    val caseLevel = if (rawCase in 1..100) rawCase else 100
    val caseCharge = if (battery.case?.isCharging == true) COLOR_OS_CHARGE_CHARGING else COLOR_OS_CHARGE_NOT_CHARGING

    return intArrayOf(
        leftLevel,
        rightLevel,
        caseLevel,
        leftCharge,
        rightCharge,
        caseCharge,
    )
}

/** 状态栏与蓝牙设置使用的单一电量：取在线耳机中较低的一侧，避免高估可用时长。 */
internal fun colorOsSystemBatteryLevel(battery: BatteryParams): Int =
    listOfNotNull(battery.left, battery.right)
        .filter { it.isConnected }
        .minOfOrNull { it.battery.coerceIn(0, 100) }
        ?: COLOR_OS_BATTERY_UNKNOWN

/**
 * 把华为耳机电量写入 ColorOS 自己的耳机电量通道。
 *
 * ColorOS 17 为 AirPods 与三方耳机提供了 `IOplusRemoteDevice`：蓝牙设置、我的设备和快速设备
 * 连接都通过 `OplusBluetoothDevice.getBatteryInfo()` 读取左右耳/盒电量，状态栏与电量小组件则读取
 * Android 标准电量。华为私有协议不会进入这两个通道，这里在蓝牙进程内补齐。
 *
 * 只登记电量，不声明降噪等可控特性：系统界面因此不会尝试通过 OBUDS AT 指令控制华为耳机。
 * 所有厂商接口均通过反射访问，缺失时退回 Android 标准电量。
 */
internal object ColorOsSystemBatteryBridge {
    private const val TAG = "HuaweiPods-ColorOsBattery"
    private const val FEATURE_CACHE = "com.oplus.bluetooth.common.OplusFeatureCache"
    private const val FEATURE_INTERFACE = "com.oplus.bluetooth.common.IOplusCommonFeature"
    private const val REMOTE_DEVICE_INTERFACE =
        "com.oplus.bluetooth.common.interfaces.IOplusRemoteDevice"
    private const val AIRPODS_MANAGER = "com.oplus.bluetooth.feature.airpods.AirPodsManager"
    private const val EXTEND_FEATURE_INTERFACE =
        "com.oplus.bluetooth.common.interfaces.IOplusExtendFeature"
    private const val DEFAULT_TRANSPORT_HFP_AT = 1
    private const val ADAPTER_SERVICE = "com.android.bluetooth.btservice.AdapterService"

    @Volatile
    private var remoteDeviceUnavailableLogged = false
    @Volatile
    private var lastPublishSignature: String? = null
    @Volatile
    private var cachedRemoteDevice: Pair<ClassLoader, RemoteDevice>? = null
    @Volatile
    private var cachedAirPodsManager: Pair<ClassLoader, Any?>? = null

    private val latestBatteries = java.util.concurrent.ConcurrentHashMap<String, BatteryParams>()
    private val latestAncModes = java.util.concurrent.ConcurrentHashMap<String, moe.chenxy.huaweipods.pods.NoiseControlMode>()
    private val latestAncAdaptive = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun getLatestBattery(address: String?): BatteryParams? =
        address?.uppercase()?.let { latestBatteries[it] }

    fun getLatestAnc(address: String?): moe.chenxy.huaweipods.pods.NoiseControlMode? =
        address?.uppercase()?.let { latestAncModes[it] }

    fun isLatestAncAdaptive(address: String?): Boolean =
        address?.uppercase()?.let { latestAncAdaptive[it] == true } ?: false

    fun setLatestAnc(address: String?, mode: moe.chenxy.huaweipods.pods.NoiseControlMode, isAdaptive: Boolean = false) {
        address?.uppercase()?.let {
            latestAncModes[it] = mode
            latestAncAdaptive[it] = isAdaptive
        }
    }

    private val enabled: Boolean by lazy {
        RomIntegrationPolicy.detect(Build.MANUFACTURER, Build.BRAND) == RomFamily.COLOR_OS
    }

    @SuppressLint("MissingPermission")
    fun publish(
        context: Context,
        device: BluetoothDevice,
        battery: BatteryParams,
        inEar: Boolean? = null,
        isReady: Boolean = true,
    ) {
        if (!enabled) return
        if (!isReady) {
            Log.d(TAG, "ColorOsSystemBatteryBridge.publish: suppressed before isReady")
            return
        }
        val addr = device.address.uppercase()
        val existingCase = latestBatteries[addr]?.case
        val rawCase = battery.case
        val effectiveCase = if (rawCase != null && rawCase.battery > 0) {
            rawCase.copy(isConnected = true)
        } else if (existingCase != null && existingCase.battery > 0) {
            existingCase.copy(isConnected = true)
        } else {
            PodParams(battery = 100, isConnected = true, isCharging = false)
        }
        val effectiveBattery = battery.copy(case = effectiveCase)
        latestBatteries[addr] = effectiveBattery
        // 顺带把三段电量写进全局快照：其它进程（蓝牙设置 / 设备详细信息页）启动时先读它，
        // 不必等下一包广播才显示得出三段。
        runCatching {
            ColorOsBatterySnapshot.save(
                context,
                ColorOsPodBattery(
                    address = device.address,
                    left = effectiveBattery.left?.battery ?: 0,
                    leftConnected = effectiveBattery.left?.isConnected == true,
                    right = effectiveBattery.right?.battery ?: 0,
                    rightConnected = effectiveBattery.right?.isConnected == true,
                    case = effectiveBattery.case?.battery ?: 0,
                    caseConnected = effectiveBattery.case?.isConnected == true,
                    name = device.name,
                ),
            )
        }
        val info = colorOsTwsBatteryInfo(effectiveBattery)
        if (info[2] <= 0 || info[5] == COLOR_OS_CHARGE_DISCONNECTED) {
            info[2] = effectiveCase.battery.coerceIn(1, 100)
            info[5] = if (effectiveCase.isCharging) COLOR_OS_CHARGE_CHARGING else COLOR_OS_CHARGE_NOT_CHARGING
        }
        val remote = oplusRemoteDevice(context)
        if (remote != null) {
            runCatching {
                if (!moe.chenxy.huaweipods.backend.soundcore.SoundcoreDeviceIdentifier.isSoundcoreDevice(device)) {
                    remote.call(
                        "setOplusDeviceProtocolTransport",
                        arrayOf(BluetoothDevice::class.java, Int::class.javaPrimitiveType!!),
                        device,
                        remote.transportHfpAt,
                    )
                }
                remote.call(
                    "notifyWearStatusChanged",
                    arrayOf(
                        BluetoothDevice::class.java,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                    ),
                    device,
                    colorOsEarbudWearStatus(effectiveBattery.left, inEar),
                    colorOsEarbudWearStatus(effectiveBattery.right, inEar),
                )
                remote.call(
                    "notifyBatteryLevelChanged",
                    arrayOf(BluetoothDevice::class.java, IntArray::class.java),
                    device,
                    info,
                )
            }.onFailure {
                Log.w(TAG, "ColorOS earphone battery channel update failed", it)
            }
        }
        notifyAirPodsManager(context, device, info)
        publishAndroidMetadata(device, effectiveBattery)
        // OPLUS 在右耳缺席时会把 -1 当作最低值写入标准电量；这里再用在线耳机较低值校正。
        val systemLevel = colorOsSystemBatteryLevel(effectiveBattery)
        if (systemLevel >= 0) setAndroidBatteryLevel(context, device, systemLevel)
        // 只在数值变化时记一条：蓝牙设置页显示几段，全靠这个数组的内容，排查时必须看得到。
        val signature = info.joinToString() + "|" + systemLevel + "|" + inEar
        if (signature != lastPublishSignature) {
            lastPublishSignature = signature
            Log.i(TAG, "ColorOS battery published info=${info.joinToString()} system=$systemLevel inEar=$inEar")
        }
    }

    private fun publishAndroidMetadata(device: BluetoothDevice, battery: BatteryParams) {
        runCatching {
            // Key 6: Untethered Headset (TWS)
            device.setMetadata(6, "true".toByteArray())
            // Key 17: Device type for ColorOS and Android ("oplusEarBuds")
            device.setMetadata(17, "oplusEarBuds".toByteArray())

            battery.left?.let { pod ->
                if (pod.isConnected && pod.battery in 0..100) {
                    device.setMetadata(10, pod.battery.toString().toByteArray())
                    device.setMetadata(13, pod.isCharging.toString().toByteArray())
                }
            }
            battery.right?.let { pod ->
                if (pod.isConnected && pod.battery in 0..100) {
                    device.setMetadata(11, pod.battery.toString().toByteArray())
                    device.setMetadata(14, pod.isCharging.toString().toByteArray())
                }
            }
            battery.case?.let { pod ->
                if (pod.isConnected && pod.battery in 0..100) {
                    device.setMetadata(12, pod.battery.toString().toByteArray())
                    device.setMetadata(15, pod.isCharging.toString().toByteArray())
                }
            }
            val minEar = listOfNotNull(battery.left, battery.right)
                .filter { it.isConnected }
                .minOfOrNull { it.battery.coerceIn(0, 100) }
            if (minEar != null) {
                device.setMetadata(18, minEar.toString().toByteArray())
                val isCharging = battery.left?.isCharging == true || battery.right?.isCharging == true
                device.setMetadata(19, isCharging.toString().toByteArray())
            }
        }.onFailure {
            Log.w(TAG, "publishAndroidMetadata failed for ${device.address}", it)
        }
    }

    fun clear(context: Context, device: BluetoothDevice) {
        if (!enabled) return
        val addr = device.address.uppercase()
        latestBatteries.remove(addr)
        ColorOsBatterySnapshot.clear(context, device.address)
        notifyAirPodsManager(
            context,
            device,
            intArrayOf(
                COLOR_OS_BATTERY_UNKNOWN,
                COLOR_OS_BATTERY_UNKNOWN,
                COLOR_OS_BATTERY_UNKNOWN,
                COLOR_OS_CHARGE_DISCONNECTED,
                COLOR_OS_CHARGE_DISCONNECTED,
                COLOR_OS_CHARGE_DISCONNECTED,
            ),
        )
        // OPLUS 电量数组是独立通道：只清标准电量时，「我的设备」/蓝牙设置仍会显示断开前的
        // 左右耳和盒电量。断开时两处一起清。
        oplusRemoteDevice(context)?.let { remote ->
            runCatching {
                remote.call(
                    "notifyBatteryLevelChanged",
                    arrayOf(BluetoothDevice::class.java, IntArray::class.java),
                    device,
                    IntArray(6) { COLOR_OS_BATTERY_UNKNOWN },
                )
            }.onFailure {
                Log.w(TAG, "ColorOS earphone battery channel clear failed", it)
            }
        }
        setAndroidBatteryLevel(context, device, COLOR_OS_BATTERY_UNKNOWN)
    }

    private fun setAndroidBatteryLevel(context: Context, device: BluetoothDevice, level: Int) {
        val adapterService = adapterService(context) ?: return
        runCatching {
            adapterService.javaClass.findMethod(
                "setBatteryLevel",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
            ).invoke(adapterService, device, level, true)
        }.onFailure {
            Log.w(TAG, "Android battery level update failed level=$level", it)
        }

        // Direct standard broadcast to ensure SystemUI & Statusbar pick it up immediately
        runCatching {
            val intent = Intent("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED").apply {
                putExtra("android.bluetooth.device.extra.DEVICE", device)
                putExtra("android.bluetooth.device.extra.BATTERY_LEVEL", level)
                addFlags(0x04000000 or 0x01000000)
            }
            runCatching {
                adapterService.javaClass.findMethod(
                    "sendBroadcast",
                    Intent::class.java,
                    String::class.java,
                ).invoke(adapterService, intent, "android.permission.BLUETOOTH_CONNECT")
            }.getOrElse {
                context.sendBroadcast(intent, "android.permission.BLUETOOTH_CONNECT")
            }
        }.onFailure {
            Log.w(TAG, "Direct battery level broadcast failed level=$level", it)
        }
    }

    private fun airPodsManager(context: Context): Any? {
        val classLoader = context.classLoader ?: return null
        cachedAirPodsManager?.takeIf { it.first === classLoader }?.let { return it.second }
        val instance = resolveAirPodsManager(classLoader)
        cachedAirPodsManager = classLoader to instance
        return instance
    }

    private fun resolveAirPodsManager(classLoader: ClassLoader): Any? = runCatching {
        val clazz = Class.forName(AIRPODS_MANAGER, false, classLoader)
        val sInstance = clazz.getDeclaredField("sInstance").apply { isAccessible = true }.get(null)
        if (sInstance != null) return@runCatching sInstance

        val extendType = Class.forName(EXTEND_FEATURE_INTERFACE, false, classLoader)
        val featureType = Class.forName(FEATURE_INTERFACE, false, classLoader)
        val default = extendType.getField("DEFAULT").get(null)
        Class.forName(FEATURE_CACHE, false, classLoader)
            .getMethod("get", featureType)
            .invoke(null, default)
    }.getOrNull()

    private fun notifyAirPodsManager(context: Context, device: BluetoothDevice, info: IntArray) {
        val manager = airPodsManager(context) ?: return
        runCatching {
            val method = manager.javaClass.findMethod(
                "notifyBatteryLevelChanged",
                BluetoothDevice::class.java,
                IntArray::class.java,
            )
            method.invoke(manager, device, info)
        }.recoverCatching {
            val method = manager.javaClass.findMethod(
                "updateBatteryInfo",
                BluetoothDevice::class.java,
                Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
            method.invoke(
                manager,
                device,
                false,
                info[0],
                info[1],
                info[2],
                info[3],
                info[4],
                info[5],
            )
        }.onFailure {
            Log.d(TAG, "AirPodsManager notification failed: ${it.message}")
        }
    }

    /**
     * HuaweiHfpController 的 Context 通常是 A2dpService（ProfileService）；热重载恢复时可能是
     * Application。依次尝试 ProfileService.getAdapterService()、AdapterService 静态实例与
     * OPLUS 实现持有的 AdapterService（Android 17 已移除 AdapterService.getAdapterService()）。
     */
    private fun adapterService(context: Context): Any? {
        if (context.javaClass.name == ADAPTER_SERVICE) return context
        runCatching { context.javaClass.findMethod("getAdapterService").invoke(context) }
            .getOrNull()?.let { return it }
        runCatching {
            Class.forName(ADAPTER_SERVICE, false, context.classLoader)
                .getDeclaredField("sAdapterService")
                .apply { isAccessible = true }
                .get(null)
        }.getOrNull()?.let { return it }
        airPodsManager(context)?.let { manager ->
            runCatching {
                manager.javaClass.getDeclaredField("mAdapterService").apply { isAccessible = true }.get(manager)
            }.getOrNull()?.let { return it }
        }
        return oplusRemoteDevice(context)?.instance?.let { remote ->
            runCatching {
                remote.javaClass.getDeclaredField("mAdapterService").apply { isAccessible = true }.get(remote)
            }.getOrNull()
        }
    }

    private class RemoteDevice(val instance: Any, val type: Class<*>, val transportHfpAt: Int) {
        private val methods = OptionalVendorMethods(type)
        private val reported = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun call(name: String, parameterTypes: Array<Class<*>>, vararg args: Any?) {
            // ColorOS 16 does not expose all of ColorOS 17's battery extensions. A missing
            // transport/wear method must not prevent the battery or Android fallback update.
            runCatching {
                val method = methods.find(name, parameterTypes)
                if (method == null) {
                    if (reported.add(name)) Log.i(TAG, "ColorOS optional battery method unavailable: $name")
                } else {
                    method.invoke(instance, *args)
                }
            }.onFailure {
                if (reported.add("failed:$name")) Log.w(TAG, "ColorOS battery method failed: $name", it)
            }
        }
    }

    private fun oplusRemoteDevice(context: Context): RemoteDevice? {
        val classLoader = context.classLoader ?: return null
        cachedRemoteDevice?.takeIf { it.first === classLoader }?.let { return it.second }
        // 只缓存成功结果：蓝牙栈尚未完成 OPLUS 扩展注册时，下次上报会重新解析。
        return resolveOplusRemoteDevice(classLoader)?.also {
            cachedRemoteDevice = classLoader to it
        }
    }

    private fun resolveOplusRemoteDevice(classLoader: ClassLoader): RemoteDevice? = runCatching {
        val remoteType = Class.forName(REMOTE_DEVICE_INTERFACE, false, classLoader)
        val featureType = Class.forName(FEATURE_INTERFACE, false, classLoader)
        val default = remoteType.getField("DEFAULT").get(null)
        val instance = Class.forName(FEATURE_CACHE, false, classLoader)
            .getMethod("get", featureType)
            .invoke(null, default)
            ?: return@runCatching null
        val transport = runCatching { remoteType.getField("TRANSPORT_HFP_AT").getInt(null) }
            .getOrDefault(DEFAULT_TRANSPORT_HFP_AT)
        RemoteDevice(instance, remoteType, transport)
    }.onFailure {
        if (!remoteDeviceUnavailableLogged) {
            remoteDeviceUnavailableLogged = true
            Log.i(TAG, "ColorOS earphone battery channel unavailable; using Android battery only")
        }
    }.getOrNull()

    private fun Class<*>.findMethod(name: String, vararg parameterTypes: Class<*>): Method {
        var type: Class<*>? = this
        while (type != null) {
            runCatching { return type.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true } }
            type = type.superclass
        }
        throw NoSuchMethodException(name)
    }
}

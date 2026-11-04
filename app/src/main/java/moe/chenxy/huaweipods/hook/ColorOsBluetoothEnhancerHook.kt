package moe.chenxy.huaweipods.hook

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import moe.chenxy.huaweipods.BuildConfig
import moe.chenxy.huaweipods.backend.soundcore.SoundcoreDeviceIdentifier
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.pods.isSupported
import moe.chenxy.huaweipods.pods.resolveHuaweiDeviceRoute
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.PodParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.addHuaweiPodsAction

object ColorOsBluetoothEnhancerHook : HookContext() {
    private const val TAG = "HuaweiPods-ColorOsEnhancer"
    private const val EXTEND_FEATURE_MASK = 1581 or 2048 or 8192

    private fun getFeatureStatus(address: String, featureId: Int): Int? {
        val upper = address.uppercase()
        return when (featureId) {
            4 -> {
                val mode = ColorOsSystemBatteryBridge.getLatestAnc(upper) ?: NoiseControlMode.OFF
                val isAdaptive = ColorOsSystemBatteryBridge.isLatestAncAdaptive(upper)
                when (mode) {
                    NoiseControlMode.OFF -> 1
                    NoiseControlMode.NOISE_CANCELLATION -> if (isAdaptive) 4 else 2
                    NoiseControlMode.TRANSPARENCY -> 3
                    else -> 1
                }
            }
            2048 -> 0 // both earbuds worn
            8192 -> 1 // single-ear noise switch supported
            32 -> 1 // wear detection supported
            4096 -> 0
            else -> 0
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

    private var stateReceiver: BroadcastReceiver? = null
    private var stateReceiverContext: Context? = null
    private val supportedAddresses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun onHook() {
        hookAdapterServiceEnhancer()
        hookOplusAdapterServiceExtImpl()
        hookAirPodsManager()
        hookOplusBluetoothDevice()

        val app = currentApplicationOrNull()
        if (app != null) {
            registerStateReceiver(app)
        } else {
            hookApplicationOnCreate()
        }
    }

    override fun onClose() {
        val receiver = stateReceiver
        val context = stateReceiverContext
        if (receiver != null && context != null) {
            runCatching { context.unregisterReceiver(receiver) }
        }
        stateReceiver = null
        stateReceiverContext = null
    }

    private fun hookApplicationOnCreate() {
        runCatching {
            val appCls = findClass("android.app.Application")
            val method = appCls.getDeclaredMethod("onCreate")
            hookAfter(method) {
                val app = instance as? Application ?: return@hookAfter
                registerStateReceiver(app)
            }
        }.onFailure { Log.w(TAG, "Unable to hook Application.onCreate in bluetooth", it) }
    }

    private fun registerStateReceiver(context: Context) {
        if (stateReceiver != null) return
        val appContext = context.applicationContext ?: context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val received = intent ?: return
                val action = HuaweiPodsAction.canonical(received.action) ?: received.action ?: return
                when (action) {
                    HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        val isReady = received.getBooleanExtra("is_ready", true)
                        if (!isReady) {
                            Log.d(TAG, "Ignoring battery broadcast before isReady")
                            return
                        }
                        val address = received.getStringExtra("address") ?: return
                        val left = received.getIntExtra("left_battery", -1)
                        val leftConn = received.getBooleanExtra("left_connected", false)
                        val leftChg = received.getBooleanExtra("left_charging", false)
                        val right = received.getIntExtra("right_battery", -1)
                        val rightConn = received.getBooleanExtra("right_connected", false)
                        val rightChg = received.getBooleanExtra("right_charging", false)
                        val case = received.getIntExtra("case_battery", -1)
                        val caseConn = received.getBooleanExtra("case_connected", false)
                        val caseChg = received.getBooleanExtra("case_charging", false)

                        Log.i(TAG, "bluetooth stateReceiver onReceive: leftChg=$leftChg rightChg=$rightChg caseChg=$caseChg")

                        if (!leftConn && !rightConn && !caseConn) return
                        if (left < 0 && right < 0) return

                        val existingCase = ColorOsSystemBatteryBridge.getLatestBattery(address)?.case
                        val effectiveCase = if (case > 0 && case <= 100) {
                            PodParams(battery = case, isConnected = true, isCharging = caseChg)
                        } else if (existingCase != null && existingCase.battery > 0) {
                            existingCase.copy(isConnected = true, isCharging = caseChg)
                        } else {
                            PodParams(battery = 100, isConnected = true, isCharging = false)
                        }

                        val effectiveL = if (left in 0..100) left else 100
                        val effectiveR = if (right in 0..100) right else 100

                        val battery = BatteryParams(
                            left = PodParams(battery = effectiveL, isConnected = true, isCharging = leftChg),
                            right = PodParams(battery = effectiveR, isConnected = true, isCharging = rightChg),
                            case = effectiveCase,
                        )
                        val extraName = received.getStringExtra("device_name")
                        if (extraName != null && (SoundcoreDeviceIdentifier.isLiberty4Nc(extraName) || extraName.lowercase().contains("soundcore"))) {
                            supportedAddresses.add(address.uppercase())
                        }
                        val dev = runCatching { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) }.getOrNull()
                        if (dev != null && isSupportedAddress(address)) {
                            supportedAddresses.add(address.uppercase())
                            ColorOsSystemBatteryBridge.publish(appContext, dev, battery, isReady = true)
                        }
                    }
                    HuaweiPodsAction.ACTION_UPDATE_PODS_NOTIFICATION -> {
                        val isReady = received.getBooleanExtra("is_ready", true)
                        if (!isReady) {
                            Log.d(TAG, "Ignoring notification battery broadcast before isReady")
                            return
                        }
                        val battery = received.getParcelableExtra("batteryParams", BatteryParams::class.java)
                        val dev = received.getParcelableExtra("device", BluetoothDevice::class.java)
                        if (dev != null && battery != null && isSupportedDevice(dev)) {
                            val existingCase = ColorOsSystemBatteryBridge.getLatestBattery(dev.address)?.case
                            val rxCase = battery.case
                            val effectiveCase = if (rxCase != null && rxCase.battery > 0 && rxCase.battery <= 100) {
                                rxCase.copy(isConnected = true)
                            } else if (existingCase != null && existingCase.battery > 0) {
                                existingCase.copy(isConnected = true)
                            } else {
                                PodParams(battery = 100, isConnected = true, isCharging = false)
                            }
                            val effectiveBattery = battery.copy(case = effectiveCase)
                            ColorOsSystemBatteryBridge.publish(appContext, dev, effectiveBattery, isReady = true)
                        }
                    }
                    HuaweiPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        val address = received.getStringExtra("address")
                        if (address != null) {
                            supportedAddresses.add(address.uppercase())
                        }
                        val status = received.getIntExtra("status", 0)
                        val mode = NoiseControlMode.fromBroadcastStatus(status)
                        val isAdaptive = received.getBooleanExtra("is_adaptive", false)
                        ColorOsSystemBatteryBridge.setLatestAnc(address, mode, isAdaptive)
                    }
                    HuaweiPodsAction.ACTION_PODS_DISCONNECTED,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        val address = received.getStringExtra("address")
                            ?: (received.getParcelableExtra("device", BluetoothDevice::class.java)
                                ?: received.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java))?.address
                        if (address != null) {
                            val dev = runCatching { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) }.getOrNull()
                            if (dev != null) {
                                ColorOsSystemBatteryBridge.clear(appContext, dev)
                            }
                        }
                    }
                }
            }
        }
        runCatching {
            appContext.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED)
                    addHuaweiPodsAction(HuaweiPodsAction.ACTION_UPDATE_PODS_NOTIFICATION)
                    addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_ANC_CHANGED)
                    addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_CONNECTED)
                    addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_DISCONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                },
                Context.RECEIVER_EXPORTED,
            )
        }.onSuccess {
            stateReceiver = receiver
            stateReceiverContext = appContext
            Log.i(TAG, "Bluetooth state receiver registered successfully")
        }.onFailure { Log.w(TAG, "Failed to register bluetooth state receiver", it) }
    }

    fun isSupportedAddress(address: String?): Boolean {
        if (address == null) return false
        val addr = address.uppercase()
        if (supportedAddresses.contains(addr)) return true
        if (ColorOsSystemBatteryBridge.getLatestBattery(addr) != null) {
            supportedAddresses.add(addr)
            return true
        }
        val dev = runCatching { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) }.getOrNull()
        return isSupportedDevice(dev)
    }

    fun isSupportedDevice(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val addr = device.address.uppercase()
        if (supportedAddresses.contains(addr)) return true
        val name = runCatching { device.name ?: device.alias }.getOrNull()
        if (SoundcoreDeviceIdentifier.isLiberty4Nc(name) || SoundcoreDeviceIdentifier.isSoundcoreDevice(device)) {
            supportedAddresses.add(addr)
            return true
        }
        val route = resolveHuaweiDeviceRoute(device.address, name)
        if (route.isSupported) {
            supportedAddresses.add(addr)
            return true
        }
        return false
    }

    private fun extractDevice(inst: Any?): BluetoothDevice? {
        if (inst == null) return null
        if (inst is BluetoothDevice) return inst
        runCatching {
            val m = inst.javaClass.methods.firstOrNull { it.name == "getDevice" && it.parameterCount == 0 && it.returnType == BluetoothDevice::class.java }
            if (m != null) return m.invoke(inst) as? BluetoothDevice
        }
        var cls: Class<*>? = inst.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (field.type == BluetoothDevice::class.java) {
                    field.isAccessible = true
                    val dev = field.get(inst) as? BluetoothDevice
                    if (dev != null) return dev
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun hookAdapterServiceEnhancer() {
        val cls = runCatching {
            findClass("com.oplus.bluetooth.btservice.AdapterServiceEnhancer")
        }.getOrNull() ?: return

        runCatching {
            val method = cls.getDeclaredMethod("checkIsAirpodsDevice", BluetoothDevice::class.java)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    result = true
                }
            }
        }.onFailure { Log.w(TAG, "AdapterServiceEnhancer.checkIsAirpodsDevice hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getBatteryInfo", BluetoothDevice::class.java)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    if (!isDeviceConnected(device)) return@hookBefore
                    val battery = ColorOsSystemBatteryBridge.getLatestBattery(device.address) ?: return@hookBefore
                    val info = colorOsTwsBatteryInfo(battery)
                    if (info[2] == COLOR_OS_BATTERY_UNKNOWN) {
                        info[2] = 100
                        info[5] = COLOR_OS_CHARGE_NOT_CHARGING
                    }
                    result = info
                }
            }
        }.onFailure { Log.w(TAG, "AdapterServiceEnhancer.getBatteryInfo hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getDeviceExtendFeatureMask", BluetoothDevice::class.java)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    result = EXTEND_FEATURE_MASK
                }
            }
        }.onFailure { Log.w(TAG, "AdapterServiceEnhancer.getDeviceExtendFeatureMask hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getExtendFeatureStatus", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                val featureId = args.getOrNull(1) as? Int ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    getFeatureStatus(device.address, featureId)?.let { result = it }
                }
            }
        }.onFailure { Log.w(TAG, "AdapterServiceEnhancer.getExtendFeatureStatus hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod(
                "setExtendFeatureStatus",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                val featureId = args.getOrNull(1) as? Int ?: return@hookBefore
                val value = args.getOrNull(2) as? Int ?: return@hookBefore
                if (featureId == 4 && isSupportedDevice(device)) {
                    val (mode, isAdaptive, subMode) = when (value) {
                        1 -> Triple(NoiseControlMode.OFF, false, null)
                        2 -> Triple(NoiseControlMode.NOISE_CANCELLATION, false, null)
                        3 -> Triple(NoiseControlMode.TRANSPARENCY, false, null)
                        4 -> Triple(NoiseControlMode.NOISE_CANCELLATION, true, 3)
                        else -> Triple(NoiseControlMode.OFF, false, null)
                    }
                    ColorOsSystemBatteryBridge.setLatestAnc(device.address, mode, isAdaptive)
                    val context = currentApplicationOrNull() ?: stateReceiverContext
                    if (context != null) {
                        val intent = Intent(HuaweiPodsAction.ACTION_ANC_SELECT).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            putExtra("status", mode.broadcastStatus)
                            putExtra("address", device.address)
                            putExtra("is_adaptive", isAdaptive)
                            if (subMode != null) putExtra("submode", subMode)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        }
                        context.sendBroadcast(intent)
                    }
                    result = true
                }
            }
        }.onFailure { Log.w(TAG, "AdapterServiceEnhancer.setExtendFeatureStatus hook unavailable", it) }
    }

    private fun hookOplusAdapterServiceExtImpl() {
        val cls = runCatching {
            findClass("com.oplus.bluetooth.btservice.OplusAdapterServiceExtImpl")
        }.getOrNull() ?: return

        runCatching {
            val method = cls.getDeclaredMethod("checkIsAirpodsDevice", BluetoothDevice::class.java)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    result = true
                }
            }
        }.onFailure { Log.w(TAG, "OplusAdapterServiceExtImpl.checkIsAirpodsDevice hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getBatteryInfo", BluetoothDevice::class.java)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    if (!isDeviceConnected(device)) return@hookBefore
                    val battery = ColorOsSystemBatteryBridge.getLatestBattery(device.address) ?: return@hookBefore
                    val info = colorOsTwsBatteryInfo(battery)
                    if (info[2] <= 0 || info[5] == COLOR_OS_CHARGE_DISCONNECTED) {
                        val existingCase = battery.case ?: ColorOsSystemBatteryBridge.getLatestBattery(device.address)?.case
                        info[2] = if (existingCase != null && existingCase.battery > 0) existingCase.battery else 100
                        info[5] = if (existingCase?.isCharging == true) COLOR_OS_CHARGE_CHARGING else COLOR_OS_CHARGE_NOT_CHARGING
                    }
                    result = info
                }
            }
        }.onFailure { Log.w(TAG, "OplusAdapterServiceExtImpl.getBatteryInfo hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getDeviceExtendFeatureMask", BluetoothDevice::class.java)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    result = EXTEND_FEATURE_MASK
                }
            }
        }.onFailure { Log.w(TAG, "OplusAdapterServiceExtImpl.getDeviceExtendFeatureMask hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getExtendFeatureStatus", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                val featureId = args.getOrNull(1) as? Int ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    getFeatureStatus(device.address, featureId)?.let { result = it }
                }
            }
        }.onFailure { Log.w(TAG, "OplusAdapterServiceExtImpl.getExtendFeatureStatus hook unavailable", it) }
    }

    private fun hookAirPodsManager() {
        val cls = runCatching {
            findClass("com.oplus.bluetooth.feature.airpods.AirPodsManager")
        }.getOrNull() ?: return

        runCatching {
            val method = cls.getDeclaredMethod("checkIsAirpodsDevice", BluetoothDevice::class.java)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    result = true
                }
            }
        }.onFailure { Log.w(TAG, "AirPodsManager.checkIsAirpodsDevice hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getBatteryInfo", BluetoothDevice::class.java)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    if (!isDeviceConnected(device)) return@hookBefore
                    val battery = ColorOsSystemBatteryBridge.getLatestBattery(device.address) ?: return@hookBefore
                    val info = colorOsTwsBatteryInfo(battery)
                    if (info[2] <= 0 || info[5] == COLOR_OS_CHARGE_DISCONNECTED) {
                        val existingCase = battery.case ?: ColorOsSystemBatteryBridge.getLatestBattery(device.address)?.case
                        info[2] = if (existingCase != null && existingCase.battery > 0) existingCase.battery else 100
                        info[5] = if (existingCase?.isCharging == true) COLOR_OS_CHARGE_CHARGING else COLOR_OS_CHARGE_NOT_CHARGING
                    }
                    result = info
                }
            }
        }.onFailure { Log.w(TAG, "AirPodsManager.getBatteryInfo hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("notifyBatteryLevelChanged", BluetoothDevice::class.java, IntArray::class.java)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                val iArr = args.getOrNull(1) as? IntArray ?: return@hookBefore
                if (isSupportedDevice(device) && iArr.size >= 6) {
                    if (!isDeviceConnected(device)) return@hookBefore
                    val battery = ColorOsSystemBatteryBridge.getLatestBattery(device.address) ?: return@hookBefore
                    val patched = iArr.clone()
                    var changed = false
                    val left = battery.left
                    if (left != null && left.isConnected && left.battery in 1..100) {
                        if (patched[0] != left.battery) {
                            patched[0] = left.battery
                            changed = true
                        }
                        val desiredLeftChg = if (left.isCharging) COLOR_OS_CHARGE_CHARGING else COLOR_OS_CHARGE_NOT_CHARGING
                        if (patched[3] != desiredLeftChg) {
                            patched[3] = desiredLeftChg
                            changed = true
                        }
                    }
                    val right = battery.right
                    if (right != null && right.isConnected && right.battery in 1..100) {
                        if (patched[1] != right.battery) {
                            patched[1] = right.battery
                            changed = true
                        }
                        val desiredRightChg = if (right.isCharging) COLOR_OS_CHARGE_CHARGING else COLOR_OS_CHARGE_NOT_CHARGING
                        if (patched[4] != desiredRightChg) {
                            patched[4] = desiredRightChg
                            changed = true
                        }
                    }
                    val casePod = battery.case
                    if (casePod != null && casePod.isConnected && casePod.battery in 1..100) {
                        if (patched[2] != casePod.battery) {
                            patched[2] = casePod.battery
                            changed = true
                        }
                        val desiredCaseChg = if (casePod.isCharging) COLOR_OS_CHARGE_CHARGING else COLOR_OS_CHARGE_NOT_CHARGING
                        if (patched[5] != desiredCaseChg) {
                            patched[5] = desiredCaseChg
                            changed = true
                        }
                    }
                    if (changed) {
                        proceedWithArgs(device, patched)
                        return@hookBefore
                    }
                }
            }
        }.onFailure { Log.w(TAG, "AirPodsManager.notifyBatteryLevelChanged hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getDeviceExtendFeatureMask", BluetoothDevice::class.java)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    result = EXTEND_FEATURE_MASK
                }
            }
        }.onFailure { Log.w(TAG, "AirPodsManager.getDeviceExtendFeatureMask hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getExtendFeatureStatus", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                val featureId = args.getOrNull(1) as? Int ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    getFeatureStatus(device.address, featureId)?.let { result = it }
                }
            }
        }.onFailure { Log.w(TAG, "AirPodsManager.getExtendFeatureStatus hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod(
                "setExtendFeatureStatus",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                val featureId = args.getOrNull(1) as? Int ?: return@hookBefore
                val value = args.getOrNull(2) as? Int ?: return@hookBefore
                if (featureId == 4 && isSupportedDevice(device)) {
                    val (mode, isAdaptive, subMode) = when (value) {
                        1 -> Triple(NoiseControlMode.OFF, false, null)
                        2 -> Triple(NoiseControlMode.NOISE_CANCELLATION, false, null)
                        3 -> Triple(NoiseControlMode.TRANSPARENCY, false, null)
                        4 -> Triple(NoiseControlMode.NOISE_CANCELLATION, true, 3)
                        else -> Triple(NoiseControlMode.OFF, false, null)
                    }
                    ColorOsSystemBatteryBridge.setLatestAnc(device.address, mode, isAdaptive)
                    val context = currentApplicationOrNull() ?: stateReceiverContext
                    if (context != null) {
                        val intent = Intent(HuaweiPodsAction.ACTION_ANC_SELECT).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            putExtra("status", mode.broadcastStatus)
                            putExtra("address", device.address)
                            putExtra("is_adaptive", isAdaptive)
                            if (subMode != null) putExtra("submode", subMode)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        }
                        context.sendBroadcast(intent)
                    }
                    result = true
                }
            }
        }.onFailure { Log.w(TAG, "AirPodsManager.setExtendFeatureStatus hook unavailable", it) }
    }

    private fun hookOplusBluetoothDevice() {
        val cls = runCatching {
            findClass("android.bluetooth.OplusBluetoothDevice")
        }.getOrNull() ?: return

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
                    val battery = ColorOsSystemBatteryBridge.getLatestBattery(device.address) ?: return@hookBefore
                    val info = colorOsTwsBatteryInfo(battery)
                    if (info[2] <= 0 || info[5] == COLOR_OS_CHARGE_DISCONNECTED) {
                        val existingCase = battery.case ?: ColorOsSystemBatteryBridge.getLatestBattery(device.address)?.case
                        info[2] = if (existingCase != null && existingCase.battery > 0) existingCase.battery else 100
                        info[5] = if (existingCase?.isCharging == true) COLOR_OS_CHARGE_CHARGING else COLOR_OS_CHARGE_NOT_CHARGING
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
                        2 -> Triple(NoiseControlMode.NOISE_CANCELLATION, false, null)
                        3 -> Triple(NoiseControlMode.TRANSPARENCY, false, null)
                        4 -> Triple(NoiseControlMode.NOISE_CANCELLATION, true, 3)
                        else -> Triple(NoiseControlMode.OFF, false, null)
                    }
                    ColorOsSystemBatteryBridge.setLatestAnc(device.address, mode, isAdaptive)
                    val context = currentApplicationOrNull() ?: stateReceiverContext
                    if (context != null) {
                        val intent = Intent(HuaweiPodsAction.ACTION_ANC_SELECT).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            putExtra("status", mode.broadcastStatus)
                            putExtra("address", device.address)
                            putExtra("is_adaptive", isAdaptive)
                            if (subMode != null) putExtra("submode", subMode)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        }
                        context.sendBroadcast(intent)
                    }
                    result = true
                }
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothDevice.setExtendFeatureStatus hook unavailable", it) }
    }
}

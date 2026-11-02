package moe.chenxy.huaweipods.hook

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.chenxy.huaweipods.BuildConfig
import moe.chenxy.huaweipods.backend.soundcore.SoundcoreDeviceIdentifier
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.pods.isSupported
import moe.chenxy.huaweipods.pods.resolveHuaweiDeviceRoute
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction

object ColorOsBluetoothEnhancerHook : HookContext() {
    private const val TAG = "HuaweiPods-ColorOsEnhancer"

    override fun onHook() {
        hookAdapterServiceEnhancer()
        hookOplusAdapterServiceExtImpl()
        hookAirPodsManager()
        hookOplusBluetoothDevice()
    }

    fun isSupportedDevice(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val addr = device.address
        val name = runCatching { device.name ?: device.alias }.getOrNull()
        if (SoundcoreDeviceIdentifier.isLiberty4Nc(name) || SoundcoreDeviceIdentifier.isSoundcoreDevice(device)) return true
        val route = resolveHuaweiDeviceRoute(addr, name)
        return route.isSupported
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
                    val battery = ColorOsSystemBatteryBridge.getLatestBattery(device.address)
                    if (battery != null) {
                        result = colorOsTwsBatteryInfo(battery)
                    } else {
                        val level = runCatching {
                            device.javaClass.getMethod("getBatteryLevel").invoke(device) as? Int
                        }.getOrNull()?.coerceIn(0, 100) ?: 100
                        result = intArrayOf(level, level, level, 2, 2, 2)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "AdapterServiceEnhancer.getBatteryInfo hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getDeviceExtendFeatureMask", BluetoothDevice::class.java)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    result = 1581 // Supports battery, ANC, adaptive, wear, transparency; not headset
                }
            }
        }.onFailure { Log.w(TAG, "AdapterServiceEnhancer.getDeviceExtendFeatureMask hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getExtendFeatureStatus", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                val featureId = args.getOrNull(1) as? Int ?: return@hookBefore
                if (featureId == 4 && isSupportedDevice(device)) {
                    val mode = ColorOsSystemBatteryBridge.getLatestAnc(device.address) ?: NoiseControlMode.OFF
                    result = when (mode) {
                        NoiseControlMode.OFF -> 1
                        NoiseControlMode.NOISE_CANCELLATION -> 2
                        NoiseControlMode.TRANSPARENCY -> 3
                        else -> 1
                    }
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
                    val mode = when (value) {
                        1 -> NoiseControlMode.OFF
                        2 -> NoiseControlMode.NOISE_CANCELLATION
                        3 -> NoiseControlMode.TRANSPARENCY
                        else -> NoiseControlMode.OFF
                    }
                    val context = currentApplicationOrNull()
                    if (context != null) {
                        val intent = Intent(HuaweiPodsAction.ACTION_ANC_SELECT).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            putExtra("status", mode.broadcastStatus)
                            putExtra("address", device.address)
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
                    val battery = ColorOsSystemBatteryBridge.getLatestBattery(device.address)
                    if (battery != null) {
                        result = colorOsTwsBatteryInfo(battery)
                    } else {
                        val level = runCatching {
                            device.javaClass.getMethod("getBatteryLevel").invoke(device) as? Int
                        }.getOrNull()?.coerceIn(0, 100) ?: 100
                        result = intArrayOf(level, level, level, 2, 2, 2)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "OplusAdapterServiceExtImpl.getBatteryInfo hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getDeviceExtendFeatureMask", BluetoothDevice::class.java)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    result = 1581
                }
            }
        }.onFailure { Log.w(TAG, "OplusAdapterServiceExtImpl.getDeviceExtendFeatureMask hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getExtendFeatureStatus", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                val featureId = args.getOrNull(1) as? Int ?: return@hookBefore
                if (featureId == 4 && isSupportedDevice(device)) {
                    val mode = ColorOsSystemBatteryBridge.getLatestAnc(device.address) ?: NoiseControlMode.OFF
                    result = when (mode) {
                        NoiseControlMode.OFF -> 1
                        NoiseControlMode.NOISE_CANCELLATION -> 2
                        NoiseControlMode.TRANSPARENCY -> 3
                        else -> 1
                    }
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
                    val battery = ColorOsSystemBatteryBridge.getLatestBattery(device.address)
                    if (battery != null) {
                        result = colorOsTwsBatteryInfo(battery)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "AirPodsManager.getBatteryInfo hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getDeviceExtendFeatureMask", BluetoothDevice::class.java)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                if (isSupportedDevice(device)) {
                    result = 1581
                }
            }
        }.onFailure { Log.w(TAG, "AirPodsManager.getDeviceExtendFeatureMask hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getExtendFeatureStatus", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)
            hookBefore(method) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                val featureId = args.getOrNull(1) as? Int ?: return@hookBefore
                if (featureId == 4 && isSupportedDevice(device)) {
                    val mode = ColorOsSystemBatteryBridge.getLatestAnc(device.address) ?: NoiseControlMode.OFF
                    result = when (mode) {
                        NoiseControlMode.OFF -> 1
                        NoiseControlMode.NOISE_CANCELLATION -> 2
                        NoiseControlMode.TRANSPARENCY -> 3
                        else -> 1
                    }
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
                    val mode = when (value) {
                        1 -> NoiseControlMode.OFF
                        2 -> NoiseControlMode.NOISE_CANCELLATION
                        3 -> NoiseControlMode.TRANSPARENCY
                        else -> NoiseControlMode.OFF
                    }
                    val context = currentApplicationOrNull()
                    if (context != null) {
                        val intent = Intent(HuaweiPodsAction.ACTION_ANC_SELECT).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            putExtra("status", mode.broadcastStatus)
                            putExtra("address", device.address)
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

        fun extractDevice(inst: Any?): BluetoothDevice? {
            if (inst == null) return null
            return runCatching {
                inst.javaClass.declaredFields.firstOrNull { it.type == BluetoothDevice::class.java }
                    ?.apply { isAccessible = true }
                    ?.get(inst) as? BluetoothDevice
            }.getOrNull()
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
                    val battery = ColorOsSystemBatteryBridge.getLatestBattery(device.address)
                    if (battery != null) {
                        result = colorOsTwsBatteryInfo(battery)
                    }
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
                    result = 1581
                }
            }
        }.onFailure { Log.w(TAG, "OplusBluetoothDevice.getDeviceExtendFeatureMask hook unavailable", it) }

        runCatching {
            val method = cls.getDeclaredMethod("getExtendFeatureStatus", Int::class.javaPrimitiveType!!)
            hookBefore(method) {
                val device = extractDevice(instance)
                val featureId = args.firstOrNull() as? Int ?: return@hookBefore
                if (device != null && featureId == 4 && isSupportedDevice(device)) {
                    val mode = ColorOsSystemBatteryBridge.getLatestAnc(device.address) ?: NoiseControlMode.OFF
                    result = when (mode) {
                        NoiseControlMode.OFF -> 1
                        NoiseControlMode.NOISE_CANCELLATION -> 2
                        NoiseControlMode.TRANSPARENCY -> 3
                        else -> 1
                    }
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
                    val mode = when (value) {
                        1 -> NoiseControlMode.OFF
                        2 -> NoiseControlMode.NOISE_CANCELLATION
                        3 -> NoiseControlMode.TRANSPARENCY
                        else -> NoiseControlMode.OFF
                    }
                    val context = currentApplicationOrNull()
                    if (context != null) {
                        val intent = Intent(HuaweiPodsAction.ACTION_ANC_SELECT).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            putExtra("status", mode.broadcastStatus)
                            putExtra("address", device.address)
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

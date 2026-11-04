package moe.chenxy.huaweipods.backend.soundcore

import android.bluetooth.BluetoothDevice
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.isSoundcore

enum class SoundcoreModel(val modelCode: String, val displayName: String) {
    A3947("A3947", "Soundcore Liberty 4 NC"),
    A3952("A3952", "Soundcore Liberty 3 Pro"),
}

object SoundcoreDeviceIdentifier {
    const val MODEL_A3947 = "A3947"
    const val DISPLAY_NAME_A3947 = "Soundcore Liberty 4 NC"

    const val MODEL_A3952 = "A3952"
    const val DISPLAY_NAME_A3952 = "Soundcore Liberty 3 Pro"

    fun isLiberty4Nc(name: String?): Boolean {
        if (name == null) return false
        val clean = name.lowercase().replace(" ", "").replace("-", "")
        return clean.contains("liberty4nc") || clean.contains("a3947")
    }

    fun isLiberty3Pro(name: String?): Boolean {
        if (name == null) return false
        val clean = name.lowercase().replace(" ", "").replace("-", "")
        return clean.contains("liberty3pro") || clean.contains("a3952")
    }

    fun isSoundcoreName(name: String?): Boolean {
        if (name == null) return false
        return isLiberty4Nc(name) || isLiberty3Pro(name) || name.lowercase().contains("soundcore")
    }

    fun resolveRoute(name: String?): HuaweiDeviceRoute {
        return if (isLiberty3Pro(name)) {
            HuaweiDeviceRoute.SOUNDCORE_LIBERTY_3_PRO
        } else {
            HuaweiDeviceRoute.SOUNDCORE_LIBERTY_4_NC
        }
    }

    fun resolveRoute(device: BluetoothDevice): HuaweiDeviceRoute {
        val name = runCatching { device.name ?: device.alias }.getOrNull()
        return resolveRoute(name)
    }

    fun resolveModel(route: HuaweiDeviceRoute?, name: String? = null): SoundcoreModel {
        if (route == HuaweiDeviceRoute.SOUNDCORE_LIBERTY_3_PRO || isLiberty3Pro(name)) {
            return SoundcoreModel.A3952
        }
        return SoundcoreModel.A3947
    }

    fun isSoundcoreDevice(device: BluetoothDevice): Boolean {
        val name = runCatching { device.name ?: device.alias }.getOrNull()
        if (isSoundcoreName(name)) return true

        val uuids = runCatching { device.uuids }.getOrNull()
        if (uuids != null && uuids.any { SoundcoreUuid.isVendorUuid(it.uuid) }) {
            return true
        }

        val route = runCatching {
            with(moe.chenxy.huaweipods.pods.HuaweiDeviceRouteResolver) {
                device.address?.let { moe.chenxy.huaweipods.pods.resolveHuaweiDeviceRoute(it, device.name ?: device.alias) }
            }
        }.getOrNull()
        if (route?.isSoundcore == true) return true

        return false
    }
}

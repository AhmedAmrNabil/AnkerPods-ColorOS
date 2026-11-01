package moe.chenxy.huaweipods.backend.soundcore

import android.bluetooth.BluetoothDevice

object SoundcoreDeviceIdentifier {
    const val MODEL_A3947 = "A3947"
    const val DISPLAY_NAME_A3947 = "Soundcore Liberty 4 NC"

    fun isLiberty4Nc(name: String?): Boolean {
        if (name == null) return false
        val clean = name.lowercase().replace(" ", "").replace("-", "")
        return clean.contains("liberty4nc") || clean.contains("a3947")
    }

    fun isSoundcoreDevice(device: BluetoothDevice): Boolean {
        val name = runCatching { device.name ?: device.alias }.getOrNull()
        if (isLiberty4Nc(name)) return true
        if (name?.lowercase()?.contains("soundcore") == true) return true

        val uuids = runCatching { device.uuids }.getOrNull()
        if (uuids != null && uuids.any { SoundcoreUuid.isVendorUuid(it.uuid) }) {
            return true
        }

        val route = runCatching {
            with(moe.chenxy.huaweipods.pods.HuaweiDeviceRouteResolver) {
                device.address?.let { moe.chenxy.huaweipods.pods.resolveHuaweiDeviceRoute(it, device.name ?: device.alias) }
            }
        }.getOrNull()
        if (route == moe.chenxy.huaweipods.pods.HuaweiDeviceRoute.SOUNDCORE_LIBERTY_4_NC) return true

        return false
    }
}

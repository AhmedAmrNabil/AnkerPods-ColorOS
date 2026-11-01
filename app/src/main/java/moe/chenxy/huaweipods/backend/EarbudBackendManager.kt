package moe.chenxy.huaweipods.backend

import android.bluetooth.BluetoothDevice

object EarbudBackendManager {
    var activeBackend: EarbudBackend = HuaweiEarbudBackend
        private set

    fun getBackend(device: BluetoothDevice): EarbudBackend {
        return HuaweiEarbudBackend
    }

    fun isSupported(device: BluetoothDevice): Boolean {
        return getBackend(device).isSupported(device)
    }
}

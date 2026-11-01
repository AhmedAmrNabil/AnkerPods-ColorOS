package moe.chenxy.huaweipods.backend

import android.bluetooth.BluetoothDevice
import moe.chenxy.huaweipods.backend.soundcore.SoundcoreBackend

object EarbudBackendManager {
    var activeBackend: EarbudBackend = SoundcoreBackend
        private set

    fun getBackend(device: BluetoothDevice): EarbudBackend {
        val backend = if (SoundcoreBackend.isSupported(device)) {
            SoundcoreBackend
        } else {
            HuaweiEarbudBackend
        }
        activeBackend = backend
        return backend
    }

    fun isSupported(device: BluetoothDevice): Boolean {
        return SoundcoreBackend.isSupported(device) || HuaweiEarbudBackend.isSupported(device)
    }
}

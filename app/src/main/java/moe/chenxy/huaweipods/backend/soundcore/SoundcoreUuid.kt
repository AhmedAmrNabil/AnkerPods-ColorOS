package moe.chenxy.huaweipods.backend.soundcore

import android.bluetooth.BluetoothDevice
import java.util.UUID

object SoundcoreUuid {
    val STANDARD_RFCOMM_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private const val VENDOR_UUID_PREFIX = "0cf12d31-fac3-4553-bd80-d6832e7"

    fun isVendorUuid(uuid: UUID): Boolean {
        return uuid.toString().lowercase().startsWith(VENDOR_UUID_PREFIX)
    }

    fun selectRfcommUuid(device: BluetoothDevice): UUID {
        val uuids = runCatching { device.uuids }.getOrNull()
        if (uuids != null) {
            for (parcelUuid in uuids) {
                if (isVendorUuid(parcelUuid.uuid)) {
                    return parcelUuid.uuid
                }
            }
        }
        return STANDARD_RFCOMM_UUID
    }
}

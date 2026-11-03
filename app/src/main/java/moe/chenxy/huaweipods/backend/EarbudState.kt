package moe.chenxy.huaweipods.backend

import android.bluetooth.BluetoothDevice
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams

data class EarbudState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val battery: BatteryParams? = null,
    val ancMode: NoiseControlMode = NoiseControlMode.UNKNOWN,
    val ancSubMode: Int? = null,
    val isAdaptiveAnc: Boolean = false,
    val device: BluetoothDevice? = null,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
)

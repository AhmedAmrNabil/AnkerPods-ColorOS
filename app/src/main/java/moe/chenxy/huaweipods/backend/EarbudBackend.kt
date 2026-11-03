package moe.chenxy.huaweipods.backend

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.flow.StateFlow
import moe.chenxy.huaweipods.pods.NoiseControlMode

interface EarbudBackend {
    val state: StateFlow<EarbudState>

    fun isSupported(device: BluetoothDevice): Boolean
    fun connect(context: Context, device: BluetoothDevice)
    fun disconnect(context: Context, device: BluetoothDevice)
    fun setAncMode(mode: NoiseControlMode)
    fun setAncMode(mode: NoiseControlMode, subMode: Int?) {
        setAncMode(mode)
    }
    fun setAncMode(mode: NoiseControlMode, subMode: Int?, isAdaptive: Boolean) {
        setAncMode(mode, subMode)
    }
    fun cycleAncMode()

    fun audioSessionAddress(): String?
    fun audioSessionGeneration(): Long

    fun saveHotReloadState(outState: Bundle)
    fun restoreHotReloadState(savedState: Bundle)
    fun hotReloadSessionAddress(savedState: Bundle): String?
    fun closeForHotReload()
}

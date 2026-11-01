package moe.chenxy.huaweipods.backend

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import moe.chenxy.huaweipods.pods.HuaweiHfpController
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.pods.huaweiDeviceRoute
import moe.chenxy.huaweipods.pods.isSupported
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams

object HuaweiEarbudBackend : EarbudBackend {
    private val _state = MutableStateFlow(EarbudState())
    override val state: StateFlow<EarbudState> = _state.asStateFlow()

    override fun isSupported(device: BluetoothDevice): Boolean {
        return runCatching { device.huaweiDeviceRoute().isSupported }.getOrDefault(false)
    }

    override fun connect(context: Context, device: BluetoothDevice) {
        _state.update {
            it.copy(
                isConnecting = true,
                device = device,
                deviceName = runCatching { device.name ?: device.alias }.getOrNull(),
                deviceAddress = device.address,
            )
        }
        HuaweiHfpController.connectPod(context, device)
    }

    override fun disconnect(context: Context, device: BluetoothDevice) {
        HuaweiHfpController.disconnectedPod(context, device)
        _state.update {
            EarbudState()
        }
    }

    override fun setAncMode(mode: NoiseControlMode) {
        HuaweiHfpController.setAncMode(mode.broadcastStatus)
    }

    override fun cycleAncMode() {
        val currentMode = _state.value.ancMode
        val nextMode = when (currentMode) {
            NoiseControlMode.OFF -> NoiseControlMode.NOISE_CANCELLATION
            NoiseControlMode.NOISE_CANCELLATION -> NoiseControlMode.TRANSPARENCY
            NoiseControlMode.TRANSPARENCY -> NoiseControlMode.OFF
            else -> NoiseControlMode.NOISE_CANCELLATION
        }
        setAncMode(nextMode)
    }

    override fun audioSessionAddress(): String? = HuaweiHfpController.audioSessionAddress()

    override fun audioSessionGeneration(): Long = HuaweiHfpController.audioSessionGeneration()

    override fun saveHotReloadState(outState: Bundle) {
        HuaweiHfpController.saveHotReloadState(outState)
    }

    override fun restoreHotReloadState(savedState: Bundle) {
        HuaweiHfpController.restoreHotReloadState(savedState)
    }

    override fun hotReloadSessionAddress(savedState: Bundle): String? {
        return HuaweiHfpController.hotReloadSessionAddress(savedState)
    }

    override fun closeForHotReload() {
        HuaweiHfpController.closeForHotReload()
    }

    internal fun updateBattery(battery: BatteryParams?) {
        _state.update { it.copy(battery = battery) }
    }

    internal fun updateAncMode(mode: NoiseControlMode) {
        _state.update { it.copy(ancMode = mode) }
    }

    internal fun updateConnectionState(isConnected: Boolean, isConnecting: Boolean) {
        _state.update { it.copy(isConnected = isConnected, isConnecting = isConnecting) }
    }
}

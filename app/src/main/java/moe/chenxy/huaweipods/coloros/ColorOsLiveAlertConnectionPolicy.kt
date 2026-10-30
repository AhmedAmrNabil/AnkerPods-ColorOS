package moe.chenxy.huaweipods.coloros

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile

/** A Profile disconnect is only a hint; another audio Profile may still be connected. */
internal object ColorOsLiveAlertConnectionPolicy {
    fun matchesDevice(currentAddress: String?, eventAddress: String?): Boolean =
        !currentAddress.isNullOrBlank() && !eventAddress.isNullOrBlank() &&
            currentAddress.equals(eventAddress, ignoreCase = true)

    fun shouldCheckProfileDisconnect(
        currentAddress: String?,
        eventAddress: String?,
        eventState: Int,
    ): Boolean = matchesDevice(currentAddress, eventAddress) &&
        eventState == BluetoothProfile.STATE_DISCONNECTED

    fun shouldCancelAfterProfileCheck(
        currentAddress: String?,
        checkedAddress: String?,
        a2dpState: Int?,
        headsetState: Int?,
    ): Boolean = matchesDevice(currentAddress, checkedAddress) &&
        a2dpState == BluetoothProfile.STATE_DISCONNECTED &&
        headsetState == BluetoothProfile.STATE_DISCONNECTED

    fun isBluetoothTurningOffOrOff(state: Int): Boolean =
        state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF

    /**
     * 关机类广播（`ACTION_STATE_CHANGED`）没有身份信息，任何持 `BLUETOOTH_CONNECT` 的应用都能发。
     * 之前只对 `STATE_OFF` 核对真实适配器状态，`STATE_TURNING_OFF` 直接照信 —— 伪造一条
     * `TURNING_OFF` 就能把正在显示的流体云卡片收掉。现在两种状态都必须与真实适配器状态一致，
     * 拿不到真实状态时一律不取消（fail-closed：真关机时进程本来也会退出）。
     */
    fun shouldCancelForAdapterState(claimedState: Int, actualState: Int?): Boolean {
        if (!isBluetoothTurningOffOrOff(claimedState)) return false
        if (actualState == null) return false
        return isBluetoothTurningOffOrOff(actualState)
    }
}

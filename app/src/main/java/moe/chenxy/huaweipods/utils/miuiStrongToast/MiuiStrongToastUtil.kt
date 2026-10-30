package moe.chenxy.huaweipods.utils.miuiStrongToast

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import moe.chenxy.huaweipods.platform.RomIntegrationPolicy
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.sendIdentitySharingBroadcast

object MiuiStrongToastUtil {
    private fun notificationHostPackage(): String = RomIntegrationPolicy.notificationHostPackage()

    fun showPodsNotificationByMiuiBt(
        context: Context,
        batteryParams: BatteryParams,
        device: BluetoothDevice,
        presentationOnly: Boolean = false,
    ) {
        val intent = Intent(HuaweiPodsAction.ACTION_UPDATE_PODS_NOTIFICATION)
        intent.putExtra("batteryParams", batteryParams)
        intent.putExtra("device", device)
        intent.putExtra(HuaweiPodsAction.EXTRA_PRESENTATION_ONLY, presentationOnly)
        intent.`package` = notificationHostPackage()
        // 通知宿主接收器按 Action 域 fail-closed，裸 sendBroadcast 的 sentFromPackage 是 null，会被整条丢掉。
        context.sendIdentitySharingBroadcast(intent)
    }

    fun cancelPodsNotificationByMiuiBt(
        context: Context,
        device: BluetoothDevice,
    ) {
        val intent = Intent(HuaweiPodsAction.ACTION_CANCEL_PODS_NOTIFICATION)
        intent.putExtra("device", device)
        intent.`package` = notificationHostPackage()
        context.sendIdentitySharingBroadcast(intent)
    }
}

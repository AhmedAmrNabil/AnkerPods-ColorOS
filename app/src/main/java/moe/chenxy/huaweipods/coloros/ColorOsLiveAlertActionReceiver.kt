package moe.chenxy.huaweipods.coloros

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.pods.decodeHuaweiDeviceRouteFromBroadcast
import moe.chenxy.huaweipods.pods.encodeHuaweiDeviceRouteForBroadcast
import moe.chenxy.huaweipods.pods.supportsAnc
import moe.chenxy.huaweipods.pods.supportsLowLatencyControl
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.sendIdentitySharingBroadcast

/**
 * 流体云卡片上的低时延按钮。通知按钮只能触发本应用内的 PendingIntent，这里再以模块身份把
 * 控制指令转发给蓝牙进程；按钮文字在蓝牙进程回报写入结果、电量通知重建后更新。
 */
class ColorOsLiveAlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val routeExtra = intent.getStringExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE)
        val address = intent.getStringExtra(EXTRA_ADDRESS)?.takeIf(String::isNotBlank) ?: return
        when (intent.action) {
            ACTION_TOGGLE_LOW_LATENCY -> {
                val route = decodeHuaweiDeviceRouteFromBroadcast(routeExtra)
                    ?.takeIf { it.supportsLowLatencyControl } ?: return
                val enabled = intent.getBooleanExtra(HuaweiPodsAction.EXTRA_HUAWEI_LOW_LATENCY_ENABLED, false)
                context.sendIdentitySharingBroadcast(Intent(HuaweiPodsAction.ACTION_HUAWEI_LOW_LATENCY_SET).apply {
                    putExtra("address", address)
                    putExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE, routeExtra)
                    putExtra(HuaweiPodsAction.EXTRA_HUAWEI_LOW_LATENCY_ENABLED, enabled)
                    setPackage(BLUETOOTH_PACKAGE)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                })
                Log.i(TAG, "Live Alert low-latency requested enabled=$enabled route=$route")
            }

            ACTION_CYCLE_NOISE_MODE -> {
                val route = decodeHuaweiDeviceRouteFromBroadcast(routeExtra)?.takeIf { it.supportsAnc } ?: return
                val mode = NoiseControlMode.fromBroadcastStatus(intent.getIntExtra("status", 0))
                if (mode == NoiseControlMode.UNKNOWN) return
                context.sendIdentitySharingBroadcast(Intent(HuaweiPodsAction.ACTION_ANC_SELECT).apply {
                    putExtra("address", address)
                    putExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE, routeExtra)
                    putExtra("status", mode.broadcastStatus)
                    setPackage(BLUETOOTH_PACKAGE)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                })
                Log.i(TAG, "Live Alert noise mode requested mode=$mode route=$route")
            }
        }
    }

    companion object {
        private const val TAG = "HuaweiPods-LiveAlert"
        private const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
        private const val ACTION_TOGGLE_LOW_LATENCY =
            "moe.chenxy.huaweipods.coloros.LIVE_ALERT_TOGGLE_LOW_LATENCY"
        private const val ACTION_CYCLE_NOISE_MODE =
            "moe.chenxy.huaweipods.coloros.LIVE_ALERT_CYCLE_NOISE_MODE"
        private const val EXTRA_ADDRESS = "address"

        /** 同一副耳机的两个按钮必须用不同的 request code，否则 PendingIntent 会互相覆盖 extras。 */
        private const val NOISE_REQUEST_CODE_SALT = 0x4E4F4953

        /** 切换到 [enabled] 的按钮 PendingIntent；按地址区分，避免多副耳机互相覆盖。 */
        fun toggleLowLatencyIntent(
            context: Context,
            address: String,
            route: HuaweiDeviceRoute,
            enabled: Boolean,
        ): PendingIntent? {
            val routeExtra = encodeHuaweiDeviceRouteForBroadcast(route) ?: return null
            return PendingIntent.getBroadcast(
                context,
                address.hashCode(),
                Intent(context, ColorOsLiveAlertActionReceiver::class.java).apply {
                    action = ACTION_TOGGLE_LOW_LATENCY
                    putExtra(EXTRA_ADDRESS, address)
                    putExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE, routeExtra)
                    putExtra(HuaweiPodsAction.EXTRA_HUAWEI_LOW_LATENCY_ENABLED, enabled)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /** 切到 [mode] 的降噪按钮。 */
        fun cycleNoiseModeIntent(
            context: Context,
            address: String,
            route: HuaweiDeviceRoute?,
            mode: NoiseControlMode,
        ): PendingIntent? {
            if (route == null) return null
            val routeExtra = encodeHuaweiDeviceRouteForBroadcast(route) ?: return null
            return PendingIntent.getBroadcast(
                context,
                address.hashCode() xor NOISE_REQUEST_CODE_SALT,
                Intent(context, ColorOsLiveAlertActionReceiver::class.java).apply {
                    action = ACTION_CYCLE_NOISE_MODE
                    putExtra(EXTRA_ADDRESS, address)
                    putExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE, routeExtra)
                    putExtra("status", mode.broadcastStatus)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}

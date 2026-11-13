package moe.chenxy.huaweipods.ui.components

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.config.ConfigManager
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.HuaweiWearDetectionController
import moe.chenxy.huaweipods.pods.supportsWearDetection
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 佩戴检测开关控制，支持华为与 Soundcore 耳机。
 */
@Composable
fun WearDetectionControl(address: String, route: HuaweiDeviceRoute) {
    if (!route.supportsWearDetection) return
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }
    val wearKey = "wear_detection_$address"
    var enabled by remember(address, route) {
        mutableStateOf(prefs.getBoolean(wearKey, true))
    }
    var pending by remember(address, route) { mutableStateOf(false) }

    DisposableEffect(address, route) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val received = intent ?: return
                if (received.action == HuaweiPodsAction.ACTION_HUAWEI_WEAR_DETECTION_CHANGED) {
                    val addr = received.getStringExtra("address")
                    if (addr == null || addr.equals(address, ignoreCase = true)) {
                        val state = received.getBooleanExtra(
                            HuaweiPodsAction.EXTRA_HUAWEI_WEAR_DETECTION_ENABLED,
                            enabled,
                        )
                        enabled = state
                        prefs.edit().putBoolean(wearKey, state).apply()
                    }
                }
            }
        }
        val filter = IntentFilter(HuaweiPodsAction.ACTION_HUAWEI_WEAR_DETECTION_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val dev = context.wearDetectionDevice(address)
        if (dev != null) {
            HuaweiWearDetectionController.requestState(context, dev, route) { cur ->
                if (cur != null) {
                    enabled = cur
                    prefs.edit().putBoolean(wearKey, cur).apply()
                }
            }
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    val toggle = {
        if (!pending) {
            val target = !enabled
            val device = context.wearDetectionDevice(address)
            if (device == null) {
                Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show()
            } else {
                pending = true
                HuaweiWearDetectionController.setEnabled(context, device, route, target) { success ->
                    pending = false
                    if (success) {
                        enabled = target
                        prefs.edit().putBoolean(wearKey, target).apply()
                    } else {
                        Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    SwitchPreference(
        title = stringResource(R.string.huawei_gesture_wear_detection),
        summary = stringResource(R.string.soundcore_wear_detection_summary),
        checked = enabled,
        enabled = !pending,
        onCheckedChange = { toggle() },
    )
}

@SuppressLint("MissingPermission")
private fun Context.wearDetectionDevice(address: String) =
    takeIf { BluetoothAdapter.checkBluetoothAddress(address) }
        ?.getSystemService(BluetoothManager::class.java)
        ?.adapter
        ?.getRemoteDevice(address)

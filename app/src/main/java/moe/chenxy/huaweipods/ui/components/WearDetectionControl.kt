package moe.chenxy.huaweipods.ui.components

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.config.ConfigManager
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.HuaweiWearDetectionController
import moe.chenxy.huaweipods.pods.supportsWearDetection
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !pending, role = Role.Switch, onClick = toggle)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.huawei_gesture_wear_detection),
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.headline1,
            )
            Text(
                text = stringResource(R.string.soundcore_wear_detection_summary),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )
        }
        Spacer(Modifier.width(12.dp))
        Checkbox(
            state = if (enabled) ToggleableState.On else ToggleableState.Off,
            enabled = !pending,
            onClick = toggle,
        )
    }
}

@SuppressLint("MissingPermission")
private fun Context.wearDetectionDevice(address: String) =
    takeIf { BluetoothAdapter.checkBluetoothAddress(address) }
        ?.getSystemService(BluetoothManager::class.java)
        ?.adapter
        ?.getRemoteDevice(address)

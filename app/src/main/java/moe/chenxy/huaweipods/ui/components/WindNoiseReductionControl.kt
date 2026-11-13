package moe.chenxy.huaweipods.ui.components

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.HuaweiWindNoiseReductionController
import moe.chenxy.huaweipods.pods.supportsWindNoiseReduction
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun WindNoiseReductionControl(address: String, route: HuaweiDeviceRoute) {
    if (!route.supportsWindNoiseReduction) return
    val context = LocalContext.current
    var enabled by remember(address, route) { mutableStateOf<Boolean?>(null) }
    var pending by remember(address, route) { mutableStateOf(false) }
    val device = remember(context, address) { context.windNoiseDevice(address) }

    LaunchedEffect(device, route) {
        device?.let {
            HuaweiWindNoiseReductionController.requestState(context, it, route) { value ->
                enabled = value
            }
        }
    }

    val toggle = {
        if (!pending && device != null) {
            val target = enabled != true
            pending = true
            HuaweiWindNoiseReductionController.setEnabled(
                context = context,
                device = device,
                route = route,
                enabled = target,
                onState = { value -> enabled = value },
                onComplete = { success ->
                    pending = false
                    if (!success) {
                        Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }

    SwitchPreference(
        title = stringResource(R.string.wind_noise_reduction),
        summary = stringResource(R.string.wind_noise_reduction_summary),
        checked = enabled == true,
        enabled = !pending && device != null,
        onCheckedChange = { toggle() },
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    )
}

@SuppressLint("MissingPermission")
private fun Context.windNoiseDevice(address: String) =
    takeIf { BluetoothAdapter.checkBluetoothAddress(address) }
        ?.getSystemService(BluetoothManager::class.java)
        ?.adapter
        ?.getRemoteDevice(address)

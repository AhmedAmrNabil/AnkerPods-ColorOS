package moe.chenxy.huaweipods.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.backend.soundcore.SoundcoreBackend
import moe.chenxy.huaweipods.backend.soundcore.SoundcoreStateManager
import moe.chenxy.huaweipods.config.ConfigManager
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.supportsSurroundSound
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun SurroundSoundControl(address: String, route: HuaweiDeviceRoute) {
    if (!route.supportsSurroundSound) return
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }
    val prefKey = "soundcore_surround_${address.lowercase()}"
    var enabled by remember(address, route) {
        mutableStateOf(SoundcoreStateManager.surroundSound ?: prefs.getBoolean(prefKey, false))
    }
    var pending by remember(address, route) { mutableStateOf(false) }

    LaunchedEffect(SoundcoreStateManager.surroundSound) {
        SoundcoreStateManager.surroundSound?.let { enabled = it }
    }

    val toggle = {
        if (!pending) {
            val target = !enabled
            pending = true
            SoundcoreBackend.setSurroundSound(target)
            prefs.edit().putBoolean(prefKey, target).apply()
            enabled = target
            pending = false
        }
    }

    SwitchPreference(
        title = stringResource(R.string.soundcore_surround_sound),
        summary = stringResource(R.string.soundcore_surround_sound_summary),
        checked = enabled,
        enabled = !pending,
        onCheckedChange = { toggle() },
    )
}

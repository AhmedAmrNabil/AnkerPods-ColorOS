package moe.chenxy.huaweipods.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.backend.soundcore.SoundcoreBackend
import moe.chenxy.huaweipods.backend.soundcore.SoundcoreStateManager
import moe.chenxy.huaweipods.config.ConfigManager
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.supportsSurroundSound
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
                text = stringResource(R.string.soundcore_surround_sound),
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.headline1,
            )
            Text(
                text = stringResource(R.string.soundcore_surround_sound_summary),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = enabled,
            enabled = !pending,
            onCheckedChange = { toggle() },
        )
    }
}

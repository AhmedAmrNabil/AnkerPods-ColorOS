package moe.chenxy.huaweipods.widget

import android.content.Context
import android.content.Intent
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.sendIdentitySharingBroadcast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import moe.chenxy.huaweipods.backend.soundcore.SoundcoreBackend
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction

object SoundcoreWidgetKeys {
    val KEY_IS_CONNECTED = booleanPreferencesKey("is_connected")
    val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
    val KEY_LEFT_BATTERY = intPreferencesKey("left_battery")
    val KEY_LEFT_CHARGING = booleanPreferencesKey("left_charging")
    val KEY_RIGHT_BATTERY = intPreferencesKey("right_battery")
    val KEY_RIGHT_CHARGING = booleanPreferencesKey("right_charging")
    val KEY_CASE_BATTERY = intPreferencesKey("case_battery")
    val KEY_CASE_CHARGING = booleanPreferencesKey("case_charging")
    val KEY_ANC_MODE = intPreferencesKey("anc_mode")

    val PARAM_ANC_MODE = ActionParameters.Key<Int>("target_anc_mode")
}

class SoundcoreWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
            val isConnected = prefs[SoundcoreWidgetKeys.KEY_IS_CONNECTED] ?: false
            val deviceName = prefs[SoundcoreWidgetKeys.KEY_DEVICE_NAME] ?: "Soundcore Liberty 4 NC"
            val leftBattery = prefs[SoundcoreWidgetKeys.KEY_LEFT_BATTERY] ?: -1
            val leftCharging = prefs[SoundcoreWidgetKeys.KEY_LEFT_CHARGING] ?: false
            val rightBattery = prefs[SoundcoreWidgetKeys.KEY_RIGHT_BATTERY] ?: -1
            val rightCharging = prefs[SoundcoreWidgetKeys.KEY_RIGHT_CHARGING] ?: false
            val caseBattery = prefs[SoundcoreWidgetKeys.KEY_CASE_BATTERY] ?: -1
            val caseCharging = prefs[SoundcoreWidgetKeys.KEY_CASE_CHARGING] ?: false
            val ancMode = prefs[SoundcoreWidgetKeys.KEY_ANC_MODE] ?: NoiseControlMode.UNKNOWN.broadcastStatus

            val activeBg = Color(0xFF2A6DF4)
            val inactiveBg = Color(0x22FFFFFF)
            val cardBg = Color(0xFF1E1E24)
            val textPrimary = Color.White
            val textSecondary = Color(0xFFAAAAAA)

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(cardBg)
                    .cornerRadius(16.dp)
                    .padding(12.dp)
            ) {
                // Header
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            text = deviceName,
                            style = TextStyle(color = androidx.glance.unit.ColorProvider(textPrimary), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (isConnected) "Connected" else "Disconnected",
                            style = TextStyle(color = androidx.glance.unit.ColorProvider(if (isConnected) Color(0xFF4CAF50) else textSecondary), fontSize = 11.sp)
                        )
                    }
                    Box(
                        modifier = GlanceModifier
                            .background(Color(0x33FFFFFF))
                            .cornerRadius(12.dp)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clickable(actionRunCallback<RefreshWidgetActionCallback>())
                    ) {
                        Text(
                            text = "Refresh",
                            style = TextStyle(color = androidx.glance.unit.ColorProvider(textPrimary), fontSize = 11.sp)
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.height(8.dp))

                // Battery Row
                if (isConnected) {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val leftText = if (leftBattery >= 0) "L: " + (if (leftCharging) "⚡" else "") + "$leftBattery%" else "L: --"
                        val rightText = if (rightBattery >= 0) "R: " + (if (rightCharging) "⚡" else "") + "$rightBattery%" else "R: --"
                        val caseText = if (caseBattery >= 0) "Case: " + (if (caseCharging) "⚡" else "") + "$caseBattery%" else "Case: --"

                        Text(
                            text = leftText,
                            style = TextStyle(color = androidx.glance.unit.ColorProvider(textPrimary), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            modifier = GlanceModifier.defaultWeight()
                        )
                        Text(
                            text = rightText,
                            style = TextStyle(color = androidx.glance.unit.ColorProvider(textPrimary), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            modifier = GlanceModifier.defaultWeight()
                        )
                        Text(
                            text = caseText,
                            style = TextStyle(color = androidx.glance.unit.ColorProvider(textPrimary), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            modifier = GlanceModifier.defaultWeight()
                        )
                    }
                } else {
                    Text(
                        text = "Earbuds not connected",
                        style = TextStyle(color = androidx.glance.unit.ColorProvider(textSecondary), fontSize = 12.sp)
                    )
                }

                Spacer(modifier = GlanceModifier.height(10.dp))

                // ANC Controls Row
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isOff = ancMode == NoiseControlMode.OFF.broadcastStatus
                    val isAnc = ancMode == NoiseControlMode.NOISE_CANCELLATION.broadcastStatus
                    val isTrans = ancMode == NoiseControlMode.TRANSPARENCY.broadcastStatus

                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .background(if (isOff) activeBg else inactiveBg)
                            .cornerRadius(12.dp)
                            .padding(vertical = 6.dp)
                            .clickable(actionRunCallback<SetAncActionCallback>(
                                actionParametersOf(SoundcoreWidgetKeys.PARAM_ANC_MODE to NoiseControlMode.OFF.broadcastStatus)
                            )),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Normal",
                            style = TextStyle(
                                color = androidx.glance.unit.ColorProvider(if (isOff) Color.White else textSecondary),
                                fontSize = 12.sp,
                                fontWeight = if (isOff) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                    }

                    Spacer(modifier = GlanceModifier.width(6.dp))

                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .background(if (isAnc) activeBg else inactiveBg)
                            .cornerRadius(12.dp)
                            .padding(vertical = 6.dp)
                            .clickable(actionRunCallback<SetAncActionCallback>(
                                actionParametersOf(SoundcoreWidgetKeys.PARAM_ANC_MODE to NoiseControlMode.NOISE_CANCELLATION.broadcastStatus)
                            )),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "ANC",
                            style = TextStyle(
                                color = androidx.glance.unit.ColorProvider(if (isAnc) Color.White else textSecondary),
                                fontSize = 12.sp,
                                fontWeight = if (isAnc) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                    }

                    Spacer(modifier = GlanceModifier.width(6.dp))

                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .background(if (isTrans) activeBg else inactiveBg)
                            .cornerRadius(12.dp)
                            .padding(vertical = 6.dp)
                            .clickable(actionRunCallback<SetAncActionCallback>(
                                actionParametersOf(SoundcoreWidgetKeys.PARAM_ANC_MODE to NoiseControlMode.TRANSPARENCY.broadcastStatus)
                            )),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Transparency",
                            style = TextStyle(
                                color = androidx.glance.unit.ColorProvider(if (isTrans) Color.White else textSecondary),
                                fontSize = 12.sp,
                                fontWeight = if (isTrans) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                    }
                }
            }
        }
    }
}

class SetAncActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val targetMode = parameters[SoundcoreWidgetKeys.PARAM_ANC_MODE] ?: return
        val intent = Intent(HuaweiPodsAction.ACTION_ANC_SELECT).apply {
            setPackage("com.android.bluetooth")
            putExtra("status", targetMode)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        context.sendIdentitySharingBroadcast(intent)

        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[SoundcoreWidgetKeys.KEY_ANC_MODE] = targetMode
            prefs[SoundcoreWidgetKeys.KEY_IS_CONNECTED] = true
        }
        SoundcoreWidget().update(context, glanceId)
    }
}

class RefreshWidgetActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val backendState = SoundcoreBackend.state.value
        if (backendState.isConnected) {
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[SoundcoreWidgetKeys.KEY_IS_CONNECTED] = true
                backendState.deviceName?.let { prefs[SoundcoreWidgetKeys.KEY_DEVICE_NAME] = it }
                backendState.battery?.let { b ->
                    b.left?.let {
                        prefs[SoundcoreWidgetKeys.KEY_LEFT_BATTERY] = it.battery
                        prefs[SoundcoreWidgetKeys.KEY_LEFT_CHARGING] = it.isCharging
                    }
                    b.right?.let {
                        prefs[SoundcoreWidgetKeys.KEY_RIGHT_BATTERY] = it.battery
                        prefs[SoundcoreWidgetKeys.KEY_RIGHT_CHARGING] = it.isCharging
                    }
                    b.case?.let {
                        prefs[SoundcoreWidgetKeys.KEY_CASE_BATTERY] = it.battery
                        prefs[SoundcoreWidgetKeys.KEY_CASE_CHARGING] = it.isCharging
                    }
                }
                prefs[SoundcoreWidgetKeys.KEY_ANC_MODE] = backendState.ancMode.broadcastStatus
            }
            SoundcoreWidget().update(context, glanceId)
        }

        val intent = Intent(HuaweiPodsAction.ACTION_REFRESH_STATUS).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        context.sendIdentitySharingBroadcast(intent)
    }
}

package moe.chenxy.huaweipods.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.state.updateAppWidgetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.chenxy.huaweipods.backend.soundcore.SoundcoreBackend
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction

class SoundcoreWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = SoundcoreWidget()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        val rawAction = intent.action ?: return
        val action = HuaweiPodsAction.canonical(rawAction)
        val isAppWidgetUpdate = rawAction == AppWidgetManager.ACTION_APPWIDGET_UPDATE

        if (action == null && !isAppWidgetUpdate) return

        scope.launch {
            val manager = GlanceAppWidgetManager(context)
            val glanceIds = manager.getGlanceIds(SoundcoreWidget::class.java)
            if (glanceIds.isEmpty()) return@launch

            for (glanceId in glanceIds) {
                updateAppWidgetState(context, glanceId) { prefs ->
                    if (isAppWidgetUpdate) {
                        val backendState = SoundcoreBackend.state.value
                        if (backendState.isConnected) {
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
                    } else {
                        when (action) {
                            HuaweiPodsAction.ACTION_PODS_CONNECTED -> {
                                prefs[SoundcoreWidgetKeys.KEY_IS_CONNECTED] = true
                                val deviceName = intent.getStringExtra("device_name")
                                    ?: SoundcoreBackend.state.value.deviceName
                                    ?: "Soundcore Liberty 4 NC"
                                prefs[SoundcoreWidgetKeys.KEY_DEVICE_NAME] = deviceName
                            }
                            HuaweiPodsAction.ACTION_PODS_DISCONNECTED -> {
                                prefs[SoundcoreWidgetKeys.KEY_IS_CONNECTED] = false
                            }
                            HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                                val battery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    intent.getParcelableExtra("status", BatteryParams::class.java)
                                } else {
                                    @Suppress("DEPRECATION")
                                    intent.getParcelableExtra("status")
                                } ?: SoundcoreBackend.state.value.battery
                                if (battery != null) {
                                    prefs[SoundcoreWidgetKeys.KEY_IS_CONNECTED] = true
                                    battery.left?.let {
                                        prefs[SoundcoreWidgetKeys.KEY_LEFT_BATTERY] = it.battery
                                        prefs[SoundcoreWidgetKeys.KEY_LEFT_CHARGING] = it.isCharging
                                    }
                                    battery.right?.let {
                                        prefs[SoundcoreWidgetKeys.KEY_RIGHT_BATTERY] = it.battery
                                        prefs[SoundcoreWidgetKeys.KEY_RIGHT_CHARGING] = it.isCharging
                                    }
                                    battery.case?.let {
                                        prefs[SoundcoreWidgetKeys.KEY_CASE_BATTERY] = it.battery
                                        prefs[SoundcoreWidgetKeys.KEY_CASE_CHARGING] = it.isCharging
                                    }
                                }
                            }
                            HuaweiPodsAction.ACTION_PODS_ANC_CHANGED -> {
                                val status = intent.getIntExtra("status", NoiseControlMode.UNKNOWN.broadcastStatus)
                                prefs[SoundcoreWidgetKeys.KEY_ANC_MODE] = status
                                prefs[SoundcoreWidgetKeys.KEY_IS_CONNECTED] = true
                            }
                            else -> {}
                        }
                    }
                }
                glanceAppWidget.update(context, glanceId)
            }
        }
    }
}

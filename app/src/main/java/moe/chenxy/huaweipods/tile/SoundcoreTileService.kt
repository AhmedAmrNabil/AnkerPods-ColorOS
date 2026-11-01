package moe.chenxy.huaweipods.tile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.backend.soundcore.SoundcoreBackend
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.addHuaweiPodsAction

@RequiresApi(Build.VERSION_CODES.N)
class SoundcoreTileService : TileService() {

    private var isConnected = false
    private var currentMode = NoiseControlMode.UNKNOWN
    private var batterySummary: String? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val receivedIntent = intent ?: return
            val action = HuaweiPodsAction.canonical(receivedIntent.action)
            when (action) {
                HuaweiPodsAction.ACTION_PODS_CONNECTED -> {
                    isConnected = true
                    updateTileState()
                }
                HuaweiPodsAction.ACTION_PODS_DISCONNECTED -> {
                    isConnected = false
                    batterySummary = null
                    currentMode = NoiseControlMode.UNKNOWN
                    updateTileState()
                }
                HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                    val battery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        receivedIntent.getParcelableExtra("status", BatteryParams::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        receivedIntent.getParcelableExtra("status")
                    }
                    if (battery != null) {
                        isConnected = true
                        val l = battery.left?.battery
                        val r = battery.right?.battery
                        batterySummary = if (l != null && r != null) "L:$l% R:$r%" else null
                    }
                    updateTileState()
                }
                HuaweiPodsAction.ACTION_PODS_ANC_CHANGED -> {
                    isConnected = true
                    val status = receivedIntent.getIntExtra("status", NoiseControlMode.UNKNOWN.broadcastStatus)
                    currentMode = NoiseControlMode.fromBroadcastStatus(status)
                    updateTileState()
                }
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        // Check current backend state
        val backendState = SoundcoreBackend.state.value
        if (backendState.isConnected) {
            isConnected = true
            currentMode = backendState.ancMode
            val b = backendState.battery
            val l = b?.left?.battery
            val r = b?.right?.battery
            batterySummary = if (l != null && r != null) "L:$l% R:$r%" else null
        }

        val filter = IntentFilter().apply {
            addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_CONNECTED)
            addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_DISCONNECTED)
            addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_ANC_CHANGED)
        }
        runCatching {
            registerReceiver(statusReceiver, filter, RECEIVER_EXPORTED)
        }

        // Query current state
        sendBroadcast(Intent(HuaweiPodsAction.ACTION_REFRESH_STATUS).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })

        updateTileState()
    }

    override fun onStopListening() {
        super.onStopListening()
        runCatching {
            unregisterReceiver(statusReceiver)
        }
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return

        if (!isConnected) {
            // Attempt refresh or show disconnected
            sendBroadcast(Intent(HuaweiPodsAction.ACTION_REFRESH_STATUS).apply {
                setPackage("com.android.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            tile.state = Tile.STATE_INACTIVE
            tile.subtitle = getString(R.string.not_connected)
            tile.updateTile()
            return
        }

        // Cycle mode
        val nextMode = when (currentMode) {
            NoiseControlMode.OFF -> NoiseControlMode.NOISE_CANCELLATION
            NoiseControlMode.NOISE_CANCELLATION -> NoiseControlMode.TRANSPARENCY
            NoiseControlMode.TRANSPARENCY -> NoiseControlMode.OFF
            else -> NoiseControlMode.NOISE_CANCELLATION
        }
        currentMode = nextMode

        // Broadcast to backend
        sendBroadcast(Intent(HuaweiPodsAction.ACTION_ANC_SELECT).apply {
            setPackage("com.android.bluetooth")
            putExtra("status", nextMode.broadcastStatus)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
        sendBroadcast(Intent(HuaweiPodsAction.ACTION_CYCLE_ANC).apply {
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })

        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return

        if (isConnected) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.noise_control_title)
            val modeName = when (currentMode) {
                NoiseControlMode.NOISE_CANCELLATION -> getString(R.string.noise_cancellation_title)
                NoiseControlMode.TRANSPARENCY -> getString(R.string.transparency_mode)
                NoiseControlMode.OFF -> getString(R.string.off)
                NoiseControlMode.UNKNOWN -> getString(R.string.noise_control_title)
            }
            tile.subtitle = if (batterySummary != null) "$modeName · $batterySummary" else modeName
            tile.icon = Icon.createWithResource(this, android.R.drawable.ic_lock_silent_mode)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Soundcore"
            tile.subtitle = getString(R.string.not_connected)
            tile.icon = Icon.createWithResource(this, android.R.drawable.stat_sys_data_bluetooth)
        }
        tile.updateTile()
    }
}

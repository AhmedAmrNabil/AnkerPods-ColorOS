package moe.chenxy.huaweipods.backend.soundcore

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.chenxy.huaweipods.BuildConfig
import moe.chenxy.huaweipods.backend.EarbudBackend
import moe.chenxy.huaweipods.backend.EarbudState
import moe.chenxy.huaweipods.broadcast.sendHeadsetAppBroadcast
import moe.chenxy.huaweipods.hook.ColorOsSystemBatteryBridge
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.pods.encodeHuaweiDeviceRouteForBroadcast
import moe.chenxy.huaweipods.utils.miuiStrongToast.MiuiStrongToastUtil
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.PodParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.addHuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.sendIdentitySharingBroadcast
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

object SoundcoreBackend : EarbudBackend {
    private const val TAG = "SoundcoreBackend"

    private val _state = MutableStateFlow(EarbudState())
    override val state: StateFlow<EarbudState> = _state.asStateFlow()

    @Volatile
    private var activeDevice: BluetoothDevice? = null
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var socket: BluetoothSocket? = null
    private val socketLock = Any()

    private val sessionGenerationRef = AtomicLong(0)
    private var backendScope: CoroutineScope? = null
    private var connectionJob: Job? = null
    private var receiverRegistered = false

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ctx = context ?: return
            val receivedIntent = intent ?: return
            val action = HuaweiPodsAction.canonical(receivedIntent.action)
            val currentDevice = activeDevice ?: return

            val targetAddress = receivedIntent.getStringExtra("address")
            if (targetAddress != null && !targetAddress.equals(currentDevice.address, ignoreCase = true)) {
                return
            }

            when (action) {
                HuaweiPodsAction.ACTION_ANC_SELECT -> {
                    val status = receivedIntent.getIntExtra("status", NoiseControlMode.UNKNOWN.broadcastStatus)
                    val mode = NoiseControlMode.fromBroadcastStatus(status)
                    val isAdaptive = receivedIntent.getBooleanExtra("is_adaptive", false)
                    val subMode = if (receivedIntent.hasExtra("submode")) {
                        receivedIntent.getIntExtra("submode", -1).takeIf { it >= 0 }
                    } else if (receivedIntent.hasExtra("level")) {
                        receivedIntent.getIntExtra("level", -1).takeIf { it >= 0 }
                    } else if (isAdaptive) {
                        3
                    } else {
                        5
                    }
                    if (mode != NoiseControlMode.UNKNOWN) {
                        setAncMode(mode, subMode, isAdaptive)
                    }
                }
                HuaweiPodsAction.ACTION_HUAWEI_ANC_LEVEL_SET -> {
                    val level = receivedIntent.getIntExtra("level", -1).takeIf { it >= 0 } ?: return
                    setAncMode(NoiseControlMode.NOISE_CANCELLATION, level)
                }
                HuaweiPodsAction.ACTION_HUAWEI_ANC_REFRESH -> {
                    _state.value.ancSubMode?.let { sendAncLevel(it) }
                }
                HuaweiPodsAction.ACTION_CYCLE_ANC -> {
                    cycleAncMode()
                }
                HuaweiPodsAction.ACTION_REFRESH_STATUS,
                HuaweiPodsAction.ACTION_PODS_UI_INIT -> {
                    activeDevice?.takeIf { _state.value.isConnected }?.let { dev ->
                        val name = dev.name ?: dev.alias ?: SoundcoreDeviceIdentifier.DISPLAY_NAME_A3947
                        sendConnectionState("connected")
                        sendConnectedBroadcast(dev, name)
                    }
                    _state.value.battery?.takeIf { it.left?.isConnected == true || it.right?.isConnected == true }?.let { sendBattery(it) }
                    sendAnc(_state.value.ancMode)
                    _state.value.ancSubMode?.let { sendAncLevel(it) }
                    sendRequestState()
                }
            }
        }
    }

    override fun isSupported(device: BluetoothDevice): Boolean {
        return SoundcoreDeviceIdentifier.isSoundcoreDevice(device)
    }

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
        registerControlReceiver(context)
    }

    @SuppressLint("MissingPermission")
    override fun connect(context: Context, device: BluetoothDevice) {
        if (!isSupported(device)) return
        Log.i(TAG, "Connecting to Soundcore device: ${device.address}")

        appContext = context.applicationContext ?: context
        activeDevice = device
        moe.chenxy.huaweipods.hook.MiBluetoothToastHook.markConnected(device.address)

        val deviceName = runCatching { device.name ?: device.alias }.getOrNull()
            ?: SoundcoreDeviceIdentifier.DISPLAY_NAME_A3947

        _state.update {
            it.copy(
                isConnecting = true,
                isConnected = false,
                device = device,
                deviceName = deviceName,
                deviceAddress = device.address,
                battery = null,
                ancMode = NoiseControlMode.OFF,
                ancSubMode = null,
            )
        }

        registerControlReceiver(context)

        connectionJob?.cancel()
        val scope = CoroutineScope(Dispatchers.IO)
        backendScope = scope

        connectionJob = scope.launch {
            runConnectionLoop(context, device)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runConnectionLoop(context: Context, device: BluetoothDevice) {
        val currentGen = sessionGenerationRef.incrementAndGet()
        val uuid = SoundcoreUuid.selectRfcommUuid(device)
        Log.i(TAG, "Attempting RFCOMM connection with UUID=$uuid to device=${device.address}")

        val newSocket = try {
            device.createRfcommSocketToServiceRecord(uuid)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create RFCOMM socket", e)
            disconnect(context, device)
            return
        }

        try {
            withContext(Dispatchers.IO) {
                newSocket.connect()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Socket connection failed", e)
            try { newSocket.close() } catch (_: Exception) {}
            disconnect(context, device)
            return
        }

        synchronized(socketLock) {
            socket = newSocket
        }

        val deviceName = runCatching { device.name ?: device.alias }.getOrNull()
            ?: SoundcoreDeviceIdentifier.DISPLAY_NAME_A3947

        _state.update {
            it.copy(
                isConnected = true,
                isConnecting = false,
                device = device,
                deviceName = deviceName,
                deviceAddress = device.address,
            )
        }

        sendConnectionState("connected")
        sendConnectedBroadcast(device, deviceName)
        sendRequestState()

        // Launch periodic refresh / keep-alive heartbeat
        val heartbeatJob = backendScope?.launch {
            while (isActive && _state.value.isConnected) {
                delay(30_000L)
                sendRequestState()
            }
        }

        // Read stream loop
        val inStream = try {
            newSocket.inputStream
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open inputStream", e)
            heartbeatJob?.cancel()
            disconnect(context, device)
            return
        }

        val buffer = ByteArray(2048)
        var bufferPos = 0

        try {
            while (backendScope?.isActive == true && newSocket.isConnected) {
                val bytesRead = withContext(Dispatchers.IO) {
                    inStream.read(buffer, bufferPos, buffer.size - bufferPos)
                }
                if (bytesRead <= 0) break
                bufferPos += bytesRead

                while (true) {
                    val parsed = SoundcorePacket.parseInbound(buffer, bufferPos) ?: break
                    val (packet, consumed) = parsed
                    handleInboundPacket(context, device, packet)

                    val remainder = bufferPos - consumed
                    if (remainder > 0) {
                        System.arraycopy(buffer, consumed, buffer, 0, remainder)
                    }
                    bufferPos = remainder
                }

                if (bufferPos == buffer.size) {
                    bufferPos = 0
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "Socket read ended or disconnected", e)
        } finally {
            heartbeatJob?.cancel()
            disconnect(context, device)
        }
    }

    private fun handleInboundPacket(context: Context, device: BluetoothDevice, packet: SoundcoreInboundPacket) {
        when (packet.command) {
            SoundcorePacket.CMD_STATE_UPDATE -> {
                val success = SoundcoreStateManager.updateFromStateUpdate(packet.body)
                if (success && SoundcoreStateManager.isReady) {
                    val battery = SoundcoreStateManager.toBatteryParams()
                    val ancMode = SoundcoreStateManager.ancMode
                    val ancSubMode = SoundcoreStateManager.ancSubMode
                    val isAdaptive = SoundcoreStateManager.isAdaptiveAnc
                    Log.i(TAG, "Soundcore state update received: L=${battery.left?.battery}% R=${battery.right?.battery}% Case=${battery.case?.battery}% ANC=$ancMode Sub=$ancSubMode Adaptive=$isAdaptive")
                    _state.update {
                        it.copy(
                            battery = battery,
                            ancMode = ancMode,
                            ancSubMode = ancSubMode ?: it.ancSubMode,
                            isAdaptiveAnc = isAdaptive,
                        )
                    }
                    ColorOsSystemBatteryBridge.publish(context, device, battery, isReady = true)
                    sendBattery(battery)
                    sendAnc(ancMode, isAdaptive)
                    ancSubMode?.let { sendAncLevel(it) }
                    MiuiStrongToastUtil.showPodsNotificationByMiuiBt(context, battery, device)
                }
            }
            SoundcorePacket.CMD_BATTERY_LEVEL -> {
                val success = SoundcoreStateManager.updateFromDualBatteryLevel(packet.body)
                if (success && SoundcoreStateManager.isReady) {
                    val battery = SoundcoreStateManager.toBatteryParams()
                    _state.update { it.copy(battery = battery) }
                    Log.d(TAG, "Soundcore battery level update published: L=${battery.left?.battery}% R=${battery.right?.battery}% Case=${battery.case?.battery}%")
                    ColorOsSystemBatteryBridge.publish(context, device, battery, isReady = true)
                    sendBattery(battery)
                    MiuiStrongToastUtil.showPodsNotificationByMiuiBt(context, battery, device)
                }
            }
            SoundcorePacket.CMD_BATTERY_CHARGING -> {
                val success = SoundcoreStateManager.updateFromDualBatteryCharging(packet.body)
                if (success && SoundcoreStateManager.isReady) {
                    val battery = SoundcoreStateManager.toBatteryParams()
                    _state.update { it.copy(battery = battery) }
                    Log.d(TAG, "Soundcore charging state update published")
                    ColorOsSystemBatteryBridge.publish(context, device, battery, isReady = true)
                    sendBattery(battery)
                    MiuiStrongToastUtil.showPodsNotificationByMiuiBt(context, battery, device)
                }
            }
            SoundcorePacket.CMD_SOUND_MODES_INBOUND -> {
                val soundModes = SoundcorePacket.parseSoundModesWithSubMode(packet.body)
                SoundcoreStateManager.ancMode = soundModes.ancMode
                SoundcoreStateManager.ancSubMode = soundModes.ancSubMode
                SoundcoreStateManager.isAdaptiveAnc = soundModes.isAdaptive
                _state.update {
                    it.copy(
                        ancMode = soundModes.ancMode,
                        ancSubMode = soundModes.ancSubMode ?: it.ancSubMode,
                        isAdaptiveAnc = soundModes.isAdaptive,
                    )
                }
                sendAnc(soundModes.ancMode, soundModes.isAdaptive)
                soundModes.ancSubMode?.let { sendAncLevel(it) }
                if (SoundcoreStateManager.isReady) {
                    MiuiStrongToastUtil.showPodsNotificationByMiuiBt(context, SoundcoreStateManager.toBatteryParams(), device)
                }
            }
        }
    }

    override fun disconnect(context: Context, device: BluetoothDevice) {
        synchronized(socketLock) {
            try {
                socket?.close()
            } catch (_: Exception) {}
            socket = null
        }

        connectionJob?.cancel()
        connectionJob = null

        SoundcoreStateManager.onDisconnect()
        ColorOsSystemBatteryBridge.clear(context, device)
        MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt(context, device)
        sendConnectionState("disconnected")
        sendDisconnectedBroadcast(device)

        _state.update { EarbudState() }
        activeDevice = null
    }

    override fun setAncMode(mode: NoiseControlMode) {
        setAncMode(mode, null, false)
    }

    override fun setAncMode(mode: NoiseControlMode, subMode: Int?) {
        setAncMode(mode, subMode, false)
    }

    override fun setAncMode(mode: NoiseControlMode, subMode: Int?, isAdaptive: Boolean) {
        val targetSubMode = subMode ?: if (mode == NoiseControlMode.NOISE_CANCELLATION) {
            if (isAdaptive) 3 else 5
        } else null
        val packetBytes = SoundcorePacket.buildSetSoundModesPacket(mode, targetSubMode, isAdaptive)
        sendRawPacket(packetBytes)

        SoundcoreStateManager.ancMode = mode
        SoundcoreStateManager.ancSubMode = targetSubMode
        SoundcoreStateManager.isAdaptiveAnc = isAdaptive
        _state.update {
            it.copy(
                ancMode = mode,
                ancSubMode = targetSubMode ?: it.ancSubMode,
                isAdaptiveAnc = isAdaptive,
            )
        }
        sendAnc(mode, isAdaptive)
        if (targetSubMode != null) {
            sendAncLevel(targetSubMode)
        }

        val currentDevice = activeDevice
        val currentContext = appContext
        if (currentDevice != null && currentContext != null && SoundcoreStateManager.isReady) {
            val effectiveBattery = SoundcoreStateManager.toBatteryParams()
            MiuiStrongToastUtil.showPodsNotificationByMiuiBt(currentContext, effectiveBattery, currentDevice)
        }
    }

    override fun cycleAncMode() {
        val current = _state.value.ancMode
        val next = when (current) {
            NoiseControlMode.OFF -> NoiseControlMode.NOISE_CANCELLATION
            NoiseControlMode.NOISE_CANCELLATION -> NoiseControlMode.TRANSPARENCY
            NoiseControlMode.TRANSPARENCY -> NoiseControlMode.OFF
            else -> NoiseControlMode.NOISE_CANCELLATION
        }
        setAncMode(next)
    }

    fun sendRequestState() {
        val packetBytes = SoundcorePacket.buildRequestStatePacket()
        sendRawPacket(packetBytes)
    }

    private fun sendRawPacket(bytes: ByteArray): Boolean {
        synchronized(socketLock) {
            val currentSocket = socket ?: return false
            return try {
                currentSocket.outputStream.write(bytes)
                currentSocket.outputStream.flush()
                true
            } catch (e: IOException) {
                Log.w(TAG, "Failed to write raw packet to socket", e)
                false
            }
        }
    }

    private fun registerControlReceiver(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addHuaweiPodsAction(HuaweiPodsAction.ACTION_ANC_SELECT)
            addHuaweiPodsAction(HuaweiPodsAction.ACTION_CYCLE_ANC)
            addHuaweiPodsAction(HuaweiPodsAction.ACTION_REFRESH_STATUS)
            addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_UI_INIT)
            addHuaweiPodsAction(HuaweiPodsAction.ACTION_HUAWEI_ANC_LEVEL_SET)
            addHuaweiPodsAction(HuaweiPodsAction.ACTION_HUAWEI_ANC_REFRESH)
        }
        runCatching {
            context.registerReceiver(controlReceiver, filter, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        }.onFailure {
            Log.w(TAG, "Failed to register control receiver", it)
        }
    }

    private fun sendConnectionState(state: String) {
        val currentDevice = activeDevice ?: return
        val deviceName = currentDevice.name ?: currentDevice.alias ?: SoundcoreDeviceIdentifier.DISPLAY_NAME_A3947
        val ctx = appContext ?: return
        sendAppBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED) {
            putExtra("address", currentDevice.address)
            putExtra("device_name", deviceName)
            putExtra("state", state)
        }
    }

    private fun sendConnectedBroadcast(device: BluetoothDevice, deviceName: String) {
        val ctx = appContext ?: return
        val routeStr = encodeHuaweiDeviceRouteForBroadcast(HuaweiDeviceRoute.SOUNDCORE_LIBERTY_4_NC)
        sendAppBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_CONNECTED) {
            putExtra("address", device.address)
            putExtra("device_name", deviceName)
            routeStr?.let { putExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE, it) }
        }
        sendExternalBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_CONNECTED) {
            putExtra("device_name", deviceName)
            routeStr?.let { putExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE, it) }
        }
        sendWidgetBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_CONNECTED) {
            putExtra("device_name", deviceName)
            routeStr?.let { putExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE, it) }
        }
    }

    private fun sendDisconnectedBroadcast(device: BluetoothDevice) {
        val ctx = appContext ?: return
        sendAppBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_DISCONNECTED) {
            putExtra("address", device.address)
        }
        sendExternalBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_DISCONNECTED) {
            putExtra("address", device.address)
        }
        sendWidgetBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_DISCONNECTED) {
            putExtra("address", device.address)
        }
    }

    private fun sendBattery(battery: BatteryParams) {
        val ctx = appContext ?: return
        val hasLeft = battery.left?.isConnected == true && (battery.left?.battery ?: 0) > 0
        val hasRight = battery.right?.isConnected == true && (battery.right?.battery ?: 0) > 0
        if (!hasLeft && !hasRight) {
            Log.d(TAG, "Skipping sendBattery: no valid non-zero earbud battery")
            return
        }
        Log.i(TAG, "sendBattery: L=${battery.left?.battery}%(chg=${battery.left?.isCharging}) R=${battery.right?.battery}%(chg=${battery.right?.isCharging}) Case=${battery.case?.battery}%(chg=${battery.case?.isCharging}) isReady=${SoundcoreStateManager.isReady}")
        sendAppBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("status", battery)
            putExtra("is_ready", SoundcoreStateManager.isReady)
            putBatteryExtras(battery)
        }
        sendExternalBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("status", battery)
            putExtra("is_ready", SoundcoreStateManager.isReady)
            putBatteryExtras(battery)
        }
        sendWidgetBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("status", battery)
            putExtra("is_ready", SoundcoreStateManager.isReady)
            putBatteryExtras(battery)
        }
    }

    private fun sendAnc(mode: NoiseControlMode, isAdaptiveOverride: Boolean? = null) {
        val ctx = appContext ?: return
        val subMode = _state.value.ancSubMode
        val isAdaptive = isAdaptiveOverride ?: _state.value.isAdaptiveAnc
        activeDevice?.let { ColorOsSystemBatteryBridge.setLatestAnc(it.address, mode, isAdaptive) }
        sendAppBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_ANC_CHANGED) {
            putExtra("status", mode.broadcastStatus)
            putExtra("is_adaptive", isAdaptive)
            if (subMode != null) putExtra("submode", subMode)
        }
        sendExternalBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_ANC_CHANGED) {
            putExtra("status", mode.broadcastStatus)
            putExtra("is_adaptive", isAdaptive)
            if (subMode != null) putExtra("submode", subMode)
        }
        sendWidgetBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_ANC_CHANGED) {
            putExtra("status", mode.broadcastStatus)
            putExtra("is_adaptive", isAdaptive)
            if (subMode != null) putExtra("submode", subMode)
        }
    }

    private fun sendAncLevel(level: Int) {
        val ctx = appContext ?: return
        sendAppBroadcast(ctx, HuaweiPodsAction.ACTION_HUAWEI_ANC_LEVEL_CHANGED) {
            putExtra("level", level)
        }
        sendExternalBroadcast(ctx, HuaweiPodsAction.ACTION_HUAWEI_ANC_LEVEL_CHANGED) {
            putExtra("level", level)
        }
    }

    private fun sendWidgetBroadcast(ctx: Context, action: String, fill: Intent.() -> Unit = {}) {
        val currentDevice = activeDevice
        runCatching {
            val intent = Intent(action).apply {
                component = ComponentName(
                    BuildConfig.APPLICATION_ID,
                    "moe.chenxy.huaweipods.widget.SoundcoreWidgetReceiver"
                )
                putExtra("vendor", "soundcore")
                currentDevice?.let {
                    putExtra("address", it.address)
                    putExtra("device_name", it.name ?: it.alias ?: SoundcoreDeviceIdentifier.DISPLAY_NAME_A3947)
                }
                fill()
            }
            ctx.sendBroadcast(intent)
        }
    }

    private fun Intent.putBatteryExtras(status: BatteryParams) {
        putExtra("left_battery", status.left?.battery ?: 0)
        putExtra("left_charging", status.left?.isCharging ?: false)
        putExtra("left_connected", status.left?.isConnected ?: false)
        putExtra("right_battery", status.right?.battery ?: 0)
        putExtra("right_charging", status.right?.isCharging ?: false)
        putExtra("right_connected", status.right?.isConnected ?: false)
        putExtra("case_battery", status.case?.battery ?: 0)
        putExtra("case_charging", status.case?.isCharging ?: false)
        putExtra("case_connected", status.case?.isConnected ?: false)
    }

    private fun sendAppBroadcast(ctx: Context, action: String, fill: Intent.() -> Unit = {}) {
        val currentDevice = activeDevice
        val routeStr = encodeHuaweiDeviceRouteForBroadcast(HuaweiDeviceRoute.SOUNDCORE_LIBERTY_4_NC)
        Intent(action).apply {
            putExtra("vendor", "soundcore")
            putExtra(HuaweiPodsAction.EXTRA_EVENT_ELAPSED_REALTIME, SystemClock.elapsedRealtime())
            routeStr?.let { putExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE, it) }
            currentDevice?.let {
                putExtra("address", it.address)
                putExtra("device_name", it.name ?: it.alias ?: SoundcoreDeviceIdentifier.DISPLAY_NAME_A3947)
            }
            fill()
            setPackage(BuildConfig.APPLICATION_ID)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendHeadsetAppBroadcast(this)
        }
    }

    private fun sendExternalBroadcast(ctx: Context, action: String, fill: Intent.() -> Unit = {}) {
        val currentDevice = activeDevice
        val routeStr = encodeHuaweiDeviceRouteForBroadcast(HuaweiDeviceRoute.SOUNDCORE_LIBERTY_4_NC)
        listOf("com.heytap.mydevices", "com.oplus.wirelesssettings", "com.android.bluetooth").forEach { targetPackage ->
            Intent(action).apply {
                putExtra("vendor", "soundcore")
                putExtra(HuaweiPodsAction.EXTRA_EVENT_ELAPSED_REALTIME, SystemClock.elapsedRealtime())
                routeStr?.let { putExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE, it) }
                currentDevice?.let {
                    putExtra("address", it.address)
                    putExtra("device_name", it.name ?: it.alias ?: SoundcoreDeviceIdentifier.DISPLAY_NAME_A3947)
                }
                fill()
                setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                runCatching { ctx.sendIdentitySharingBroadcast(this) }
            }
        }
    }

    override fun audioSessionAddress(): String? = activeDevice?.address

    override fun audioSessionGeneration(): Long = sessionGenerationRef.get()

    override fun saveHotReloadState(outState: Bundle) {}
    override fun restoreHotReloadState(savedState: Bundle) {}
    override fun hotReloadSessionAddress(savedState: Bundle): String? = null
    override fun closeForHotReload() {
        val dev = activeDevice
        val ctx = appContext
        if (dev != null && ctx != null) {
            disconnect(ctx, dev)
        }
    }
}

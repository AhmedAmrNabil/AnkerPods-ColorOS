package moe.chenxy.huaweipods.backend.soundcore

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
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
                    val subMode = if (receivedIntent.hasExtra("submode")) {
                        receivedIntent.getIntExtra("submode", -1).takeIf { it >= 0 }
                    } else if (receivedIntent.hasExtra("level")) {
                        receivedIntent.getIntExtra("level", -1).takeIf { it >= 0 }
                    } else null
                    if (mode != NoiseControlMode.UNKNOWN) {
                        setAncMode(mode, subMode)
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
                    _state.value.battery?.let { sendBattery(it) }
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
                val parsed = SoundcorePacket.parseA3947StateUpdate(packet.body, _state.value.battery)
                if (parsed != null) {
                    val (battery, ancMode, ancSubMode) = parsed
                    _state.update { it.copy(battery = battery, ancMode = ancMode, ancSubMode = ancSubMode ?: it.ancSubMode) }
                    ColorOsSystemBatteryBridge.publish(context, device, battery)
                    sendBattery(battery)
                    sendAnc(ancMode)
                    ancSubMode?.let { sendAncLevel(it) }
                    MiuiStrongToastUtil.showPodsNotificationByMiuiBt(context, battery, device)
                }
            }
            SoundcorePacket.CMD_BATTERY_LEVEL -> {
                val current = _state.value.battery ?: BatteryParams()
                val updated = SoundcorePacket.parseDualBatteryLevel(packet.body, current)
                _state.update { it.copy(battery = updated) }
                ColorOsSystemBatteryBridge.publish(context, device, updated)
                sendBattery(updated)
                MiuiStrongToastUtil.showPodsNotificationByMiuiBt(context, updated, device)
            }
            SoundcorePacket.CMD_BATTERY_CHARGING -> {
                val current = _state.value.battery ?: BatteryParams()
                val updated = SoundcorePacket.parseDualBatteryCharging(packet.body, current)
                _state.update { it.copy(battery = updated) }
                ColorOsSystemBatteryBridge.publish(context, device, updated)
                sendBattery(updated)
                MiuiStrongToastUtil.showPodsNotificationByMiuiBt(context, updated, device)
            }
            SoundcorePacket.CMD_SOUND_MODES_INBOUND -> {
                val soundModes = SoundcorePacket.parseSoundModesWithSubMode(packet.body)
                _state.update { it.copy(ancMode = soundModes.ancMode, ancSubMode = soundModes.ancSubMode ?: it.ancSubMode) }
                sendAnc(soundModes.ancMode)
                soundModes.ancSubMode?.let { sendAncLevel(it) }
                _state.value.battery?.let {
                    MiuiStrongToastUtil.showPodsNotificationByMiuiBt(context, it, device)
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

        ColorOsSystemBatteryBridge.clear(context, device)
        MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt(context, device)
        sendConnectionState("disconnected")
        sendDisconnectedBroadcast(device)

        _state.update { EarbudState() }
        activeDevice = null
    }

    override fun setAncMode(mode: NoiseControlMode) {
        setAncMode(mode, null)
    }

    override fun setAncMode(mode: NoiseControlMode, subMode: Int?) {
        val targetSubMode = subMode ?: if (mode == NoiseControlMode.NOISE_CANCELLATION) {
            _state.value.ancSubMode ?: 0
        } else null
        val packetBytes = SoundcorePacket.buildSetSoundModesPacket(mode, targetSubMode)
        sendRawPacket(packetBytes)

        _state.update { it.copy(ancMode = mode, ancSubMode = targetSubMode ?: it.ancSubMode) }
        sendAnc(mode)
        if (targetSubMode != null) {
            sendAncLevel(targetSubMode)
        }

        val currentDevice = activeDevice
        val currentContext = appContext
        val battery = _state.value.battery
        if (currentDevice != null && currentContext != null && battery != null) {
            MiuiStrongToastUtil.showPodsNotificationByMiuiBt(currentContext, battery, currentDevice)
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
    }

    private fun sendDisconnectedBroadcast(device: BluetoothDevice) {
        val ctx = appContext ?: return
        sendAppBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_DISCONNECTED) {
            putExtra("address", device.address)
        }
        sendExternalBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_DISCONNECTED) {
            putExtra("address", device.address)
        }
    }

    private fun sendBattery(battery: BatteryParams) {
        val ctx = appContext ?: return
        sendAppBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("status", battery)
            putBatteryExtras(battery)
        }
        sendExternalBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("status", battery)
            putBatteryExtras(battery)
        }
    }

    private fun sendAnc(mode: NoiseControlMode) {
        val ctx = appContext ?: return
        sendAppBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_ANC_CHANGED) {
            putExtra("status", mode.broadcastStatus)
        }
        sendExternalBroadcast(ctx, HuaweiPodsAction.ACTION_PODS_ANC_CHANGED) {
            putExtra("status", mode.broadcastStatus)
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
        listOf("com.heytap.mydevices", "com.oplus.wirelesssettings").forEach { targetPackage ->
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

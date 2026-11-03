package moe.chenxy.huaweipods.coloros

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.provider.Settings
import androidx.core.app.NotificationCompat
import moe.chenxy.huaweipods.MainActivity
import moe.chenxy.huaweipods.PopupActivity
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.config.ConfigManager
import moe.chenxy.huaweipods.config.LowLatencyPrefs
import moe.chenxy.huaweipods.config.PodImageResource
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.pods.decodeHuaweiDeviceRouteFromBroadcast
import moe.chenxy.huaweipods.pods.displayName
import moe.chenxy.huaweipods.pods.isKnown
import moe.chenxy.huaweipods.pods.isSupported
import moe.chenxy.huaweipods.pods.supportsLowLatencyControl
import moe.chenxy.huaweipods.broadcast.HuaweiPodsBroadcastTrustPolicy
import moe.chenxy.huaweipods.platform.RomFamily
import moe.chenxy.huaweipods.platform.RomIntegrationPolicy
import moe.chenxy.huaweipods.utils.PodImageLoader
import moe.chenxy.huaweipods.utils.PodImageLoader.asNotificationIcon
import moe.chenxy.huaweipods.utils.resolveBluetoothDisplayName
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction

internal fun colorOsLiveAlertChipText(
    left: Int?,
    right: Int?,
    case: Int?,
): String? = listOfNotNull(left, right).minOrNull()?.let { "$it%" }
    ?: case?.let { "$it%" }

internal fun shouldShowColorOsLiveAlert(
    isColorOs: Boolean,
    hasConnectedBattery: Boolean,
): Boolean = isColorOs && hasConnectedBattery

internal fun shouldAcceptColorOsLiveAlertBatteryUpdate(
    lastDisconnectElapsed: Long,
    eventElapsed: Long,
): Boolean = lastDisconnectElapsed <= 0L || eventElapsed > lastDisconnectElapsed

/** 记录断开时间：早于该时间的电量广播不会再把流体云拉起来。 */
internal fun markColorOsLiveAlertDisconnected(context: Context, elapsedRealtime: Long) {
    context.getSharedPreferences("coloros_live_alert_state", Context.MODE_PRIVATE).edit()
        .putLong("last_disconnect_elapsed", elapsedRealtime)
        .putLong("last_event_elapsed", elapsedRealtime)
        .putInt("event_boot", runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1))
        .remove("current_address")
        .apply()
}

internal fun NotificationCompat.Builder.requestColorOsLiveAlert(chipText: String?) = apply {
    // ColorOS 16 also supports promotion on API 36.0. AndroidX writes
    // the compatible extra without calling the platform's 36.1 setter.
    if (Build.VERSION.SDK_INT >= 36) {
        setRequestPromotedOngoing(true)
        chipText?.let(::setShortCriticalText)
    }
}

/** Publishes the Android 16 promoted ongoing notification rendered as ColorOS Fluid Cloud. */
class ColorOsLiveAlertReceiver : BroadcastReceiver() {
    internal var fromBoundBridge = false

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in TRUSTED_BLUETOOTH_ACTIONS && intent.action !in setOf(
                BluetoothAdapter.ACTION_STATE_CHANGED, BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)) return
        if (
            intent.action in TRUSTED_BLUETOOTH_ACTIONS &&
            !HuaweiPodsBroadcastTrustPolicy.isTrustedBluetoothStateSender(sentFromPackage)
        ) {
            Log.w(TAG, "Rejected headset status broadcast from an untrusted sender: $sentFromPackage")
            return
        }
        handleTrustedEvent(context, intent)
    }

    /** Only called after the receiver or Binder has authenticated the real Bluetooth sender. */
    internal fun handleTrustedEvent(context: Context, intent: Intent, onComplete: ((Boolean) -> Unit)? = null) {
        var completionPending = false
        var succeeded = true
        try {
        val isColorOs = RomIntegrationPolicy.detect(Build.MANUFACTURER, Build.BRAND) ==
            RomFamily.COLOR_OS
        val manager = context.getSystemService(NotificationManager::class.java)
        val statePrefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (statePrefs.getLong(KEY_LAST_DISCONNECT_ELAPSED, 0L) == Long.MAX_VALUE) {
            // Migrate the old sentinel: it could reject every later battery event when
            // ColorOS omitted the matching connected broadcast.
            statePrefs.edit().putLong(KEY_LAST_DISCONNECT_ELAPSED, 0L).apply()
        }
        val eventElapsed = intent.getLongExtra(
            HuaweiPodsAction.EXTRA_EVENT_ELAPSED_REALTIME,
            0L,
        )
        when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (ColorOsLiveAlertConnectionPolicy.isBluetoothTurningOffOrOff(state)) {
                    // 广播没有身份信息，只能核对真实适配器状态：声称关机/正在关但适配器其实还开着 = 伪造。
                    val actualState = runCatching {
                        context.getSystemService(BluetoothManager::class.java)?.adapter?.state
                    }.getOrNull()
                    if (!ColorOsLiveAlertConnectionPolicy.shouldCancelForAdapterState(state, actualState)) {
                        Log.w(
                            TAG,
                            "Ignored a Bluetooth shutdown request claimed=$state actual=$actualState",
                        )
                        return
                    }
                    cancelLiveAlert(context, manager, statePrefs, eventElapsed)
                    Log.d(TAG, "ColorOS Live Alert cancelled on Bluetooth shutdown state=$state")
                }
                return
            }
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(
                    BluetoothProfile.EXTRA_STATE,
                    BluetoothAdapter.ERROR,
                )
                val eventAddress = runCatching {
                    intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java,
                    )?.address
                }.getOrNull()
                val currentAddress = statePrefs.getString(KEY_CURRENT_ADDRESS, null)
                if (ColorOsLiveAlertConnectionPolicy.shouldCheckProfileDisconnect(
                        currentAddress, eventAddress, state,
                    )
                ) completionPending = confirmDisconnected(context, manager, statePrefs, eventAddress, eventElapsed, onComplete)
                return
            }
        }
        val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()
        val previousBoot = statePrefs.getInt("event_boot", -1).takeIf { it >= 0 }
        if (boot != null && previousBoot != null && boot != previousBoot) {
            statePrefs.edit().putLong(KEY_LAST_DISCONNECT_ELAPSED, 0L).remove(KEY_CURRENT_ADDRESS).apply()
        }
        if (!acceptColorOsLiveAlertEvent(previousBoot, boot, statePrefs.getLong("last_event_elapsed", 0L), eventElapsed)) return
        if (eventElapsed > 0) statePrefs.edit().putLong("last_event_elapsed", eventElapsed)
            .putInt("event_boot", boot ?: -1).apply()
        when (HuaweiPodsAction.canonical(intent.action)) {
            HuaweiPodsAction.ACTION_PODS_CONNECTED -> {
                statePrefs.edit()
                    .putLong(KEY_LAST_DISCONNECT_ELAPSED, 0L)
                    .putString(KEY_CURRENT_ADDRESS, intent.getStringExtra("address"))
                    .apply()
                return
            }

            HuaweiPodsAction.ACTION_PODS_DISCONNECTED -> {
                // The module also sends this when only A2DP ends. Verify HFP before clearing.
                completionPending = confirmDisconnected(context, manager, statePrefs, intent.getStringExtra("address"), eventElapsed, onComplete)
                return
            }

            HuaweiPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED -> {
                if (intent.getStringExtra("state") == "disconnected") {
                    completionPending = confirmDisconnected(context, manager, statePrefs, intent.getStringExtra("address"), eventElapsed, onComplete)
                } else if (intent.getStringExtra("state") == "connected") {
                    statePrefs.edit()
                        .putLong(KEY_LAST_DISCONNECT_ELAPSED, 0L)
                        .putString(KEY_CURRENT_ADDRESS, intent.getStringExtra("address"))
                        .apply()
                }
                return
            }

            HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED -> Unit

            HuaweiPodsAction.ACTION_PODS_ANC_CHANGED,
            HuaweiPodsAction.ACTION_HUAWEI_LOW_LATENCY_CHANGED -> {
                // 降噪/低时延状态影响卡片上按钮与状态文字：只重建已存在的卡片，不新建、不改生命周期。
                refreshLiveAlertCard(context, intent)
                return
            }

            else -> return
        }
        if (!isColorOs) return
        val lastDisconnectElapsed = statePrefs.getLong(KEY_LAST_DISCONNECT_ELAPSED, 0L)
        if (!shouldAcceptColorOsLiveAlertBatteryUpdate(lastDisconnectElapsed, eventElapsed)) {
            context.stopService(Intent(context, ColorOsLiveAlertService::class.java))
            manager.cancel(NOTIFICATION_ID)
            Log.d(TAG, "Ignored stale ColorOS Live Alert battery update")
            return
        }

        ConfigManager.refreshFromPrefs(
            context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE),
        )
        if (!ConfigManager.persistentNotificationEnabled() ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            context.stopService(Intent(context, ColorOsLiveAlertService::class.java))
            manager.cancel(NOTIFICATION_ID)
            return
        }

        val battery = intent.batteryParams()
        intent.getStringExtra("address")?.takeIf(String::isNotBlank)?.let { address ->
            statePrefs.edit().putString(KEY_CURRENT_ADDRESS, address).apply()
        }
        val left = battery.left?.takeIf { it.isConnected }?.battery
        val right = battery.right?.takeIf { it.isConnected }?.battery
        val case = battery.case?.takeIf { it.isConnected }?.battery
        if (!shouldShowColorOsLiveAlert(isColorOs, listOf(left, right, case).any { it != null })) {
            context.stopService(Intent(context, ColorOsLiveAlertService::class.java))
            manager.cancel(NOTIFICATION_ID)
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.coloros_live_alert_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)

        val address = intent.getStringExtra("address").orEmpty()
        // 蓝牙进程已确认机型：用于选择机型内置图，避免 FreeClip 2 等型号显示成一代 FreeClip。
        val route = decodeHuaweiDeviceRouteFromBroadcast(
            intent.getStringExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE),
        )?.takeIf { it.isSupported }
        val title = resolveBluetoothDisplayName(
            alias = intent.getStringExtra(HuaweiPodsAction.EXTRA_DEVICE_DISPLAY_NAME),
            name = intent.getStringExtra("device_name"),
            fallback = route?.displayName ?: context.getString(R.string.coloros_live_alert_fallback_title),
        )
        val card = LiveAlertCard(
            address = address,
            title = title,
            route = route,
            battery = battery,
            noiseMode = readPersistedNoiseMode(statePrefs, address),
            deviceName = intent.getStringExtra("device_name"),
        )
        // 卡片按钮与状态文字要在后续 ANC / 低时延广播里重建，因此把当前卡片内容存下来。
        persistLiveAlertCard(
            statePrefs,
            card,
            intent.getStringExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE),
        )
        val notification = buildLiveAlertNotification(context, card)
        if (Build.VERSION.SDK_INT >= 36) {
            Log.d(
                TAG,
                "ColorOS Live Alert eligibility " +
                    "promotable=${notification.hasPromotableCharacteristics()} " +
                    "allowed=${manager.canPostPromotedNotifications()}",
            )
        }
        val serviceIntent = Intent(context, ColorOsLiveAlertService::class.java).putExtra(
            ColorOsLiveAlertService.EXTRA_NOTIFICATION,
            notification,
        ).putExtra(ColorOsLiveAlertService.EXTRA_DEVICE_ADDRESS, address)
        runCatching { context.startForegroundService(serviceIntent) }
            .onFailure {
                manager.notify(NOTIFICATION_ID, notification)
                Log.w(TAG, "Foreground keep-alive unavailable; posted notification directly", it)
            }
        Log.d(TAG, "ColorOS Live Alert posted")
        } catch (error: Exception) {
            succeeded = false
            throw error
        } finally {
            if (!completionPending) onComplete?.invoke(succeeded)
        }
    }

    /** 流体云卡片当前展示的内容；ANC / 低时延变化时用它原样重建。 */
    private class LiveAlertCard(
        val address: String,
        val title: String,
        val route: HuaweiDeviceRoute?,
        val battery: BatteryParams,
        val noiseMode: NoiseControlMode?,
        val deviceName: String?,
    )

    /**
     * 流体云卡片（promoted ongoing）把整张卡当成一个点击区，`Notification.Action` 的点击不会派发
     * （实测连点 3 次 action 都只是走 contentIntent），所以卡片点击必须落到能直接改状态的地方：
     * 优先复用模块自己的耳机弹窗（含降噪 / 低时延开关），解析不到设备时退回模块耳机详情页。
     * 弹窗自己会尊重用户设置的 `notificationClickAction`，行为与点通知一致。
     */
    @SuppressLint("MissingPermission")
    private fun liveAlertContentIntent(context: Context, card: LiveAlertCard): Intent {
        val device = card.address.takeIf(String::isNotBlank)?.let { address ->
            runCatching { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) }.getOrNull()
        }
        if (device != null) {
            return Intent(context, PopupActivity::class.java).apply {
                action = HuaweiPodsAction.ACTION_SHOW_PODS_UI
                addCategory(Intent.CATEGORY_DEFAULT)
                putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
        }
        return Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_NAVIGATE_PAGE, MainActivity.PAGE_EARPHONE_DETAIL)
            if (card.address.isNotBlank()) {
                putExtra(MainActivity.EXTRA_DEVICE_ADDRESS, card.address)
            }
            card.deviceName?.takeIf(String::isNotBlank)?.let {
                putExtra(MainActivity.EXTRA_DEVICE_NAME, it)
            }
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    private fun buildLiveAlertNotification(context: Context, card: LiveAlertCard): Notification {
        val prefs = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val left = card.battery.left?.takeIf { it.isConnected }?.battery
        val right = card.battery.right?.takeIf { it.isConnected }?.battery
        val case = card.battery.case?.takeIf { it.isConnected }?.battery
        val contentText = buildList {
            left?.let { add("L $it%") }
            right?.let { add("R $it%") }
            case?.let { add("${context.getString(R.string.pod_case)} $it%") }
        }.joinToString("  ")
        val largeIcon = card.address.takeIf(String::isNotBlank)
            ?.let { PodImageLoader.loadNotificationBoxBitmap(context, prefs, it, verifiedRoute = card.route) }
            ?: BitmapFactory.decodeResource(
                context.resources,
                PodImageLoader.modelFallbackResId(
                    card.route ?: HuaweiDeviceRoute.UNSUPPORTED,
                    PodImageResource.BOX,
                    R.drawable.img_box,
                ),
            )?.asNotificationIcon()
        val requestCode = if (card.address.isNotBlank()) card.address.hashCode() else 0
        val contentIntent = PendingIntent.getActivity(
            context,
            requestCode,
            liveAlertContentIntent(context, card),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(card.title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(colorOsLiveAlertVisibility(ConfigManager.lockscreenNotificationEnabled()))
            .setLargeIcon(largeIcon)
            .apply {
                // 降噪：按机型能力提供「关 → 降噪 → 通透」循环切换，按钮文字标明下一档。
                val nextNoise = colorOsLiveAlertNextNoiseMode(card.route, card.noiseMode)
                if (nextNoise != null && card.address.isNotBlank()) {
                    ColorOsLiveAlertActionReceiver.cycleNoiseModeIntent(
                        context,
                        card.address,
                        card.route,
                        nextNoise,
                    )?.let { addAction(0, colorOsLiveAlertNoiseActionLabel(context, nextNoise), it) }
                }
                // 支持低时延的型号在卡片上提供一键切换；状态取模块记录的最近一次写入结果。
                val route = card.route
                if (route == null || !route.supportsLowLatencyControl || card.address.isBlank()) {
                    card.noiseMode?.takeIf { it.isKnown() }?.let {
                        setSubText(colorOsLiveAlertNoiseLabel(context, it))
                    }
                    return@apply
                }
                val lowLatency = LowLatencyPrefs.desiredOrNull(prefs, card.address, route) ?: false
                val action = ColorOsLiveAlertActionReceiver.toggleLowLatencyIntent(
                    context,
                    card.address,
                    route,
                    enabled = !lowLatency,
                ) ?: return@apply
                addAction(
                    0,
                    context.getString(
                        if (lowLatency) R.string.coloros_live_alert_low_latency_off
                        else R.string.coloros_live_alert_low_latency_on,
                    ),
                    action,
                )
                setSubText(
                    buildList {
                        card.noiseMode?.takeIf { it.isKnown() }?.let {
                            add(colorOsLiveAlertNoiseLabel(context, it))
                        }
                        add(
                            context.getString(
                                if (lowLatency) R.string.coloros_live_alert_low_latency_state_on
                                else R.string.coloros_live_alert_low_latency_state_off,
                            ),
                        )
                    }.joinToString(" · "),
                )
            }
            .requestColorOsLiveAlert(colorOsLiveAlertChipText(left, right, case))
            .build()
    }

    /** 把当前卡片内容写入 prefs：ANC / 低时延广播到来时进程可能已被回收，不能只靠内存。 */
    private fun persistLiveAlertCard(
        statePrefs: android.content.SharedPreferences,
        card: LiveAlertCard,
        routeExtra: String?,
    ) {
        if (card.address.isBlank()) return
        statePrefs.edit().apply {
            putString(KEY_CARD_ADDRESS, card.address.uppercase())
            putString(KEY_CARD_TITLE, card.title)
            putString(KEY_CARD_DEVICE_NAME, card.deviceName.orEmpty())
            putString(KEY_CARD_ROUTE, routeExtra.orEmpty())
            putInt(KEY_CARD_NOISE, card.noiseMode?.broadcastStatus ?: -1)
            listOf("left" to card.battery.left, "right" to card.battery.right, "case" to card.battery.case)
                .forEach { (name, pod) ->
                    putInt("card_${name}_battery", pod?.battery ?: 0)
                    putBoolean("card_${name}_charging", pod?.isCharging ?: false)
                    putBoolean("card_${name}_connected", pod?.isConnected ?: false)
                }
        }.apply()
    }

    private fun readLiveAlertCard(statePrefs: android.content.SharedPreferences): LiveAlertCard? {
        val address = statePrefs.getString(KEY_CARD_ADDRESS, null)?.takeIf(String::isNotBlank)
            ?: return null
        fun pod(name: String) = moe.chenxy.huaweipods.utils.miuiStrongToast.data.PodParams(
            battery = statePrefs.getInt("card_${name}_battery", 0),
            isCharging = statePrefs.getBoolean("card_${name}_charging", false),
            isConnected = statePrefs.getBoolean("card_${name}_connected", false),
        )
        val routeExtra = statePrefs.getString(KEY_CARD_ROUTE, null)
        return LiveAlertCard(
            address = address,
            title = statePrefs.getString(KEY_CARD_TITLE, null) ?: return null,
            route = decodeHuaweiDeviceRouteFromBroadcast(routeExtra)?.takeIf { it.isSupported },
            battery = BatteryParams(left = pod("left"), right = pod("right"), case = pod("case")),
            noiseMode = NoiseControlMode.fromBroadcastStatus(statePrefs.getInt(KEY_CARD_NOISE, -1)),
            deviceName = statePrefs.getString(KEY_CARD_DEVICE_NAME, null)?.takeIf(String::isNotBlank),
        )
    }

    /**
     * 降噪 / 低时延状态变化时刷新已在显示的卡片。
     *
     * 只重建内容，不改变卡片生命周期：卡片是否显示仍由连接与电量事件决定。
     */
    private fun refreshLiveAlertCard(context: Context, intent: Intent) {
        val statePrefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val card = readLiveAlertCard(statePrefs) ?: return
        val eventAddress = intent.getStringExtra("address")?.uppercase()
        if (eventAddress != null && !eventAddress.equals(card.address, ignoreCase = true)) return
        val action = HuaweiPodsAction.canonical(intent.action)
        val updated = when (action) {
            HuaweiPodsAction.ACTION_PODS_ANC_CHANGED -> {
                val mode = NoiseControlMode.fromBroadcastStatus(intent.getIntExtra("status", 0))
                if (!mode.isKnown()) return
                statePrefs.edit().putInt(KEY_CARD_NOISE, mode.broadcastStatus).apply()
                if (mode == card.noiseMode) return
                LiveAlertCard(card.address, card.title, card.route, card.battery, mode, card.deviceName)
            }

            HuaweiPodsAction.ACTION_HUAWEI_LOW_LATENCY_CHANGED -> card
            else -> return
        }
        // 卡片不在前台时不重建：避免把已收起的胶囊重新拉起来。
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.activeNotifications.none { it.id == NOTIFICATION_ID }) return
        val notification = buildLiveAlertNotification(context, updated)
        // 同一 id 的 notify 直接更新前台服务正在显示的那条通知；比再启动一次服务安全，
        // 后台也受前台服务启动限制约束。
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Unable to refresh the visible Live Alert card", it) }
        Log.d(TAG, "ColorOS Live Alert card refreshed action=$action")
    }

    private fun readPersistedNoiseMode(
        statePrefs: android.content.SharedPreferences,
        address: String,
    ): NoiseControlMode? {
        // 降噪状态按地址记忆：换一副耳机不能继承上一副的档位。
        if (address.isBlank()) return null
        if (!statePrefs.getString(KEY_CARD_ADDRESS, null).equals(address, ignoreCase = true)) return null
        return NoiseControlMode.fromBroadcastStatus(statePrefs.getInt(KEY_CARD_NOISE, -1))
            .takeIf { it != NoiseControlMode.UNKNOWN }
    }

    private fun Intent.batteryParams(): BatteryParams {
        return getParcelableExtra("status", BatteryParams::class.java) ?: BatteryParams(
            left = batteryPod("left"),
            right = batteryPod("right"),
            case = batteryPod("case"),
        )
    }

    private fun Intent.batteryPod(prefix: String) =
        moe.chenxy.huaweipods.utils.miuiStrongToast.data.PodParams(
            battery = getIntExtra("${prefix}_battery", 0),
            isCharging = getBooleanExtra("${prefix}_charging", false),
            isConnected = getBooleanExtra("${prefix}_connected", false),
        )

    private fun confirmDisconnected(
        context: Context,
        manager: NotificationManager,
        statePrefs: android.content.SharedPreferences,
        eventAddress: String?,
        eventElapsed: Long,
        onComplete: ((Boolean) -> Unit)? = null,
    ): Boolean {
        if (!ColorOsLiveAlertConnectionPolicy.matchesDevice(
                statePrefs.getString(KEY_CURRENT_ADDRESS, null), eventAddress,
            )
        ) return false
        val address = eventAddress ?: return false
        if (runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == false }
                .getOrDefault(false)) {
            cancelLiveAlert(context, manager, statePrefs, eventElapsed)
            return false
        }
        val pendingResult = if (fromBoundBridge) null else goAsync()
        queryColorOsLiveAlertAudioProfiles(context, address) { a2dpState, headsetState ->
            var succeeded = true
            try {
                // A new headset may have become current while the Profile proxies were binding.
                if (ColorOsLiveAlertConnectionPolicy.shouldCancelAfterProfileCheck(
                        statePrefs.getString(KEY_CURRENT_ADDRESS, null), address, a2dpState, headsetState,
                    )
                ) {
                    cancelLiveAlert(context, manager, statePrefs, eventElapsed)
                    Log.d(TAG, "ColorOS Live Alert cancelled: both audio Profiles disconnected")
                }
            } catch (error: Exception) {
                succeeded = false
                Log.w(TAG, "Live Alert disconnect cleanup failed", error)
            } finally {
                pendingResult?.finish()
                onComplete?.invoke(succeeded)
            }
        }
        return true
    }

    private fun cancelLiveAlert(
        context: Context,
        manager: NotificationManager,
        statePrefs: android.content.SharedPreferences,
        eventElapsed: Long,
    ) {
        val now = SystemClock.elapsedRealtime()
        // 事件时间戳由发送方提供：钳到真实 uptime 以内，且只允许前进。否则伪造一个远未来的
        // 时间戳就能让之后所有真实电量事件被当作过期丢掉。
        val stamp = eventElapsed.takeIf { it in 1..now } ?: now
        val previous = statePrefs.getLong(KEY_LAST_DISCONNECT_ELAPSED, 0L)
        statePrefs.edit()
            .putLong(KEY_LAST_DISCONNECT_ELAPSED, maxOf(previous, stamp))
            .remove(KEY_CURRENT_ADDRESS)
            .remove(KEY_CARD_ADDRESS)
            .remove(KEY_CARD_TITLE)
            .remove(KEY_CARD_DEVICE_NAME)
            .remove(KEY_CARD_ROUTE)
            .remove(KEY_CARD_NOISE)
            .apply()
        context.stopService(Intent(context, ColorOsLiveAlertService::class.java))
        manager.cancel(NOTIFICATION_ID)
        markColorOsLiveAlertDisconnected(context, SystemClock.elapsedRealtime())
        ColorOsLiveAlertBridgeService.reportClosed()
    }

    private companion object {
        const val TAG = "HuaweiPods-LiveAlert"
        const val CHANNEL_ID = "HuaweiPodsLiveAlert"
        const val STATE_PREFS = "coloros_live_alert_state"
        const val KEY_LAST_DISCONNECT_ELAPSED = "last_disconnect_elapsed"
        const val KEY_CURRENT_ADDRESS = "current_address"
        const val KEY_CARD_ADDRESS = "card_address"
        const val KEY_CARD_TITLE = "card_title"
        const val KEY_CARD_DEVICE_NAME = "card_device_name"
        const val KEY_CARD_ROUTE = "card_route"
        const val KEY_CARD_NOISE = "card_noise_mode"
        const val NOTIFICATION_ID = ColorOsLiveAlertService.NOTIFICATION_ID
        const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
        val TRUSTED_BLUETOOTH_ACTIONS = setOf(
            HuaweiPodsAction.ACTION_PODS_CONNECTED,
            HuaweiPodsAction.ACTION_PODS_DISCONNECTED,
            HuaweiPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED,
            HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED,
            HuaweiPodsAction.ACTION_PODS_ANC_CHANGED,
            HuaweiPodsAction.ACTION_HUAWEI_LOW_LATENCY_CHANGED,
        )
    }
}

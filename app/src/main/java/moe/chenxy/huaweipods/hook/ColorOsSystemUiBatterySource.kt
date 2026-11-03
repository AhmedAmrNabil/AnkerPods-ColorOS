package moe.chenxy.huaweipods.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import moe.chenxy.huaweipods.broadcast.HuaweiPodsBroadcastTrustPolicy
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.addHuaweiPodsAction

/** 每代 Hook 只恢复一次快照；读数据不会让已经断开的耳机复活。 */
internal class ColorOsSystemUiBatterySource(
    private val loadSnapshot: () -> ColorOsPodBattery?,
    private val subscribe: ((ColorOsPodBattery) -> Unit, (String?) -> Unit) -> AutoCloseable,
    private val onChanged: () -> Unit,
) : AutoCloseable {
    private var started = false
    private var closed = false
    private var subscription: AutoCloseable? = null
    private val touchedAddresses = mutableSetOf<String>()
    private var disconnectedAll = false

    @Synchronized
    fun start() {
        if (started || closed) return
        started = true
        try {
            subscription = subscribe(::receive, ::disconnect)
            val pod = loadSnapshot()
            if (pod != null && !disconnectedAll &&
                ColorOsPodBatteryStore.key(pod.address) !in touchedAddresses &&
                ColorOsPodBatteryStore.get(pod.address) == null
            ) {
                ColorOsPodBatteryStore.put(pod)
                onChanged()
            }
        } catch (failure: Throwable) {
            started = false
            runCatching { subscription?.close() }
            subscription = null
            throw failure
        }
    }

    @Synchronized
    fun get(address: String?): ColorOsPodBattery? =
        if (started && !closed) ColorOsPodBatteryStore.get(address) else null

    @Synchronized
    private fun receive(pod: ColorOsPodBattery) {
        if (!started || closed || pod.address.isBlank()) return
        touchedAddresses += ColorOsPodBatteryStore.key(pod.address)
        ColorOsPodBatteryStore.put(pod)
        onChanged()
    }

    @Synchronized
    private fun disconnect(address: String?) {
        if (!started || closed) return
        if (address.isNullOrBlank()) {
            disconnectedAll = true
        } else {
            touchedAddresses += ColorOsPodBatteryStore.key(address)
        }
        ColorOsPodBatteryStore.clear(address)
        onChanged()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        started = false
        val previous = subscription
        subscription = null
        touchedAddresses.clear()
        previous?.close()
    }

    companion object {
        fun forContext(context: Context, onChanged: () -> Unit): ColorOsSystemUiBatterySource =
            ColorOsSystemUiBatterySource(
                loadSnapshot = { ColorOsBatterySnapshot.load(context) },
                subscribe = { receive, disconnect ->
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            val trusted = runCatching {
                                context.packageManager.getPackagesForUid(sentFromUid)?.any { sender ->
                                    HuaweiPodsBroadcastTrustPolicy.shouldAcceptMyDevicesStateBroadcast(
                                        intent.action, sender,
                                    )
                                } == true
                            }.getOrDefault(false)
                            if (!trusted) return
                            runCatching {
                                // 同一广播还携带模块 Parcelable，不能交给宿主 ClassLoader 解码。
                                intent.setExtrasClassLoader(ColorOsSystemUiBatterySource::class.java.classLoader)
                                val address = intent.getStringExtra("address")
                                when (HuaweiPodsAction.canonical(intent.action)) {
                                    HuaweiPodsAction.ACTION_PODS_DISCONNECTED -> disconnect(address)
                                    HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                                        if (address.isNullOrBlank()) return@runCatching
                                        receive(
                                            ColorOsPodBattery(
                                                address = address,
                                                left = intent.getIntExtra("left_battery", 0),
                                                leftConnected = intent.getBooleanExtra("left_connected", false),
                                                right = intent.getIntExtra("right_battery", 0),
                                                rightConnected = intent.getBooleanExtra("right_connected", false),
                                                case = intent.getIntExtra("case_battery", 0),
                                                caseConnected = intent.getBooleanExtra("case_connected", false),
                                                name = intent.getStringExtra("device_name"),
                                            ),
                                        )
                                    }
                                }
                            }.onFailure { Log.w("HuaweiPods-SystemUi", "battery broadcast unavailable", it) }
                        }
                    }
                    context.registerReceiver(
                        receiver,
                        IntentFilter().apply {
                            addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED)
                            addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_DISCONNECTED)
                        },
                        Context.RECEIVER_EXPORTED,
                    )
                    AutoCloseable { context.unregisterReceiver(receiver) }
                },
                onChanged = onChanged,
            )
    }
}

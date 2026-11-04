package moe.chenxy.huaweipods.pods

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import moe.chenxy.huaweipods.BuildConfig
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.sendIdentitySharingBroadcast

/**
 * 低时延写入命令。以下支持范围均来自对应机型抓包中的同一 2B6C/01 开关帧，
 * 未抓到 setter 的型号不会出现在能力表中，也不会接收该命令。
 */
object HuaweiLowLatencyController {
    private val DISABLED_PACKET = decodeLowLatencyHex("5A0006002B6C010100B430")
    private val ENABLED_PACKET = decodeLowLatencyHex("5A0006002B6C010101A411")

    /** 页面专用确认事务。没有宿主响应时失败，不回退到 App 进程争抢连接。 */
    internal fun setFreeClip2EnabledVerified(
        context: Context,
        device: BluetoothDevice,
        enabled: Boolean,
        onState: (Boolean?) -> Unit,
        onComplete: (Boolean) -> Unit,
    ) {
        val route = HuaweiDeviceRoute.HUAWEI_FREECLIP2
        val address = runCatching { device.address }.getOrNull()
        if (!usesSharedConnection(context.packageName, route) ||
            address == null || !BluetoothAdapter.checkBluetoothAddress(address)
        ) {
            onState(null)
            onComplete(false)
            return
        }
        HuaweiSharedControlClient.request(
            context, device, route, HuaweiSharedControlPolicy.LOW_LATENCY_SET_VERIFIED,
            enabled = enabled,
        ) { response ->
            val result = response?.takeIf {
                it.size == 2 && (it[0] == 0.toByte() || it[0] == 1.toByte()) &&
                    it[1].toInt() in 0..2
            }
            val actual = when (result?.get(1)?.toInt()) {
                0 -> false
                1 -> true
                else -> null
            }
            onState(actual)
            onComplete(result?.get(0) == 1.toByte() && actual == enabled)
        }
    }

    fun setEnabled(
        context: Context,
        device: BluetoothDevice,
        route: HuaweiDeviceRoute,
        enabled: Boolean,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        val address = runCatching { device.address }.getOrNull()
        if (!route.supportsLowLatencyControl ||
            address == null ||
            !BluetoothAdapter.checkBluetoothAddress(address)
        ) {
            onComplete?.invoke(false)
            return
        }
        if (route == HuaweiDeviceRoute.SOUNDCORE_LIBERTY_4_NC) {
            context.sendIdentitySharingBroadcast(Intent(HuaweiPodsAction.ACTION_HUAWEI_LOW_LATENCY_SET).apply {
                putExtra("address", address)
                putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                putExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE, route.name)
                putExtra(HuaweiPodsAction.EXTRA_HUAWEI_LOW_LATENCY_ENABLED, enabled)
            })
            onComplete?.invoke(true)
            return
        }
        if (usesSharedConnection(context.packageName, route)) {
            HuaweiSharedControlClient.request(
                context, device, route, HuaweiSharedControlPolicy.LOW_LATENCY_SET,
                enabled = enabled,
            ) { response ->
                // 这是蓝牙进程的写出确认，不代表耳机协议提供了状态回读。
                onComplete?.invoke(response?.contentEquals(byteArrayOf(1)) == true)
            }
            return
        }
        sendInHost(context, device, route, enabled, onComplete = onComplete)
    }

    internal fun usesSharedConnection(packageName: String, route: HuaweiDeviceRoute): Boolean =
        packageName == BuildConfig.APPLICATION_ID && route.supportsLowLatencyControl

    internal fun sendInHost(
        context: Context,
        device: BluetoothDevice,
        route: HuaweiDeviceRoute,
        enabled: Boolean,
        shouldWrite: () -> Boolean = { true },
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        HuaweiL2capAncController.sendRawPacket(
            context = context,
            device = device,
            route = route,
            packet = packet(enabled),
            description = "${route.name.lowercase()} low-latency enabled=$enabled",
            // SE 4 ANC / 6i 的音效也在宿主复用连接；其他型号仍按原策略在写后释放，
            // 避免长期占用通道，阻塞尚未迁移到宿主的页面控制项。
            keepSocket = HuaweiSharedControlPolicy.supports(route),
            shouldWrite = shouldWrite,
            onComplete = onComplete,
        )
    }

    internal fun packet(enabled: Boolean): ByteArray =
        (if (enabled) ENABLED_PACKET else DISABLED_PACKET).copyOf()
}

private fun decodeLowLatencyHex(value: String): ByteArray =
    value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

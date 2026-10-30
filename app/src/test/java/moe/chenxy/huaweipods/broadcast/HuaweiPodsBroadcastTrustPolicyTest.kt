package moe.chenxy.huaweipods.broadcast

import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiPodsBroadcastTrustPolicyTest {
    @Test fun sharedControlKeepsRequestAndResultInSeparateTrustDomains() {
        assertEquals(BroadcastActionDomain.APP_CONTROL,
            HuaweiPodsBroadcastTrustPolicy.resolveDomain(HuaweiPodsAction.ACTION_SHARED_CONTROL_REQUEST))
        assertEquals(BroadcastActionDomain.BLUETOOTH_STATE,
            HuaweiPodsBroadcastTrustPolicy.resolveDomain(HuaweiPodsAction.ACTION_SHARED_CONTROL_RESULT))
        assertFalse(HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(HuaweiPodsAction.ACTION_SHARED_CONTROL_REQUEST, "untrusted"))
        assertFalse(HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(HuaweiPodsAction.ACTION_SHARED_CONTROL_REQUEST, HuaweiPodsBroadcastTrustPolicy.PACKAGE_HEYTAP_MYDEVICES))
        assertFalse(HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(HuaweiPodsAction.ACTION_SHARED_CONTROL_REQUEST, HuaweiPodsBroadcastTrustPolicy.PACKAGE_BLUETOOTH))
        assertTrue(HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(HuaweiPodsAction.ACTION_SHARED_CONTROL_REQUEST, HuaweiPodsBroadcastTrustPolicy.PACKAGE_APP))
        assertFalse(HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(HuaweiPodsAction.ACTION_SHARED_CONTROL_RESULT, HuaweiPodsBroadcastTrustPolicy.PACKAGE_APP))
        assertTrue(HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(HuaweiPodsAction.ACTION_SHARED_CONTROL_RESULT, HuaweiPodsBroadcastTrustPolicy.PACKAGE_BLUETOOTH))
    }

    @Test
    fun `trusted App control senders should pass all control requests`() {
        val trustedSenders = listOf(
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_APP,
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_BLUETOOTH,
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_HEYTAP_MYDEVICES,
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_HEYTAP_ACCESSORY,
        )
        trustedSenders.forEach { sender ->
            assertTrue("Sender $sender should be trusted for app control", HuaweiPodsBroadcastTrustPolicy.isTrustedAppControlSender(sender))
        }

        val controlActions = listOf(
            HuaweiPodsAction.ACTION_CONNECT_POD_REQUEST,
            HuaweiPodsAction.ACTION_PODS_UI_INIT,
            HuaweiPodsAction.ACTION_REFRESH_STATUS,
            HuaweiPodsAction.ACTION_DEVICE_ROUTE_PROBE_REQUEST,
            HuaweiPodsAction.ACTION_ANC_SELECT,
            HuaweiPodsAction.ACTION_CYCLE_ANC,
            HuaweiPodsAction.ACTION_HUAWEI_ANC_LEVEL_SET,
            HuaweiPodsAction.ACTION_HUAWEI_ANC_REFRESH,
            HuaweiPodsAction.ACTION_HUAWEI_LOW_LATENCY_SET,
            HuaweiPodsAction.ACTION_HUAWEI_WEAR_DETECTION_SET,
            HuaweiPodsAction.ACTION_HUAWEI_WEAR_DETECTION_REFRESH,
            HuaweiPodsAction.ACTION_HUAWEI_GESTURE_SET,
            HuaweiPodsAction.ACTION_FREECLIP2_AUDIO_SET,
            HuaweiPodsAction.ACTION_HUAWEI_EQUALIZER_PRESET_SET,
        )
        val appSender = HuaweiPodsBroadcastTrustPolicy.PACKAGE_APP
        controlActions.forEach { action ->
            assertTrue(
                "Action $action should accept trusted app sender",
                HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(action, appSender),
            )
        }
    }

    @Test
    fun `untrusted sender or null should be rejected for control requests`() {
        val untrustedSenders = listOf(
            null,
            "",
            "com.malicious.fakeapp",
            "com.fake.settings",
            "com.other.app",
            "com.xiaomi.bluetooth",
        )
        val controlActions = listOf(
            HuaweiPodsAction.ACTION_CONNECT_POD_REQUEST,
            HuaweiPodsAction.ACTION_PODS_UI_INIT,
            HuaweiPodsAction.ACTION_REFRESH_STATUS,
            HuaweiPodsAction.ACTION_DEVICE_ROUTE_PROBE_REQUEST,
            HuaweiPodsAction.ACTION_ANC_SELECT,
            HuaweiPodsAction.ACTION_HUAWEI_ANC_LEVEL_SET,
            HuaweiPodsAction.ACTION_HUAWEI_GESTURE_SET,
        )
        for (sender in untrustedSenders) {
            assertFalse(
                "Sender $sender should not be trusted for app control",
                HuaweiPodsBroadcastTrustPolicy.isTrustedAppControlSender(sender),
            )
            for (action in controlActions) {
                assertFalse(
                    "Action $action should reject untrusted sender $sender",
                    HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(action, sender),
                )
            }
        }
    }

    @Test
    fun `trusted bluetooth sender should pass state and alive broadcasts`() {
        val bluetoothSender = HuaweiPodsBroadcastTrustPolicy.PACKAGE_BLUETOOTH
        assertTrue(HuaweiPodsBroadcastTrustPolicy.isTrustedBluetoothStateSender(bluetoothSender))
        assertFalse(HuaweiPodsBroadcastTrustPolicy.isTrustedBluetoothStateSender("com.xiaomi.bluetooth"))

        val stateActions = listOf(
            HuaweiPodsAction.ACTION_PODS_CONNECTED,
            HuaweiPodsAction.ACTION_PODS_DISCONNECTED,
            HuaweiPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED,
            HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED,
            HuaweiPodsAction.ACTION_PODS_ANC_CHANGED,
            HuaweiPodsAction.ACTION_HUAWEI_ANC_LEVEL_CHANGED,
            HuaweiPodsAction.ACTION_DEVICE_ROUTE_PROBE_RESULT,
            HuaweiPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE,
            HuaweiPodsAction.ACTION_MODULE_MI_BLUETOOTH_SERVICE_ALIVE,
        )
        stateActions.forEach { action ->
            assertTrue(
                "Action $action should accept bluetooth sender",
                HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(action, bluetoothSender),
            )
        }
    }

    @Test
    fun `untrusted sender or null should be rejected for state broadcasts`() {
        val untrustedSenders = listOf(
            null,
            "",
            "com.fake.bluetooth",
            "io.github.artifical0.huaweipods.coloros",
            "com.tencent.mm",
        )
        val stateActions = listOf(
            HuaweiPodsAction.ACTION_PODS_CONNECTED,
            HuaweiPodsAction.ACTION_PODS_DISCONNECTED,
            HuaweiPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED,
            HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED,
            HuaweiPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE,
        )
        for (sender in untrustedSenders) {
            assertFalse(
                "Sender $sender should not be trusted for bluetooth state",
                HuaweiPodsBroadcastTrustPolicy.isTrustedBluetoothStateSender(sender),
            )
            for (action in stateActions) {
                assertFalse(
                    "Action $action should reject untrusted sender $sender",
                    HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(action, sender),
                )
            }
        }
    }

    @Test
    fun `smart audio sender should only pass smart audio domain`() {
        assertTrue(
            HuaweiPodsBroadcastTrustPolicy.isTrustedSmartAudioSender(
                HuaweiPodsBroadcastTrustPolicy.PACKAGE_SMART_AUDIO,
            ),
        )
        assertFalse(HuaweiPodsBroadcastTrustPolicy.isTrustedSmartAudioSender(null))
        assertFalse(HuaweiPodsBroadcastTrustPolicy.isTrustedSmartAudioSender("com.other.app"))

        val smartAudioAction = HuaweiPodsAction.ACTION_SMART_AUDIO_FREECLIP2_RESULT
        assertTrue(
            HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(
                smartAudioAction,
                HuaweiPodsBroadcastTrustPolicy.PACKAGE_SMART_AUDIO,
            ),
        )
        assertFalse(
            HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(
                smartAudioAction,
                "com.fake.smartaudio",
            ),
        )
    }

    @Test
    fun `My Devices accepts only confirmed Bluetooth status actions`() {
        val actions = listOf(
            HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED,
            HuaweiPodsAction.ACTION_PODS_ANC_CHANGED,
            HuaweiPodsAction.ACTION_HUAWEI_LOW_LATENCY_CHANGED,
            HuaweiPodsAction.ACTION_HUAWEI_WEAR_DETECTION_CHANGED,
        )
        actions.forEach { action ->
            assertEquals(
                BroadcastActionDomain.BLUETOOTH_STATE,
                HuaweiPodsBroadcastTrustPolicy.resolveDomain(action),
            )
            assertTrue(
                "My Devices must accept Bluetooth status $action",
                HuaweiPodsBroadcastTrustPolicy.shouldAcceptMyDevicesStateBroadcast(
                    action,
                    HuaweiPodsBroadcastTrustPolicy.PACKAGE_BLUETOOTH,
                ),
            )
        }
    }

    @Test
    fun `My Devices rejects state with missing identity or non Bluetooth sender`() {
        val actions = listOf(
            HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED,
            HuaweiPodsAction.ACTION_PODS_ANC_CHANGED,
            HuaweiPodsAction.ACTION_HUAWEI_LOW_LATENCY_CHANGED,
            HuaweiPodsAction.ACTION_HUAWEI_WEAR_DETECTION_CHANGED,
        )
        val senders = listOf(
            null,
            "",
            "com.fake.bluetooth",
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_APP,
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_HEYTAP_MYDEVICES,
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_HEYTAP_ACCESSORY,
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_SMART_AUDIO,
        )
        actions.forEach { action ->
            senders.forEach { sender ->
                assertFalse(
                    "My Devices must reject status $action from $sender",
                    HuaweiPodsBroadcastTrustPolicy.shouldAcceptMyDevicesStateBroadcast(action, sender),
                )
            }
        }
    }

    @Test
    fun `My Devices rejects control system and unregistered state actions even from Bluetooth`() {
        val actions = listOf(
            null,
            "",
            "chen.action.huaweipods.coloros.unknown_custom_action",
            "android.bluetooth.adapter.action.STATE_CHANGED",
            HuaweiPodsAction.ACTION_REFRESH_STATUS,
            HuaweiPodsAction.ACTION_HUAWEI_LOW_LATENCY_SET,
            HuaweiPodsAction.ACTION_CONFIG_CHANGED,
            HuaweiPodsAction.ACTION_PODS_CONNECTED,
            HuaweiPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED,
            HuaweiPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE,
        )
        actions.forEach { action ->
            assertFalse(
                "My Devices must reject action $action outside its receiver contract",
                HuaweiPodsBroadcastTrustPolicy.shouldAcceptMyDevicesStateBroadcast(
                    action,
                    HuaweiPodsBroadcastTrustPolicy.PACKAGE_BLUETOOTH,
                ),
            )
        }
    }

    @Test
    fun `mac request and mac reply never share one wire action`() {
        // 两个常量同名时 `when` 会落到请求侧的控制域，被信任的控制发送方就能伪造一次 MAC 回包。
        assertFalse(
            HuaweiPodsAction.ACTION_GET_PODS_MAC == HuaweiPodsAction.ACTION_PODS_MAC_RECEIVED,
        )
        assertEquals(
            BroadcastActionDomain.APP_CONTROL,
            HuaweiPodsBroadcastTrustPolicy.resolveDomain(HuaweiPodsAction.ACTION_GET_PODS_MAC),
        )
        assertEquals(
            BroadcastActionDomain.BLUETOOTH_STATE,
            HuaweiPodsBroadcastTrustPolicy.resolveDomain(HuaweiPodsAction.ACTION_PODS_MAC_RECEIVED),
        )
        assertFalse(
            HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(
                HuaweiPodsAction.ACTION_PODS_MAC_RECEIVED,
                HuaweiPodsBroadcastTrustPolicy.PACKAGE_HEYTAP_MYDEVICES,
            ),
        )
        assertTrue(
            HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(
                HuaweiPodsAction.ACTION_PODS_MAC_RECEIVED,
                HuaweiPodsBroadcastTrustPolicy.PACKAGE_BLUETOOTH,
            ),
        )
    }

    @Test
    fun `my devices accepts the disconnect event so stale battery can be cleared`() {
        assertTrue(
            HuaweiPodsBroadcastTrustPolicy.shouldAcceptMyDevicesStateBroadcast(
                HuaweiPodsAction.ACTION_PODS_DISCONNECTED,
                HuaweiPodsBroadcastTrustPolicy.PACKAGE_BLUETOOTH,
            ),
        )
        assertFalse(
            HuaweiPodsBroadcastTrustPolicy.shouldAcceptMyDevicesStateBroadcast(
                HuaweiPodsAction.ACTION_PODS_DISCONNECTED,
                "com.random.sender",
            ),
        )
    }

    @Test
    fun `unknown action is strictly rejected fail-closed`() {
        val unknownActions = listOf(
            "chen.action.huaweipods.coloros.unknown_custom_action",
            "com.malicious.inject",
            "arbitrary.custom.broadcast",
            "",
            null,
        )
        val senders = listOf(
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_APP,
            HuaweiPodsBroadcastTrustPolicy.PACKAGE_BLUETOOTH,
            "com.random.sender",
            null,
        )
        for (action in unknownActions) {
            assertEquals(BroadcastActionDomain.UNKNOWN, HuaweiPodsBroadcastTrustPolicy.resolveDomain(action.orEmpty()))
            for (sender in senders) {
                assertFalse(
                    "Action '$action' from sender '$sender' should be strictly rejected",
                    HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(action, sender),
                )
            }
        }
    }

    /**
     * 通知宿主的两个"重建/取消耳机卡片"指令归 APP_CONTROL：
     * 模块 App 与蓝牙进程都会发，归 BLUETOOTH_STATE 会把 App 自己发的拦掉。
     * 但它们仍然必须 fail-closed —— 未共享身份的裸广播（sender=null）一律拒。
     */
    @Test fun podsNotificationCommandsAreAppControlAndFailClosed() {
        val actions = listOf(
            HuaweiPodsAction.ACTION_UPDATE_PODS_NOTIFICATION,
            HuaweiPodsAction.ACTION_CANCEL_PODS_NOTIFICATION,
        )
        for (action in actions) {
            assertEquals(BroadcastActionDomain.APP_CONTROL, HuaweiPodsBroadcastTrustPolicy.resolveDomain(action))
            assertTrue(HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(
                action, HuaweiPodsBroadcastTrustPolicy.PACKAGE_APP))
            assertTrue(HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(
                action, HuaweiPodsBroadcastTrustPolicy.PACKAGE_BLUETOOTH))
            assertFalse("未共享身份的广播必须被拒", HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(action, null))
            assertFalse(HuaweiPodsBroadcastTrustPolicy.shouldAcceptBroadcast(action, "com.random.sender"))
        }
    }
}

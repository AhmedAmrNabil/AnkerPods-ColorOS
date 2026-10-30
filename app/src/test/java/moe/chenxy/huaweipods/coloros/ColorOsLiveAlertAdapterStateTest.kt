package moe.chenxy.huaweipods.coloros

import android.bluetooth.BluetoothAdapter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关机类广播没有发送方身份，是否取消流体云卡片必须对齐真实适配器状态。
 * 这里锁死三种情况：真实关机 → 取消；声称关机但适配器还开着 → 不取消；
 * 拿不到真实状态 → 不取消（fail-closed，真关机时进程本来也会退出）。
 */
class ColorOsLiveAlertAdapterStateTest {
    @Test
    fun `real shutdown cancels the live alert`() {
        assertTrue(
            ColorOsLiveAlertConnectionPolicy.shouldCancelForAdapterState(
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_TURNING_OFF,
            ),
        )
        assertTrue(
            ColorOsLiveAlertConnectionPolicy.shouldCancelForAdapterState(
                BluetoothAdapter.STATE_OFF,
                BluetoothAdapter.STATE_OFF,
            ),
        )
    }

    @Test
    fun `forged shutdown is ignored while the adapter is still on`() {
        assertFalse(
            ColorOsLiveAlertConnectionPolicy.shouldCancelForAdapterState(
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_ON,
            ),
        )
        assertFalse(
            ColorOsLiveAlertConnectionPolicy.shouldCancelForAdapterState(
                BluetoothAdapter.STATE_OFF,
                BluetoothAdapter.STATE_ON,
            ),
        )
    }

    @Test
    fun `unknown actual state never cancels`() {
        assertFalse(
            ColorOsLiveAlertConnectionPolicy.shouldCancelForAdapterState(
                BluetoothAdapter.STATE_TURNING_OFF,
                null,
            ),
        )
        assertFalse(
            ColorOsLiveAlertConnectionPolicy.shouldCancelForAdapterState(
                BluetoothAdapter.STATE_OFF,
                null,
            ),
        )
    }

    @Test
    fun `non shutdown claims never cancel`() {
        listOf(BluetoothAdapter.STATE_ON, BluetoothAdapter.STATE_TURNING_ON, BluetoothAdapter.ERROR)
            .forEach { claimed ->
                assertFalse(
                    "claimed=$claimed",
                    ColorOsLiveAlertConnectionPolicy.shouldCancelForAdapterState(
                        claimed,
                        BluetoothAdapter.STATE_OFF,
                    ),
                )
            }
    }
}

package moe.chenxy.huaweipods.coloros

import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.NoiseControlMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorOsLiveAlertNoiseModeTest {
    @Test
    fun `models without noise control get no button`() {
        assertNull(colorOsLiveAlertNextNoiseMode(HuaweiDeviceRoute.HUAWEI_FREECLIP, NoiseControlMode.OFF))
        assertNull(colorOsLiveAlertNextNoiseMode(null, NoiseControlMode.OFF))
    }

    @Test
    fun `three state models cycle off to anc to awareness`() {
        val route = HuaweiDeviceRoute.HUAWEI_FREEBUDS_PRO5
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.OFF))
        assertEquals(
            NoiseControlMode.TRANSPARENCY,
            colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.NOISE_CANCELLATION),
        )
        assertEquals(NoiseControlMode.OFF, colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.TRANSPARENCY))
    }

    @Test
    fun `two state models skip awareness`() {
        val route = HuaweiDeviceRoute.HUAWEI_FREEBUDS3
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.OFF))
        assertEquals(
            NoiseControlMode.OFF,
            colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.NOISE_CANCELLATION),
        )
        // 机型不支持通透时，即使耳机上报了通透也不能把按钮指向它。
        assertEquals(
            NoiseControlMode.OFF,
            colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.TRANSPARENCY),
        )
    }

    @Test
    fun `unknown state starts from noise cancellation`() {
        val route = HuaweiDeviceRoute.HUAWEI_FREEBUDS_PRO5
        assertEquals(
            NoiseControlMode.NOISE_CANCELLATION,
            colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.UNKNOWN),
        )
        assertEquals(
            NoiseControlMode.NOISE_CANCELLATION,
            colorOsLiveAlertNextNoiseMode(route, null),
        )
    }
}

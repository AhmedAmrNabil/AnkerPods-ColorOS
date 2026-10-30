package moe.chenxy.huaweipods.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorOsToggleWriteLedgerTest {
    private val address = "AA:BB:CC:DD:EE:FF"

    @Test
    fun `pending write stays pending until timeout`() {
        val ledger = ColorOsToggleWriteLedger(timeoutMillis = 1_000L)
        ledger.begin(address, ColorOsToggleKey.LOW_LATENCY, requested = true, now = 5_000L)
        assertTrue(ledger.isPending(address, ColorOsToggleKey.LOW_LATENCY, now = 5_999L))
        assertTrue(ledger.expired(now = 5_999L).isEmpty())
    }

    @Test
    fun `unconfirmed write expires and reports the requested value`() {
        val ledger = ColorOsToggleWriteLedger(timeoutMillis = 1_000L)
        ledger.begin(address, ColorOsToggleKey.LOW_LATENCY, requested = true, now = 5_000L)
        val expired = ledger.expired(now = 6_000L)
        assertEquals(1, expired.size)
        assertEquals(address, expired.single().address)
        assertEquals(ColorOsToggleKey.LOW_LATENCY, expired.single().key)
        assertTrue(expired.single().requested)
        // 取走后不再重复上报，避免同一写入回滚两次。
        assertTrue(ledger.expired(now = 6_500L).isEmpty())
    }

    @Test
    fun `state report confirms the write and clears it`() {
        val ledger = ColorOsToggleWriteLedger(timeoutMillis = 1_000L)
        ledger.begin(address, ColorOsToggleKey.WEAR_DETECTION, requested = false, now = 1_000L)
        assertEquals(false, ledger.complete(address, ColorOsToggleKey.WEAR_DETECTION))
        assertNull(ledger.complete(address, ColorOsToggleKey.WEAR_DETECTION))
        assertTrue(ledger.expired(now = 9_000L).isEmpty())
    }

    @Test
    fun `addresses are matched case insensitively and kept apart`() {
        val ledger = ColorOsToggleWriteLedger(timeoutMillis = 1_000L)
        ledger.begin("aa:bb:cc:dd:ee:ff", ColorOsToggleKey.LOW_LATENCY, requested = true, now = 0L)
        ledger.begin("11:22:33:44:55:66", ColorOsToggleKey.LOW_LATENCY, requested = true, now = 0L)
        assertEquals(true, ledger.complete(address.uppercase(), ColorOsToggleKey.LOW_LATENCY))
        assertTrue(ledger.isPending("11:22:33:44:55:66", ColorOsToggleKey.LOW_LATENCY, now = 100L))
        assertEquals(1, ledger.expired(now = 2_000L).size)
    }

    @Test
    fun `newer request replaces the pending one`() {
        val ledger = ColorOsToggleWriteLedger(timeoutMillis = 1_000L)
        ledger.begin(address, ColorOsToggleKey.LOW_LATENCY, requested = true, now = 0L)
        ledger.begin(address, ColorOsToggleKey.LOW_LATENCY, requested = false, now = 900L)
        val expired = ledger.expired(now = 2_000L)
        assertEquals(1, expired.size)
        assertFalse(expired.single().requested)
    }

    @Test
    fun `clear drops every device`() {
        val ledger = ColorOsToggleWriteLedger(timeoutMillis = 1_000L)
        ledger.begin(address, ColorOsToggleKey.LOW_LATENCY, requested = true, now = 0L)
        ledger.begin(address, ColorOsToggleKey.WEAR_DETECTION, requested = true, now = 0L)
        ledger.clear()
        assertTrue(ledger.expired(now = 5_000L).isEmpty())
    }
}

package com.offlinemaps.core.routing

import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that drops a fix older than the one already followed. OPTIMIZATION.md E-06.
 */
class FixOrderGateTest {

    private val point = GeoPoint(42.5, 1.5)

    private fun fix(atMillis: Long) = LocationFix(point, accuracyMeters = 5f, timestampMillis = atMillis)

    @Test
    fun `the first fix is accepted whatever its timestamp`() {
        val gate = FixOrderGate()
        assertNull(gate.lastAcceptedTimestampMillis)
        assertTrue(gate.accepts(fix(0L)))
        assertEquals(0L, gate.lastAcceptedTimestampMillis)
    }

    @Test
    fun `a newer fix is accepted and moves the watermark`() {
        val gate = FixOrderGate()
        assertTrue(gate.accepts(fix(1_000L)))
        assertTrue(gate.accepts(fix(1_001L)))
        assertEquals(1_001L, gate.lastAcceptedTimestampMillis)
    }

    @Test
    fun `an older fix is refused`() {
        val gate = FixOrderGate()
        gate.accepts(fix(2_000L))
        assertFalse(gate.accepts(fix(1_999L)))
        assertFalse(gate.accepts(fix(0L)))
    }

    @Test
    fun `two fixes in the same millisecond are both accepted`() {
        // Not evidence of a stale fix: refusing them would drop a real update.
        val gate = FixOrderGate()
        assertTrue(gate.accepts(fix(1_500L)))
        assertTrue(gate.accepts(fix(1_500L)))
        assertEquals(1_500L, gate.lastAcceptedTimestampMillis)
    }

    @Test
    fun `a refused fix does not lower the watermark`() {
        val gate = FixOrderGate()
        gate.accepts(fix(5_000L))
        gate.accepts(fix(1_000L))
        // A fix between the refused one and the accepted one is still stale.
        assertFalse(gate.accepts(fix(4_000L)))
        assertEquals(5_000L, gate.lastAcceptedTimestampMillis)
        assertTrue(gate.accepts(fix(6_000L)))
    }

    @Test
    fun `a reset accepts an older fix again`() {
        val gate = FixOrderGate()
        gate.accepts(fix(5_000L))
        gate.reset()
        assertNull(gate.lastAcceptedTimestampMillis)
        assertTrue(gate.accepts(fix(1_000L)))
    }
}

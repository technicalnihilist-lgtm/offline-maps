package com.offlinemaps.core.routing

import com.offlinemaps.core.common.GeoMath
import com.offlinemaps.core.common.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A deviation is only answered with a recalculation when both the cooldown has passed and the driver
 * has moved since the last attempt, so a stationary off-route fix cannot spend the whole attempt
 * budget recalculating from the same point.
 */
class RerouteGateTest {

    private val start = GeoPoint(42.5, 1.5)

    private fun north(metres: Double): GeoPoint = GeoMath.destination(start, metres, 0.0)

    private val policy = ReroutePolicy(cooldownMillis = 12_000L, minTravelMeters = 30.0, maxAttempts = 5)

    @Test
    fun `the first deviation is always answered`() {
        val gate = RerouteGate(policy)
        assertTrue(gate.shouldAttempt(0L, start))
    }

    @Test
    fun `a stationary off-route fix does not loop`() {
        val gate = RerouteGate(policy)
        assertTrue(gate.shouldAttempt(0L, start))
        gate.recordAttempt(0L, start)

        // Same spot, one cooldown after another: the time limit alone would allow all of these.
        for (second in 1..20) {
            val now = second * 12_000L
            assertFalse("attempt allowed while stationary at ${now}ms", gate.shouldAttempt(now, start))
        }
    }

    @Test
    fun `a tiny wobble of the fix is not movement`() {
        val gate = RerouteGate(policy)
        gate.recordAttempt(0L, start)

        // Inside the 30 m of GPS noise the gate ignores, however long the driver waits.
        assertFalse(gate.shouldAttempt(60_000L, north(5.0)))
        assertFalse(gate.shouldAttempt(60_000L, north(20.0)))
    }

    @Test
    fun `the cooldown still paces a moving driver`() {
        val gate = RerouteGate(policy)
        gate.recordAttempt(0L, start)

        // Moved plenty, but too soon.
        assertFalse(gate.shouldAttempt(11_999L, north(200.0)))
        assertTrue(gate.shouldAttempt(12_000L, north(200.0)))
    }

    @Test
    fun `movement past the minimum allows another attempt after the cooldown`() {
        val gate = RerouteGate(policy)
        gate.recordAttempt(0L, start)
        assertFalse(gate.shouldAttempt(12_000L, north(29.0)))
        assertTrue(gate.shouldAttempt(12_000L, north(31.0)))
    }

    @Test
    fun `the attempt budget is still enforced`() {
        val gate = RerouteGate(policy)
        var metres = 0.0
        for (attempt in 0 until policy.maxAttempts) {
            val now = attempt * 12_000L
            assertTrue("attempt $attempt should be allowed", gate.shouldAttempt(now, north(metres)))
            gate.recordAttempt(now, north(metres))
            metres += 200.0
        }
        // Far away and long after: the budget is spent, so no more recalculations.
        assertFalse(gate.shouldAttempt(600_000L, north(metres)))
    }

    @Test
    fun `a successful recalculation restarts the budget but not the cooldown`() {
        val gate = RerouteGate(policy)
        var metres = 0.0
        repeat(policy.maxAttempts) { attempt ->
            val now = attempt * 12_000L
            gate.recordAttempt(now, north(metres))
            metres += 200.0
        }
        gate.onRerouted()

        // Budget back, but the last attempt is still the anchor: no movement, no attempt.
        assertFalse(gate.shouldAttempt(60_000L, north(metres - 200.0)))
        // Moved on and past the cooldown: answered again.
        assertTrue(gate.shouldAttempt(60_000L, north(metres)))
    }

    @Test
    fun `a new session forgets the previous one`() {
        val gate = RerouteGate(policy)
        repeat(policy.maxAttempts) { attempt -> gate.recordAttempt(attempt * 12_000L, start) }
        assertFalse(gate.shouldAttempt(60_000L, start))

        gate.reset()

        // New session at the same spot, no cooldown to wait out.
        assertTrue(gate.shouldAttempt(60_000L, start))
    }
}

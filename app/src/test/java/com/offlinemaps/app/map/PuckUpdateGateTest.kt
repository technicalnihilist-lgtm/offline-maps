package com.offlinemaps.app.map

import com.google.common.truth.Truth.assertThat
import com.offlinemaps.core.common.GeoMath
import com.offlinemaps.core.common.GeoPoint
import org.junit.Test

/**
 * The puck is fed from every GNSS fix, most of which are a metre or two of noise away from the last
 * one. These tests pin down that such a fix is dropped, while a real move or a real change in the
 * accuracy ring still gets through.
 */
class PuckUpdateGateTest {

    private val origin = GeoPoint(latitude = 12.9716, longitude = 77.5946)

    private fun at(metres: Double, bearing: Double = 45.0): GeoPoint =
        GeoMath.destination(origin, metres, bearing)

    @Test
    fun firstFixIsAlwaysAccepted() {
        val gate = PuckUpdateGate()

        assertThat(gate.shouldPush(origin, 8f)).isTrue()
        assertThat(gate.accepted).isEqualTo(1)
        assertThat(gate.skipped).isEqualTo(0)
        assertThat(gate.hasAcceptedFix()).isTrue()
        assertThat(gate.acceptedPoint()).isEqualTo(origin)
    }

    @Test
    fun jitterInsideTheThresholdIsDropped() {
        val gate = PuckUpdateGate()
        gate.shouldPush(origin, 8f)

        assertThat(gate.shouldPush(at(0.4), 8f)).isFalse()
        assertThat(gate.shouldPush(at(1.2, bearing = 200.0), 8f)).isFalse()
        assertThat(gate.shouldPush(at(2.9, bearing = 310.0), 8f)).isFalse()

        assertThat(gate.accepted).isEqualTo(1)
        assertThat(gate.skipped).isEqualTo(3)
        // The rejected fixes did not move the reference.
        assertThat(gate.acceptedPoint()).isEqualTo(origin)
    }

    @Test
    fun aRealMoveIsAccepted() {
        val gate = PuckUpdateGate()
        gate.shouldPush(origin, 8f)
        val moved = at(5.0)

        assertThat(gate.shouldPush(moved, 8f)).isTrue()
        assertThat(gate.acceptedPoint()).isEqualTo(moved)
        assertThat(gate.accepted).isEqualTo(2)
    }

    @Test
    fun movementJustPastTheThresholdIsAccepted() {
        val gate = PuckUpdateGate()
        gate.shouldPush(origin, 8f)

        assertThat(gate.shouldPush(at(PuckUpdateGate.DEFAULT_MIN_MOVE_METERS + 0.1), 8f)).isTrue()
    }

    @Test
    fun stationaryFixWithAMateriallyDifferentAccuracyIsAccepted() {
        val gate = PuckUpdateGate()
        gate.shouldPush(origin, 8f)

        // Same position, the fix degraded from 8 m to 25 m: the accuracy ring must follow.
        assertThat(gate.shouldPush(origin, 25f)).isTrue()
        assertThat(gate.accepted).isEqualTo(2)
    }

    @Test
    fun stationaryFixWithANegligibleAccuracyChangeIsDropped() {
        val gate = PuckUpdateGate()
        gate.shouldPush(origin, 8f)

        assertThat(gate.shouldPush(origin, 8.5f)).isFalse()
        assertThat(gate.shouldPush(origin, 7.6f)).isFalse()
        assertThat(gate.skipped).isEqualTo(2)
    }

    @Test
    fun aPoorAccuracyNeedsAProportionallyLargerChange() {
        val gate = PuckUpdateGate()
        gate.shouldPush(origin, 40f)

        // 4 m of change on a 40 m fix is noise (below the 25% relative floor).
        assertThat(gate.shouldPush(origin, 44f)).isFalse()
        // 25% of 40 m is the floor at that accuracy: 10 m is material.
        assertThat(gate.shouldPush(origin, 50f)).isTrue()
    }

    @Test
    fun accuracyBecomingKnownCountsAsMaterial() {
        val gate = PuckUpdateGate()
        gate.shouldPush(origin, null)

        assertThat(gate.shouldPush(origin, 12f)).isTrue()
    }

    @Test
    fun slowDriftAccumulatesUntilItIsVisible() {
        val gate = PuckUpdateGate()
        gate.shouldPush(origin, 8f)

        // Steps of 1.1 m: the first two are dropped, the third is past the 3 m threshold.
        val acceptedAt = (1..6).mapNotNull { step ->
            val fix = at(step * 1.1)
            if (gate.shouldPush(fix, 8f)) step else null
        }

        assertThat(acceptedAt.first()).isEqualTo(3)
        assertThat(gate.skipped).isEqualTo(2)
    }

    @Test
    fun resetMakesTheNextFixAcceptedEvenWhenIdentical() {
        val gate = PuckUpdateGate()
        gate.shouldPush(origin, 8f)
        assertThat(gate.shouldPush(origin, 8f)).isFalse()

        gate.reset()

        assertThat(gate.hasAcceptedFix()).isFalse()
        assertThat(gate.shouldPush(origin, 8f)).isTrue()
    }

    @Test
    fun thresholdsAreConfigurable() {
        val gate = PuckUpdateGate(minMoveMeters = 10.0, minAccuracyDeltaMeters = 1f)

        gate.shouldPush(origin, 8f)

        assertThat(gate.shouldPush(at(6.0), 8f)).isFalse()
        assertThat(gate.shouldPush(at(11.0), 8f)).isTrue()
    }
}

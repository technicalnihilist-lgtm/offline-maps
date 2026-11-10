package com.offlinemaps.core.location

import com.google.common.truth.Truth.assertThat
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.LocationFix
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Explore mode used to publish every fix the platform handed over, duplicates and metre-wide jitter
 * included, and each one rewrote the map screen's state (OPTIMIZATION.md G-02). These tests pin the
 * gate that decides which fixes are worth publishing: a duplicate or a stale fix never is, movement
 * has to reach the threshold measured from the last *published* fix, and a phone that stands still
 * still gets a refresh once the hold elapses.
 */
class ExploreFixGateTest {

    private val origin = GeoPoint(18.520000, 73.856744)

    /** A fix [meters] north of [origin], at [timeMs]. */
    private fun fix(
        meters: Double = 0.0,
        timeMs: Long = 1_000L,
        accuracy: Float = 5f,
    ) = LocationFix(
        point = GeoPoint(origin.latitude + meters / METERS_PER_DEGREE_LAT, origin.longitude),
        accuracyMeters = accuracy,
        timestampMillis = timeMs,
    )

    @Test
    fun `the first fix is always published`() {
        val gate = ExploreFixGate()
        assertThat(gate.accept(fix())).isTrue()
        assertThat(gate.accepted).isEqualTo(1)
        assertThat(gate.dropped).isEqualTo(0)
        assertThat(gate.publishedFix()?.point).isEqualTo(origin)
    }

    @Test
    fun `the same fix delivered twice is dropped`() {
        val gate = ExploreFixGate()
        assertThat(gate.accept(fix(timeMs = 1_000L))).isTrue()
        // Same instant, same position: one fix, two providers.
        assertThat(gate.accept(fix(timeMs = 1_000L))).isFalse()
        assertThat(gate.accepted).isEqualTo(1)
        assertThat(gate.dropped).isEqualTo(1)
    }

    @Test
    fun `a fix inside the minimum distance is dropped`() {
        val gate = ExploreFixGate(minDistanceMeters = 8.0)
        assertThat(gate.accept(fix(timeMs = 1_000L))).isTrue()
        assertThat(gate.accept(fix(meters = 3.0, timeMs = 5_000L))).isFalse()
        assertThat(gate.accepted).isEqualTo(1)
        // The published fix stays the reference.
        assertThat(gate.publishedFix()?.point).isEqualTo(origin)
    }

    @Test
    fun `a fix at the minimum distance is published`() {
        val gate = ExploreFixGate(minDistanceMeters = 8.0)
        assertThat(gate.accept(fix(timeMs = 1_000L))).isTrue()
        assertThat(gate.accept(fix(meters = 8.5, timeMs = 5_000L))).isTrue()
        assertThat(gate.dropped).isEqualTo(0)
        assertThat(gate.accepted).isEqualTo(2)
    }

    @Test
    fun `sub threshold steps accumulate against the published fix`() {
        val gate = ExploreFixGate(minDistanceMeters = 8.0)
        assertThat(gate.accept(fix(meters = 0.0, timeMs = 1_000L))).isTrue()
        assertThat(gate.accept(fix(meters = 4.0, timeMs = 5_000L))).isFalse()
        // Past 8 m from the published fix, even though only 4.5 m from the fix just dropped.
        assertThat(gate.accept(fix(meters = 8.5, timeMs = 9_000L))).isTrue()
        assertThat(gate.accepted).isEqualTo(2)
    }

    @Test
    fun `a fix older than the published one is dropped`() {
        val gate = ExploreFixGate()
        assertThat(gate.accept(fix(meters = 20.0, timeMs = 10_000L))).isTrue()
        // The network provider answering late must not walk the marker backwards.
        assertThat(gate.accept(fix(meters = 40.0, timeMs = 9_000L))).isFalse()
        assertThat(gate.publishedFix()?.timestampMillis).isEqualTo(10_000L)
    }

    @Test
    fun `a stationary phone still refreshes once the hold elapses`() {
        val gate = ExploreFixGate(minDistanceMeters = 8.0, maxHoldMillis = 30_000L)
        assertThat(gate.accept(fix(meters = 0.0, timeMs = 0L))).isTrue()
        var published = 1
        // A fix every 4 s, all inside the distance threshold.
        for (seconds in 4..40 step 4) {
            if (gate.accept(fix(meters = 0.5, timeMs = seconds * 1_000L))) published++
        }
        // The first, then one at 32 s: nothing at 4 s through 28 s.
        assertThat(published).isEqualTo(2)
        assertThat(gate.publishedFix()?.timestampMillis).isEqualTo(32_000L)
    }

    @Test
    fun `reset makes the next fix publishable again`() {
        val gate = ExploreFixGate()
        assertThat(gate.accept(fix(timeMs = 1_000L))).isTrue()
        gate.reset()
        assertThat(gate.accept(fix(timeMs = 1_000L))).isTrue()
    }

    @Test
    fun `the explore filter keeps only the fixes the gate publishes`() = runTest {
        val stream = flowOf(
            fix(meters = 0.0, timeMs = 1_000L),
            fix(meters = 0.0, timeMs = 1_000L), // duplicate
            fix(meters = 2.0, timeMs = 5_000L), // inside 8 m
            fix(meters = 12.0, timeMs = 9_000L), // past 8 m
        )
        val published = stream.exploreFiltered(ExploreFixGate(minDistanceMeters = 8.0)).toList()
        assertThat(published.map { it.timestampMillis }).containsExactly(1_000L, 9_000L).inOrder()
    }

    private companion object {
        /** One degree of latitude on the sphere [com.offlinemaps.core.common.GeoMath] measures on. */
        const val METERS_PER_DEGREE_LAT = 111_194.93
    }
}

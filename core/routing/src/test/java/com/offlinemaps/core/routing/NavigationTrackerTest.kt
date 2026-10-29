package com.offlinemaps.core.routing

import com.offlinemaps.core.common.GeoMath
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.LocationFix
import com.offlinemaps.core.common.TravelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTrackerTest {

    private val a = GeoPoint(42.5000, 1.5000)
    private val b = GeoPoint(42.5000, 1.5100)
    private val c = GeoPoint(42.5060, 1.5100)

    private fun plan(): RoutePlan {
        val geometry = buildList {
            for (i in 0..20) add(GeoPoint(a.latitude, a.longitude + (b.longitude - a.longitude) * i / 20.0))
            for (i in 1..20) add(GeoPoint(b.latitude + (c.latitude - b.latitude) * i / 20.0, c.longitude))
        }
        val cumulative = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cumulative[i] = cumulative[i - 1] + GeoMath.distanceMeters(geometry[i - 1], geometry[i])
        }
        val total = cumulative.last()
        val distanceToB = cumulative[20]
        return RoutePlan(
            profile = TravelProfile.CAR,
            geometry = geometry,
            distanceMeters = total,
            durationSeconds = (total / 10).toLong(),
            maneuvers = listOf(
                Maneuver(ManeuverType.DEPART, 0, "Start street", 0.0, 0, 0, 0.0, "Start"),
                Maneuver(ManeuverType.TURN_LEFT, -2, "Second street", distanceToB, 60, 20, distanceToB, "Turn left"),
                Maneuver(ManeuverType.ARRIVE, 4, null, 0.0, 0, geometry.lastIndex, total, "Arrive"),
            ),
            origin = a,
            destination = c,
            cumulativeMeters = cumulative,
        )
    }

    @Test
    fun `starts at the route origin with the first turn ahead`() {
        val state = NavigationTracker(plan()).onLocation(LocationFix(a, accuracyMeters = 5f))
        assertFalse(state.offRoute)
        // The departure manoeuvre is behind us the moment we are on the line.
        assertEquals(1, state.nextManeuverIndex)
        assertTrue("remaining was ${state.remainingMeters}", state.remainingMeters > 1400)
    }

    @Test
    fun `progress advances along the route`() {
        val state = NavigationTracker(plan()).onLocation(LocationFix(GeoPoint(42.5000, 1.5050), accuracyMeters = 5f))
        assertTrue(state.progressFraction in 0.15f..0.45f)
        assertTrue(state.remainingMeters < 1400)
    }

    @Test
    fun `selects the next manoeuvre after passing the corner`() {
        val tracker = NavigationTracker(plan())
        tracker.onLocation(LocationFix(a, accuracyMeters = 5f))
        val state = tracker.onLocation(LocationFix(GeoPoint(42.5020, 1.5100), accuracyMeters = 5f))
        assertEquals(2, state.nextManeuverIndex)
        assertEquals(ManeuverType.ARRIVE, state.nextManeuver?.type)
    }

    @Test
    fun `declares off route only after repeated bad fixes`() {
        val tracker = NavigationTracker(plan())
        val farAway = GeoPoint(42.5300, 1.5300)
        assertFalse("one bad fix is not enough", tracker.onLocation(LocationFix(farAway, accuracyMeters = 5f)).offRoute)
        tracker.onLocation(LocationFix(farAway, accuracyMeters = 5f))
        assertTrue("three consecutive fixes should trigger a recalculation",
            tracker.onLocation(LocationFix(farAway, accuracyMeters = 5f)).offRoute)
    }

    @Test
    fun `weak accuracy never triggers a reroute`() {
        val tracker = NavigationTracker(plan())
        val farAway = GeoPoint(42.5300, 1.5300)
        repeat(5) {
            val state = tracker.onLocation(LocationFix(farAway, accuracyMeters = 250f))
            assertFalse("weak fixes must not trigger rerouting", state.offRoute)
            assertTrue(state.gpsSignalWeak)
        }
    }

    @Test
    fun `arrival is detected within the radius`() {
        val state = NavigationTracker(plan()).onLocation(LocationFix(c, accuracyMeters = 4f))
        assertTrue(state.arrived)
        assertTrue(state.remainingMeters < 30)
    }

    @Test
    fun `snapped point sits on the route not at the raw fix`() {
        val offsetFix = GeoPoint(42.5005, 1.5050)
        val state = NavigationTracker(plan()).onLocation(LocationFix(offsetFix, accuracyMeters = 5f))
        assertTrue(GeoMath.distanceMeters(state.snappedPoint, offsetFix) > 30)
        assertEquals(1.5050, state.snappedPoint.longitude, 0.0005)
    }

    @Test
    fun `remaining time uses current speed when available`() {
        val state = NavigationTracker(plan()).onLocation(
            LocationFix(a, accuracyMeters = 5f, speedMetersPerSecond = 10f),
        )
        assertTrue("expected ~148 s, got ${state.remainingSeconds}", state.remainingSeconds in 100..220)
    }

    @Test
    fun `replacing the plan resets progress`() {
        val tracker = NavigationTracker(plan())
        tracker.onLocation(LocationFix(c, accuracyMeters = 5f))
        tracker.updatePlan(plan())
        val state = tracker.onLocation(LocationFix(a, accuracyMeters = 5f))
        assertFalse(state.arrived)
        assertTrue(state.progressFraction < 0.1f)
    }

    @Test
    fun `should recalculate helper ignores arrival`() {
        val arrived = NavigationState(
            alongRouteMeters = 1480.0, remainingMeters = 2.0, remainingSeconds = 1,
            nextManeuver = null, nextManeuverIndex = 2, distanceToNextManeuverMeters = 0.0,
            offRoute = true, arrived = true, progressFraction = 1f,
            snappedPoint = c, currentStreetName = null, gpsSignalWeak = false, headingDegrees = 0.0,
        )
        assertFalse(NavigationTracker.shouldRecalculate(arrived))
    }
}

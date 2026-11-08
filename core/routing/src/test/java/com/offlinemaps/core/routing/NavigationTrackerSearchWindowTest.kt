package com.offlinemaps.core.routing

import com.offlinemaps.core.common.GeoMath
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.LocationFix
import com.offlinemaps.core.common.TravelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tracker follows the route from a window around the last matched segment and only searches the
 * rest of the route when a fix lands outside that window - the case the spatial index was added for.
 * These tests drive that path on a long route: a fix far ahead of the window must land on the same
 * segment a scan of the whole route would pick, and a fix beside the route must still be recognised
 * as off route.
 */
class NavigationTrackerSearchWindowTest {

    private fun route(points: Int = 5000): List<GeoPoint> {
        val centre = GeoPoint(42.5, 1.5)
        return (0 until points).map { i ->
            GeoMath.destination(centre, 12_000.0, 300.0 * i / (points - 1))
        }
    }

    private fun plan(geometry: List<GeoPoint>): RoutePlan {
        val cumulative = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cumulative[i] = cumulative[i - 1] + GeoMath.distanceMeters(geometry[i - 1], geometry[i])
        }
        val total = cumulative.last()
        return RoutePlan(
            profile = TravelProfile.CAR,
            geometry = geometry,
            distanceMeters = total,
            durationSeconds = (total / 12.0).toLong(),
            maneuvers = listOf(
                Maneuver(ManeuverType.DEPART, 0, "Start street", 0.0, 0, 0, 0.0, "Start"),
                Maneuver(ManeuverType.ARRIVE, 4, null, 0.0, 0, geometry.lastIndex, total, "Arrive"),
            ),
            origin = geometry.first(),
            destination = geometry.last(),
            cumulativeMeters = cumulative,
        )
    }

    @Test
    fun `a first fix halfway along the route is placed on the right segment`() {
        val geometry = route()
        val routePlan = plan(geometry)
        val tracker = NavigationTracker(routePlan)
        val state = tracker.onLocation(LocationFix(geometry[4000], accuracyMeters = 5f))
        assertFalse("a fix on the route is not off route", state.offRoute)
        assertEquals(routePlan.cumulativeMeters[4000], state.alongRouteMeters, 20.0)
        assertTrue("progress was ${state.progressFraction}", state.progressFraction in 0.79f..0.81f)
    }

    @Test
    fun `a fix beside the route snaps to the nearest point of the whole route`() {
        val geometry = route()
        val routePlan = plan(geometry)
        val tracker = NavigationTracker(routePlan)
        // Radially outward from the point, so the nearest place on the route is still index 2500.
        val beside = GeoMath.destination(geometry[2500], 300.0, 150.0)
        val state = tracker.onLocation(LocationFix(beside, accuracyMeters = 5f))
        assertEquals(routePlan.cumulativeMeters[2500], state.alongRouteMeters, 30.0)
        assertTrue(
            "snapped point was ${GeoMath.distanceMeters(state.snappedPoint, beside)} m from the fix",
            GeoMath.distanceMeters(state.snappedPoint, beside) > 250.0,
        )
        repeat(2) { tracker.onLocation(LocationFix(beside, accuracyMeters = 5f)) }
        assertTrue(
            "three consecutive fixes 300 m off the route should trigger a recalculation",
            tracker.onLocation(LocationFix(beside, accuracyMeters = 5f)).offRoute,
        )
    }

    @Test
    fun `replacing the plan rebuilds the search index`() {
        val geometry = route()
        val routePlan = plan(geometry)
        val tracker = NavigationTracker(routePlan)
        tracker.onLocation(LocationFix(geometry[3000], accuracyMeters = 5f))
        tracker.updatePlan(routePlan)
        val state = tracker.onLocation(LocationFix(geometry[1000], accuracyMeters = 5f))
        assertEquals(routePlan.cumulativeMeters[1000], state.alongRouteMeters, 20.0)
    }

    @Test
    fun `benchmark - 50 fixes that each jump beyond the follow window`() {
        val geometry = route()
        val routePlan = plan(geometry)
        val tracker = NavigationTracker(routePlan)
        val fixes = 50
        val stride = (geometry.size - 3) / fixes
        val started = System.nanoTime()
        var lastAlong = -1.0
        for (i in 0 until fixes) {
            val at = 1 + i * stride
            val state = tracker.onLocation(LocationFix(geometry[at], accuracyMeters = 4f))
            assertEquals(
                "fix $i landed on the wrong part of the route",
                routePlan.cumulativeMeters[at],
                state.alongRouteMeters,
                40.0,
            )
            assertTrue("progress went backwards at fix $i", state.alongRouteMeters > lastAlong)
            lastAlong = state.alongRouteMeters
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        println(
            "$fixes fixes, each $stride segments (${stride * 12} m) ahead of the last match, " +
                "in $elapsedMillis ms",
        )
        assertTrue("$fixes fixes took $elapsedMillis ms", elapsedMillis < 15_000)
    }
}

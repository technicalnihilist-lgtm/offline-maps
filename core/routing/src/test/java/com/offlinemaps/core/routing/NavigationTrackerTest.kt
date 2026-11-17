package com.offlinemaps.core.routing

import com.offlinemaps.core.common.GeoMath
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.LocationFix
import com.offlinemaps.core.common.TravelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val state = NavigationTracker(plan()).onLocation(LocationFix(a, accuracyMeters = 5f))!!
        assertFalse(state.offRoute)
        // The departure manoeuvre is behind us the moment we are on the line.
        assertEquals(1, state.nextManeuverIndex)
        assertTrue("remaining was ${state.remainingMeters}", state.remainingMeters > 1400)
    }

    @Test
    fun `progress advances along the route`() {
        val state = NavigationTracker(plan()).onLocation(LocationFix(GeoPoint(42.5000, 1.5050), accuracyMeters = 5f))!!
        assertTrue(state.progressFraction in 0.15f..0.45f)
        assertTrue(state.remainingMeters < 1400)
    }

    @Test
    fun `selects the next manoeuvre after passing the corner`() {
        val tracker = NavigationTracker(plan())
        tracker.onLocation(LocationFix(a, accuracyMeters = 5f))
        val state = tracker.onLocation(LocationFix(GeoPoint(42.5020, 1.5100), accuracyMeters = 5f))!!
        assertEquals(2, state.nextManeuverIndex)
        assertEquals(ManeuverType.ARRIVE, state.nextManeuver?.type)
    }

    @Test
    fun `declares off route only after repeated bad fixes`() {
        val tracker = NavigationTracker(plan())
        val farAway = GeoPoint(42.5300, 1.5300)
        assertFalse("one bad fix is not enough", tracker.onLocation(LocationFix(farAway, accuracyMeters = 5f))!!.offRoute)
        tracker.onLocation(LocationFix(farAway, accuracyMeters = 5f))
        assertTrue(
            "three consecutive fixes should trigger a recalculation",
            tracker.onLocation(LocationFix(farAway, accuracyMeters = 5f))!!.offRoute,
        )
    }

    @Test
    fun `weak accuracy never triggers a reroute`() {
        val tracker = NavigationTracker(plan())
        val farAway = GeoPoint(42.5300, 1.5300)
        repeat(5) {
            val state = tracker.onLocation(LocationFix(farAway, accuracyMeters = 250f))!!
            assertFalse("weak fixes must not trigger rerouting", state.offRoute)
            assertTrue(state.gpsSignalWeak)
        }
    }

    @Test
    fun `a fix with no accuracy is not treated as a precise one`() {
        // 60 m north of the route: inside the threshold a 5 m fix earns, outside nothing else.
        val wobble = GeoPoint(42.50054, 1.5050)
        val precise = NavigationTracker(plan())
        repeat(3) { precise.onLocation(LocationFix(wobble, accuracyMeters = 5f)) }
        assertTrue(
            "a precise fix 60 m off the line is a deviation",
            precise.onLocation(LocationFix(wobble, accuracyMeters = 5f))!!.offRoute,
        )

        val unknown = NavigationTracker(plan())
        repeat(5) {
            assertFalse(
                "a fix that reports no accuracy must not be trusted as a tight one",
                unknown.onLocation(LocationFix(wobble))!!.offRoute,
            )
        }
    }

    @Test
    fun `a fix with no accuracy still reports a deviation when clearly off the route`() {
        val farAway = GeoPoint(42.5045, 1.5050) // ~500 m off the line
        val tracker = NavigationTracker(plan())
        tracker.onLocation(LocationFix(farAway))
        tracker.onLocation(LocationFix(farAway))
        assertTrue(
            "an unknown-accuracy fix far from the route must still be able to trigger a recalculation",
            tracker.onLocation(LocationFix(farAway))!!.offRoute,
        )
    }

    @Test
    fun `a fix with no accuracy is not called a weak signal`() {
        // The weak-signal notice reports what the receiver said; a receiver that said nothing is a
        // different thing from one that reported a poor estimate, so this stays false.
        val state = NavigationTracker(plan()).onLocation(LocationFix(a))!!
        assertFalse(state.gpsSignalWeak)
    }

    @Test
    fun `heading falls back to the route when the fix has no bearing`() {
        val state = NavigationTracker(plan()).onLocation(LocationFix(GeoPoint(42.5000, 1.5050), accuracyMeters = 5f))!!
        assertEquals("the first leg runs due east", 90.0, state.headingDegrees, 2.0)
    }

    @Test
    fun `a reported bearing is used as the heading`() {
        val state = NavigationTracker(plan())
            .onLocation(LocationFix(GeoPoint(42.5000, 1.5050), accuracyMeters = 5f, bearingDegrees = 200f))!!
        assertEquals(200.0, state.headingDegrees, 0.001)
    }

    @Test
    fun `a fix with no bearing is not given due north on a one-point plan`() {
        val origin = GeoPoint(42.5000, 1.5000)
        val destination = GeoPoint(42.6000, 1.6000) // north-east, so due north would be wrong
        val degenerate = RoutePlan(
            profile = TravelProfile.CAR,
            geometry = listOf(origin),
            distanceMeters = GeoMath.distanceMeters(origin, destination),
            durationSeconds = 600,
            maneuvers = emptyList(),
            origin = origin,
            destination = destination,
            cumulativeMeters = doubleArrayOf(0.0),
        )
        val state = NavigationTracker(degenerate).onLocation(LocationFix(origin))!!
        assertTrue(
            "expected the bearing towards the destination, got ${state.headingDegrees}",
            state.headingDegrees in 30.0..60.0,
        )
    }

    @Test
    fun `arrival is detected within the radius`() {
        val state = NavigationTracker(plan()).onLocation(LocationFix(c, accuracyMeters = 4f))!!
        assertTrue(state.arrived)
        assertTrue(state.remainingMeters < 30)
    }

    @Test
    fun `snapped point sits on the route not at the raw fix`() {
        val offsetFix = GeoPoint(42.5005, 1.5050)
        val state = NavigationTracker(plan()).onLocation(LocationFix(offsetFix, accuracyMeters = 5f))!!
        assertTrue(GeoMath.distanceMeters(state.snappedPoint, offsetFix) > 30)
        assertEquals(1.5050, state.snappedPoint.longitude, 0.0005)
    }

    @Test
    fun `remaining time uses current speed when available`() {
        val state = NavigationTracker(plan()).onLocation(
            LocationFix(a, accuracyMeters = 5f, speedMetersPerSecond = 10f),
        )!!
        assertTrue("expected ~148 s, got ${state.remainingSeconds}", state.remainingSeconds in 100..220)
    }

    @Test
    fun `replacing the plan resets progress`() {
        val tracker = NavigationTracker(plan())
        tracker.onLocation(LocationFix(c, accuracyMeters = 5f))
        tracker.updatePlan(plan())
        val state = tracker.onLocation(LocationFix(a, accuracyMeters = 5f))!!
        assertFalse(state.arrived)
        assertTrue(state.progressFraction < 0.1f)
    }

    @Test
    fun `a fix older than the last accepted one is ignored`() {
        val tracker = NavigationTracker(plan())
        val onLine = GeoPoint(42.5000, 1.5050)
        val ahead = tracker.onLocation(LocationFix(onLine, accuracyMeters = 5f, timestampMillis = 2_000L))!!
        assertTrue("progress was ${ahead.progressFraction}", ahead.progressFraction > 0.15f)

        // A batched fix from before the one already followed: refused whole, so progress holds.
        assertNull(tracker.onLocation(LocationFix(a, accuracyMeters = 5f, timestampMillis = 1_000L)))

        val next = tracker.onLocation(LocationFix(onLine, accuracyMeters = 5f, timestampMillis = 3_000L))!!
        assertTrue("progress was ${next.progressFraction}", next.progressFraction > 0.15f)
    }

    @Test
    fun `a stale off-route fix does not count towards a reroute`() {
        val tracker = NavigationTracker(plan())
        val farAway = GeoPoint(42.5300, 1.5300)
        tracker.onLocation(LocationFix(farAway, accuracyMeters = 5f, timestampMillis = 1_000L))
        // Late duplicate of a fix already seen: it must not advance the off-route counter.
        assertNull(tracker.onLocation(LocationFix(farAway, accuracyMeters = 5f, timestampMillis = 500L)))
        assertFalse(
            "two accepted fixes are not enough",
            tracker.onLocation(LocationFix(farAway, accuracyMeters = 5f, timestampMillis = 2_000L))!!.offRoute,
        )
        assertTrue(
            "the third accepted fix should trigger a recalculation",
            tracker.onLocation(LocationFix(farAway, accuracyMeters = 5f, timestampMillis = 3_000L))!!.offRoute,
        )
    }

    @Test
    fun `a recalculation does not let an older fix through`() {
        val tracker = NavigationTracker(plan())
        tracker.onLocation(LocationFix(c, accuracyMeters = 5f, timestampMillis = 5_000L))
        tracker.updatePlan(plan())
        // The route changed, the clock did not: a fix from before the recalculation is still stale.
        assertNull(tracker.onLocation(LocationFix(a, accuracyMeters = 5f, timestampMillis = 4_000L)))
        val state = tracker.onLocation(LocationFix(a, accuracyMeters = 5f, timestampMillis = 6_000L))!!
        assertFalse(state.arrived)
        assertTrue(state.progressFraction < 0.1f)
    }

    @Test
    fun `arrival stays latched for the fixes that follow it`() {
        val tracker = NavigationTracker(plan())
        assertTrue(tracker.onLocation(LocationFix(c, accuracyMeters = 4f, timestampMillis = 1_000L))!!.arrived)
        // Past the destination and then back at the origin: the trip is over either way, so the state
        // the navigation loop reads on every later fix keeps saying so and the arrival branch cannot
        // be re-entered from a flag that re-armed itself.
        val past = GeoPoint(c.latitude + 0.0004, c.longitude)
        assertTrue(tracker.onLocation(LocationFix(past, accuracyMeters = 4f, timestampMillis = 2_000L))!!.arrived)
        assertTrue(tracker.onLocation(LocationFix(a, accuracyMeters = 4f, timestampMillis = 3_000L))!!.arrived)
    }

    @Test
    fun `snapped point and progress are the numbers the separate projection calls gave`() {
        // OPTIMIZATION.md J-19: the fix path now projects onto the matched segment in one pass
        // instead of asking for the along-track distance and the snapped point separately, so the
        // state has to carry the numbers those separate calls produced.
        val routePlan = plan()
        val tracker = NavigationTracker(routePlan)
        val fixes = listOf(
            GeoPoint(42.5000, 1.5050),
            GeoPoint(42.5000, 1.5100),
            GeoPoint(42.5030, 1.5100),
            GeoPoint(42.5060, 1.5100),
        )
        var checked = 0
        fixes.forEachIndexed { index, point ->
            val state = tracker.onLocation(
                LocationFix(point, accuracyMeters = 5f, timestampMillis = 1_000L * (index + 1)),
            )!!
            val geometry = routePlan.geometry
            val index0 = (0..geometry.size - 2).minByOrNull {
                GeoMath.crossTrackDistanceMeters(point, geometry[it], geometry[it + 1])
            }!!
            val start = geometry[index0]
            val end = geometry[index0 + 1]
            val segLength = GeoMath.distanceMeters(start, end)
            val along = GeoMath.alongTrackDistanceMeters(point, start, end).coerceIn(0.0, segLength)
            val expected = GeoMath.destination(start, along, GeoMath.bearingDegrees(start, end))
            assertEquals("snapped latitude at $point", expected.latitude, state.snappedPoint.latitude, 1e-9)
            assertEquals("snapped longitude at $point", expected.longitude, state.snappedPoint.longitude, 1e-9)
            assertEquals(
                "along-route distance at $point",
                routePlan.cumulativeMeters[index0] + along,
                state.alongRouteMeters,
                1e-6,
            )
            checked++
        }
        assertEquals("every fix must have been checked", fixes.size, checked)
    }

    @Test
    fun `a fix does not re-measure the segments of its follow window`() {
        // The window slides a segment at a time, so a session measures each segment of the route at
        // most once instead of measuring all forty-odd segments of the window on every fix
        // (OPTIMIZATION.md J-20).
        val tracker = NavigationTracker(plan())
        val segments = plan().geometry.size - 1
        for (i in 0..20) {
            tracker.onLocation(
                LocationFix(
                    GeoPoint(a.latitude, a.longitude + (b.longitude - a.longitude) * i / 20.0),
                    accuracyMeters = 5f,
                    timestampMillis = 1_000L + i,
                ),
            )
        }
        val afterWalking = tracker.segmentMeasurements
        assertTrue(
            "measured $afterWalking segments over 21 fixes, the plan has only $segments",
            afterWalking <= segments.toLong(),
        )
        // The next fix looks at the same window, so it adds nothing to the count.
        tracker.onLocation(
            LocationFix(GeoPoint(a.latitude, b.longitude), accuracyMeters = 5f, timestampMillis = 2_000L),
        )
        assertEquals(afterWalking, tracker.segmentMeasurements)
    }

    @Test
    fun `a fix projects onto the segment it has already measured`() {
        // The projection used to measure the matched segment's length and bearing again, though the
        // cross-track scan had just measured them for the same segment (OPTIMIZATION.md J-22). The
        // cache now answers both: the first fix asks for exactly one pair more than it measures,
        // because the projection asks the cache for the segment it matched - and measures nothing
        // new. The fix carries its own bearing so that the heading fallback, which reads the same
        // segment's bearing on purpose, does not add a second ask of its own.
        val fix = GeoPoint(42.5000, 1.5033)
        val tracker = NavigationTracker(plan())
        val state = tracker.onLocation(
            LocationFix(fix, accuracyMeters = 5f, bearingDegrees = 90f),
        )!!
        assertEquals(
            "the projection measured the matched segment a second time",
            tracker.segmentMeasurements + 1,
            tracker.segmentMeasureRequests,
        )
        // And the state carries the numbers the measuring call gives, not merely close ones.
        val geometry = plan().geometry
        val index0 = (0..geometry.size - 2).minByOrNull {
            GeoMath.crossTrackDistanceMeters(fix, geometry[it], geometry[it + 1])
        }!!
        val reference = GeoMath.projectOnSegment(fix, geometry[index0], geometry[index0 + 1])
        assertEquals("snapped point", reference.snappedPoint, state.snappedPoint)
        assertEquals(
            "along-route distance",
            plan().cumulativeMeters[index0] + reference.clampedAlongMeters,
            state.alongRouteMeters,
            1e-9,
        )
    }

    @Test
    fun `the next manoeuvre and the current street are what the two scans gave`() {
        // The fix path answers two questions about one boundary - the first manoeuvre ahead of the
        // fix and the last one behind it - and used to answer them with two separate walks of the
        // list (`indexOfFirst` and `lastOrNull`). One pass gives the same answers for a list in any
        // order, so the list here is deliberately not in distance order (OPTIMIZATION.md J-21).
        val distances = listOf(0.0, 640.0, 310.0, 1480.0, 990.0, 1200.0, 1480.0, 500.0)
        val maneuvers = distances.mapIndexed { i, d ->
            Maneuver(ManeuverType.CONTINUE, 0, "Street $i", 0.0, 0, 0, d, "Manoeuvre $i")
        }
        val plan = plan().copy(maneuvers = maneuvers)
        var checked = 0
        for (point in plan.geometry) {
            val state = NavigationTracker(plan)
                .onLocation(LocationFix(point, accuracyMeters = 5f)) ?: continue
            val passed = state.alongRouteMeters + 1.0
            val expectedNext = maneuvers.indexOfFirst { it.distanceFromStartMeters > passed }
                .let { if (it < 0) maneuvers.lastIndex else it }
            val expectedStreet = maneuvers.lastOrNull { it.distanceFromStartMeters <= passed }?.streetName
            assertEquals(
                "next manoeuvre at ${state.alongRouteMeters} m along the route",
                expectedNext,
                state.nextManeuverIndex,
            )
            assertEquals(
                "current street at ${state.alongRouteMeters} m along the route",
                expectedStreet,
                state.currentStreetName,
            )
            checked++
        }
        assertEquals("every geometry point must have been checked", plan.geometry.size, checked)
    }

    @Test
    fun `a fix walks the manoeuvre list once and takes no iterator from it`() {
        // Two walks became one, and the one is an index loop: nothing on the fix path may ask the
        // manoeuvre list for an iterator, which is what the two scans each did (OPTIMIZATION.md
        // J-21). The state is asserted too, so a loop that skipped the list cannot pass as cheap.
        val counting = CountingList(plan().maneuvers)
        val plan = plan().copy(maneuvers = counting)
        val state = NavigationTracker(plan)
            .onLocation(LocationFix(GeoPoint(42.5000, 1.5050), accuracyMeters = 5f))!!
        assertEquals("the fix path iterated the manoeuvres", 0, counting.iterators)
        assertEquals(1, state.nextManeuverIndex)
        assertEquals("Start street", state.currentStreetName)
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

/**
 * A list that counts how often it is walked through an [iterator], so a test can tell a single
 * index pass from a `for` loop or an `indexOfFirst`/`lastOrNull` over the same elements.
 */
private class CountingList<T>(private val backing: List<T>) : List<T> by backing {
    var iterators: Int = 0
        private set

    override fun iterator(): Iterator<T> {
        iterators++
        return backing.iterator()
    }
}

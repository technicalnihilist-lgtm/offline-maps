package com.offlinemaps.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    private val andorraLaVella = GeoPoint(42.5069, 1.5212)
    private val escaldes = GeoPoint(42.5090, 1.5340)

    @Test
    fun `haversine distance matches known value`() {
        val d = GeoMath.distanceMeters(andorraLaVella, escaldes)
        assertTrue("expected ~1000-1150 m, got $d", d in 950.0..1150.0)
    }

    @Test
    fun `distance to self is zero`() {
        assertEquals(0.0, GeoMath.distanceMeters(andorraLaVella, andorraLaVella), 0.0001)
    }

    @Test
    fun `bearing is north for a point due north`() {
        assertEquals(0.0, GeoMath.bearingDegrees(andorraLaVella, GeoPoint(43.0, 1.5212)), 0.5)
    }

    @Test
    fun `bearing is east for a point due east`() {
        assertEquals(90.0, GeoMath.bearingDegrees(andorraLaVella, GeoPoint(42.5069, 1.6)), 0.5)
    }

    @Test
    fun `angle delta wraps across north`() {
        assertEquals(10.0, GeoMath.angleDelta(355.0, 5.0), 0.001)
        assertEquals(-10.0, GeoMath.angleDelta(5.0, 355.0), 0.001)
    }

    @Test
    fun `cross track distance is near zero on the segment`() {
        val mid = GeoPoint(42.5080, 1.5270)
        assertTrue(GeoMath.crossTrackDistanceMeters(mid, andorraLaVella, escaldes) < 60.0)
    }

    @Test
    fun `cross track distance grows when off the line`() {
        val offset = GeoPoint(42.5180, 1.5270)
        assertTrue(GeoMath.crossTrackDistanceMeters(offset, andorraLaVella, escaldes) > 800.0)
    }

    @Test
    fun `cross track distance measures to the endpoint when past the end`() {
        val beyond = GeoPoint(42.5300, 1.5340)
        assertEquals(
            GeoMath.distanceMeters(beyond, escaldes),
            GeoMath.crossTrackDistanceMeters(beyond, andorraLaVella, escaldes),
            1.0,
        )
    }

    @Test
    fun `along track distance is negative behind the start`() {
        assertTrue(GeoMath.alongTrackDistanceMeters(GeoPoint(42.4900, 1.5212), andorraLaVella, escaldes) < 0)
    }

    @Test
    fun `destination round trips through distance and bearing`() {
        val moved = GeoMath.destination(andorraLaVella, 1000.0, 45.0)
        assertEquals(1000.0, GeoMath.distanceMeters(andorraLaVella, moved), 1.0)
        assertEquals(45.0, GeoMath.bearingDegrees(andorraLaVella, moved), 0.5)
    }

    @Test
    fun `path length sums segment distances`() {
        val points = listOf(andorraLaVella, escaldes, GeoPoint(42.5200, 1.5400))
        val expected = GeoMath.distanceMeters(points[0], points[1]) + GeoMath.distanceMeters(points[1], points[2])
        assertEquals(expected, GeoMath.pathLengthMeters(points), 0.001)
    }

    @Test
    fun `geo uri and osm link are well formed`() {
        val point = GeoPoint(42.5069, 1.5212)
        assertEquals("geo:42.506900,1.521200", point.toGeoUri())
        assertTrue(point.toOsmLink().startsWith("https://www.openstreetmap.org/?mlat=42.506900"))
    }

    /**
     * The cross-track distance as it was computed before OPTIMIZATION.md J-18: the along-track
     * projection from [GeoMath.alongTrackDistanceMeters], then the cross-track term rebuilt from the
     * same inputs. Kept here as the reference the single-pass version is measured against.
     */
    private fun longFormCrossTrack(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        val segLength = GeoMath.distanceMeters(start, end)
        if (segLength < 0.5) return GeoMath.distanceMeters(point, start)
        val along = GeoMath.alongTrackDistanceMeters(point, start, end)
        if (along < 0.0) return GeoMath.distanceMeters(point, start)
        if (along > segLength) return GeoMath.distanceMeters(point, end)
        val d13 = GeoMath.distanceMeters(start, point) / 6_371_008.8
        if (d13 == 0.0) return 0.0
        val dxt = Math.asin(
            (
                Math.sin(d13) * Math.sin(
                    (GeoMath.bearingDegrees(start, point) - GeoMath.bearingDegrees(start, end)) *
                        (Math.PI / 180.0),
                )
                ).coerceIn(-1.0, 1.0),
        )
        return Math.abs(dxt) * 6_371_008.8
    }

    @Test
    fun `cross track distance is the number the long form produced`() {
        // A single-pass call replaced two passes over the same inputs (OPTIMIZATION.md J-18), so the
        // answer has to be the one the two passes produced - not merely close to it.
        val random = java.util.Random(20261117L)
        var checked = 0
        repeat(500) {
            val start = GeoPoint(42.0 + random.nextDouble(), 1.0 + random.nextDouble())
            val end = GeoPoint(
                start.latitude + random.nextDouble() * 0.01 - 0.005,
                start.longitude + random.nextDouble() * 0.01 - 0.005,
            )
            val point = GeoPoint(
                start.latitude + random.nextDouble() * 0.02 - 0.01,
                start.longitude + random.nextDouble() * 0.02 - 0.01,
            )
            assertEquals(
                "cross-track distance for $point on $start-$end",
                longFormCrossTrack(point, start, end),
                GeoMath.crossTrackDistanceMeters(point, start, end),
                1e-9,
            )
            checked++
        }
        assertEquals("the loop must have run", 500, checked)
    }

    @Test
    fun `cross track distance handles a degenerate segment and a point on it`() {
        val elsewhere = GeoPoint(42.5180, 1.5270)
        // A segment of no length has no direction to project onto: the answer is the distance to it.
        assertEquals(
            GeoMath.distanceMeters(elsewhere, andorraLaVella),
            GeoMath.crossTrackDistanceMeters(elsewhere, andorraLaVella, andorraLaVella),
            1e-9,
        )
        // A point that is the segment's own start is on the segment.
        assertEquals(0.0, GeoMath.crossTrackDistanceMeters(andorraLaVella, andorraLaVella, escaldes), 1e-9)
        // Past the end, the answer is the distance to that end, not to the start.
        val beyond = GeoPoint(42.5300, 1.5340)
        assertEquals(
            GeoMath.distanceMeters(beyond, escaldes),
            GeoMath.crossTrackDistanceMeters(beyond, andorraLaVella, escaldes),
            1e-9,
        )
    }

    @Test
    fun `cross track distance from a segment's own measures is the same number`() {
        // The follow window and the grid scan measure each segment once and hand the pair back
        // (OPTIMIZATION.md J-20), so the overload that takes them has to answer exactly what the
        // call that measures them itself answers.
        val random = java.util.Random(20261120L)
        var checked = 0
        repeat(500) {
            val start = GeoPoint(42.0 + random.nextDouble(), 1.0 + random.nextDouble())
            val end = GeoPoint(
                start.latitude + random.nextDouble() * 0.01 - 0.005,
                start.longitude + random.nextDouble() * 0.01 - 0.005,
            )
            val point = GeoPoint(
                start.latitude + random.nextDouble() * 0.02 - 0.01,
                start.longitude + random.nextDouble() * 0.02 - 0.01,
            )
            assertEquals(
                "cross-track distance for $point on $start-$end",
                GeoMath.crossTrackDistanceMeters(point, start, end),
                GeoMath.crossTrackDistanceMeters(
                    point,
                    start,
                    end,
                    GeoMath.distanceMeters(start, end),
                    GeoMath.bearingDegrees(start, end),
                ),
                0.0,
            )
            checked++
        }
        assertEquals("the loop must have run", 500, checked)
    }

    @Test
    fun `a supplied bearing does not change the answer for a degenerate segment`() {
        // A segment shorter than half a metre has no direction to project onto, so the answer is the
        // distance to it whatever bearing the caller measured for it.
        val elsewhere = GeoPoint(42.5180, 1.5270)
        assertEquals(
            GeoMath.distanceMeters(elsewhere, andorraLaVella),
            GeoMath.crossTrackDistanceMeters(elsewhere, andorraLaVella, andorraLaVella, 0.0, 137.0),
            1e-9,
        )
        // A point that is the segment's own start is on the segment.
        assertEquals(
            0.0,
            GeoMath.crossTrackDistanceMeters(andorraLaVella, andorraLaVella, escaldes, 1_100.0, 90.0),
            1e-9,
        )
    }

    /**
     * The snapped point as `NavigationTracker` produced it before OPTIMIZATION.md J-19: the
     * along-track distance from [GeoMath.alongTrackDistanceMeters], clamped to the segment, advanced
     * from the start along the segment's bearing. The reference the single pass is measured against.
     */
    private fun longFormSnappedPoint(point: GeoPoint, start: GeoPoint, end: GeoPoint): GeoPoint {
        val segLength = GeoMath.distanceMeters(start, end)
        if (segLength < 0.5) return start
        val along = GeoMath.alongTrackDistanceMeters(point, start, end).coerceIn(0.0, segLength)
        return GeoMath.destination(start, along, GeoMath.bearingDegrees(start, end))
    }

    @Test
    fun `segment projection is the number the separate calls produced`() {
        // One pass now answers what two calls used to (OPTIMIZATION.md J-19), so both answers have to
        // be the ones the separate calls gave - not merely close to them.
        val random = java.util.Random(20261117L)
        var checked = 0
        repeat(500) {
            val start = GeoPoint(42.0 + random.nextDouble(), 1.0 + random.nextDouble())
            val end = GeoPoint(
                start.latitude + random.nextDouble() * 0.01 - 0.005,
                start.longitude + random.nextDouble() * 0.01 - 0.005,
            )
            val point = GeoPoint(
                start.latitude + random.nextDouble() * 0.02 - 0.01,
                start.longitude + random.nextDouble() * 0.02 - 0.01,
            )
            val projection = GeoMath.projectOnSegment(point, start, end)
            val segLength = GeoMath.distanceMeters(start, end)
            assertEquals(
                "segment length for $start-$end",
                segLength,
                projection.segmentLengthMeters,
                1e-12,
            )
            assertEquals(
                "along-track distance for $point on $start-$end",
                GeoMath.alongTrackDistanceMeters(point, start, end),
                projection.alongMeters,
                1e-9,
            )
            assertEquals(
                "clamped along-track distance for $point on $start-$end",
                GeoMath.alongTrackDistanceMeters(point, start, end).coerceIn(0.0, segLength),
                projection.clampedAlongMeters,
                1e-9,
            )
            val snapped = projection.snappedPoint
            val expected = longFormSnappedPoint(point, start, end)
            assertEquals("snapped latitude for $point on $start-$end", expected.latitude, snapped.latitude, 1e-9)
            assertEquals(
                "snapped longitude for $point on $start-$end",
                expected.longitude,
                snapped.longitude,
                1e-9,
            )
            checked++
        }
        assertEquals("the loop must have run", 500, checked)
    }

    @Test
    fun `segment projection handles a degenerate segment and a point on it`() {
        val elsewhere = GeoPoint(42.5180, 1.5270)
        // A segment of no length has no direction to advance along: the projection is its start.
        val degenerate = GeoMath.projectOnSegment(elsewhere, andorraLaVella, andorraLaVella)
        assertEquals(0.0, degenerate.alongMeters, 1e-12)
        assertEquals(0.0, degenerate.clampedAlongMeters, 1e-12)
        assertEquals(andorraLaVella, degenerate.snappedPoint)
        // A point that is the segment's own start projects to the start.
        val atStart = GeoMath.projectOnSegment(andorraLaVella, andorraLaVella, escaldes)
        assertEquals(0.0, atStart.alongMeters, 1e-12)
        assertEquals(0.0, atStart.clampedAlongMeters, 1e-12)
        // Past the end the projection is clamped to the end, not to the start.
        val beyond = GeoPoint(42.5300, 1.5340)
        val past = GeoMath.projectOnSegment(beyond, andorraLaVella, escaldes)
        assertTrue("along was ${past.alongMeters}", past.alongMeters > past.segmentLengthMeters)
        assertEquals(past.segmentLengthMeters, past.clampedAlongMeters, 1e-12)
        // Clamped to the end, the projection is the segment's end - not the start it came from.
        assertEquals(
            "a projection past the end snaps to the end",
            0.0,
            GeoMath.distanceMeters(past.snappedPoint, escaldes),
            0.01,
        )
        assertEquals(
            "a projection past the end does not snap back to the start",
            GeoMath.distanceMeters(beyond, escaldes),
            GeoMath.distanceMeters(past.snappedPoint, beyond),
            0.01,
        )
    }

    @Test
    fun `a supplied segment pair gives the projection the measuring call gives`() {
        // The caller that already holds a segment's length and bearing can hand them over instead of
        // having them measured a second time (OPTIMIZATION.md J-22), so the answer must be the one
        // the measuring call gives - to the last bit, not merely close to it.
        val random = java.util.Random(20261118L)
        var checked = 0
        repeat(500) {
            val start = GeoPoint(42.0 + random.nextDouble(), 1.0 + random.nextDouble())
            val end = GeoPoint(
                start.latitude + random.nextDouble() * 0.01 - 0.005,
                start.longitude + random.nextDouble() * 0.01 - 0.005,
            )
            val point = GeoPoint(
                start.latitude + random.nextDouble() * 0.02 - 0.01,
                start.longitude + random.nextDouble() * 0.02 - 0.01,
            )
            val supplied = GeoMath.projectOnSegment(
                point,
                start,
                end,
                GeoMath.distanceMeters(start, end),
                GeoMath.bearingDegrees(start, end),
            )
            val measured = GeoMath.projectOnSegment(point, start, end)
            assertEquals(
                "along-track distance for $point on $start-$end",
                measured.alongMeters,
                supplied.alongMeters,
                0.0,
            )
            assertEquals(
                "segment length for $start-$end",
                measured.segmentLengthMeters,
                supplied.segmentLengthMeters,
                0.0,
            )
            assertEquals(
                "snapped point for $point on $start-$end",
                measured.snappedPoint,
                supplied.snappedPoint,
            )
            checked++
        }
        assertEquals("the loop must have run", 500, checked)
    }

    @Test
    fun `a supplied pair cannot turn a degenerate segment into a direction`() {
        // A segment shorter than half a metre has no direction to advance along, and the caller's
        // pair does not give it one: the projection is still the segment's own start.
        val projection = GeoMath.projectOnSegment(
            GeoPoint(42.5180, 1.5270),
            andorraLaVella,
            andorraLaVella,
            GeoMath.distanceMeters(andorraLaVella, andorraLaVella),
            GeoMath.bearingDegrees(andorraLaVella, andorraLaVella),
        )
        assertEquals(0.0, projection.alongMeters, 1e-12)
        assertEquals(andorraLaVella, projection.snappedPoint)
    }

    @Test
    fun `bounds contain and expand correctly`() {
        val bounds = GeoBounds(42.42, 1.41, 42.66, 1.79)
        assertTrue(bounds.contains(andorraLaVella))
        assertTrue(!bounds.contains(GeoPoint(48.85, 2.35)))
        val expanded = bounds.expandedBy(1000.0)
        assertTrue(expanded.contains(andorraLaVella) && expanded.north > bounds.north)
    }
}

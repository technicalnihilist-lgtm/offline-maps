package com.offlinemaps.core.routing

import com.offlinemaps.core.common.GeoMath
import com.offlinemaps.core.common.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The per-segment length and bearing a route does not change are measured once and reused
 * (OPTIMIZATION.md J-20), so these tests pin two things: that the remembered numbers are the ones
 * the great-circle helpers give, and that a segment is measured exactly once however many fixes are
 * measured against it.
 */
class SegmentMeasuresTest {

    private val centre = GeoPoint(42.5, 1.5)

    /** An arc of [segments] segments, consecutive vertices ~15 m apart. */
    private fun geometry(segments: Int = 40): List<GeoPoint> =
        (0..segments).map { i -> GeoMath.destination(centre, 15.0 * i, 90.0) }

    @Test
    fun `nothing is measured until a segment is asked for`() {
        val measures = SegmentMeasures(geometry())
        assertEquals(0L, measures.measurements)
        measures.lengthMeters(3)
        assertEquals(1L, measures.measurements)
        measures.bearingDegrees(3)
        assertEquals("asking for the other measure of the same segment measures it again", 1L, measures.measurements)
    }

    @Test
    fun `a segment is measured once however many points are measured against it`() {
        val points = geometry()
        val measures = SegmentMeasures(points)
        val random = java.util.Random(20261120L)
        repeat(100) {
            val fix = GeoMath.destination(
                points[3],
                random.nextDouble() * 60.0,
                random.nextDouble() * 360.0,
            )
            assertEquals(
                "cross-track distance differs from the call that measures the segment itself",
                GeoMath.crossTrackDistanceMeters(fix, points[3], points[4]),
                measures.crossTrackDistanceMeters(fix, 3),
                0.0,
            )
        }
        assertEquals("the segment was measured more than once", 1L, measures.measurements)
    }

    @Test
    fun `the remembered length and bearing are the great-circle ones`() {
        val points = geometry()
        val measures = SegmentMeasures(points)
        for (i in 0 until points.size - 1) {
            assertEquals(
                "length of segment $i",
                GeoMath.distanceMeters(points[i], points[i + 1]),
                measures.lengthMeters(i),
                0.0,
            )
            assertEquals(
                "bearing of segment $i",
                GeoMath.bearingDegrees(points[i], points[i + 1]),
                measures.bearingDegrees(i),
                0.0,
            )
        }
        assertEquals("one measurement per segment", (points.size - 1).toLong(), measures.measurements)
    }

    @Test
    fun `projecting onto a segment reuses the pair the cross-track test took`() {
        // The fix path tests a fix against a segment and then projects onto the one it matched, so
        // the projection must read the pair the test already took rather than measure it again
        // (OPTIMIZATION.md J-22). Same order as the tracker: cross-track, then projection.
        val points = geometry()
        val measures = SegmentMeasures(points)
        val random = java.util.Random(20261121L)
        repeat(100) {
            val fix = GeoMath.destination(
                points[3],
                random.nextDouble() * 60.0,
                random.nextDouble() * 360.0,
            )
            measures.crossTrackDistanceMeters(fix, 3)
            val projection = measures.projectOnSegment(fix, 3)
            val expected = GeoMath.projectOnSegment(fix, points[3], points[4])
            assertEquals(
                "along-track distance differs from the call that measures the segment itself",
                expected.alongMeters,
                projection.alongMeters,
                0.0,
            )
            assertEquals(
                "snapped point differs from the call that measures the segment itself",
                expected.snappedPoint,
                projection.snappedPoint,
            )
        }
        assertEquals("the segment was measured more than once", 1L, measures.measurements)
        assertEquals(
            "one ask per call, so the projection asked for a pair already in hand",
            200L,
            measures.measureRequests,
        )
    }

    @Test
    fun `a scan of the whole geometry measures each segment exactly once`() {
        val points = geometry()
        val measures = SegmentMeasures(points)
        repeat(3) {
            for (i in 0 until points.size - 1) {
                measures.crossTrackDistanceMeters(centre, i)
            }
        }
        assertEquals("three passes over 40 segments", 40L, measures.measurements)
    }
}

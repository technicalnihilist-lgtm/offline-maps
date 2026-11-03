package com.offlinemaps.core.map

import com.offlinemaps.core.common.GeoMath
import com.offlinemaps.core.common.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route line is thinned before it is serialised, and the thinning must be invisible.
 *
 * Two things therefore matter more than the saving itself: the drawn line may not move by more than
 * the tolerance (checked here against the same great-circle cross-track distance the navigation code
 * uses, not against the planar shortcut the simplifier itself measures in), and the endpoints of the
 * route may never move at all - a route that stops short of the destination marker looks broken.
 */
class RouteLineSimplifierTest {

    /** Degrees of longitude at [latitude] covering [meters] of ground. */
    private fun lonOffset(latitude: Double, meters: Double): Double =
        meters / (6_371_008.8 * Math.cos(Math.toRadians(latitude))) * 180.0 / Math.PI

    /** A straight west-to-east line of [count] points, [spacingMeters] apart, at [latitude]. */
    private fun straightLine(count: Int, spacingMeters: Double = 5.0, latitude: Double = 0.0): List<GeoPoint> =
        (0 until count).map { GeoPoint(latitude, it * lonOffset(latitude, spacingMeters)) }

    /** A line with a lateral wiggle of [amplitudeMeters] over a [wavelengthMeters] stretch. */
    private fun wigglyLine(
        count: Int,
        spacingMeters: Double,
        amplitudeMeters: Double,
        wavelengthMeters: Double,
        latitude: Double = 0.0,
    ): List<GeoPoint> = (0 until count).map { i ->
        val along = i * spacingMeters
        val lateral = amplitudeMeters * Math.sin(2.0 * Math.PI * along / wavelengthMeters)
        GeoPoint(latitude + lateral / 111_320.0, along / (6_371_008.8 * Math.cos(Math.toRadians(latitude))) * 180.0 / Math.PI)
    }

    /** Greatest distance from any point of [original] to the polyline [drawn], in metres. */
    private fun maxDeviationMeters(original: List<GeoPoint>, drawn: List<GeoPoint>): Double {
        var worst = 0.0
        for (point in original) {
            var nearest = Double.MAX_VALUE
            for (i in 0 until drawn.size - 1) {
                val d = GeoMath.crossTrackDistanceMeters(point, drawn[i], drawn[i + 1])
                if (d < nearest) nearest = d
            }
            if (nearest > worst) worst = nearest
        }
        return worst
    }

    @Test
    fun `a route below the threshold is returned untouched`() {
        val points = straightLine(RouteLineSimplifier.MIN_POINTS_TO_SIMPLIFY)
        assertSame(points, RouteLineSimplifier.simplify(points))
    }

    @Test
    fun `a route one point above the threshold is considered`() {
        val points = straightLine(RouteLineSimplifier.MIN_POINTS_TO_SIMPLIFY + 1)
        val drawn = RouteLineSimplifier.simplify(points)
        assertEquals(2, drawn.size)
    }

    @Test
    fun `a straight line collapses to its two endpoints`() {
        val points = straightLine(2_000)
        val drawn = RouteLineSimplifier.simplify(points)
        assertEquals(2, drawn.size)
        assertSame(points.first(), drawn.first())
        assertSame(points.last(), drawn.last())
    }

    @Test
    fun `a corner is kept`() {
        // 1000 points east, then 1000 points north: the corner carries the shape.
        val count = 1_000
        val step = lonOffset(0.0, 5.0)
        val east = (0 until count).map { GeoPoint(0.0, it * step) }
        val north = (1..count).map { GeoPoint(it * 5.0 / 111_320.0, (count - 1) * step) }
        val points = east + north
        val drawn = RouteLineSimplifier.simplify(points)
        assertEquals(3, drawn.size)
        assertEquals(points[count - 1].latitude, drawn[1].latitude, 1e-9)
        assertEquals(points[count - 1].longitude, drawn[1].longitude, 1e-9)
    }

    @Test
    fun `a bend below the tolerance is dropped and one above it is kept`() {
        val step = lonOffset(0.0, 10.0)
        val count = 600
        fun bend(offsetMeters: Double): List<GeoPoint> = (0 until count).map { i ->
            GeoPoint(if (i == count / 2) offsetMeters / 111_320.0 else 0.0, i * step)
        }
        // 0.4 m off a 6 km straight run is under the tolerance, so the line is the straight run.
        assertEquals(2, RouteLineSimplifier.simplify(bend(0.4)).size)
        // 4 m is not: the apex survives, and so do the points where the two ramps to it bend away
        // from a straight chord by more than the tolerance.
        val bent = bend(4.0)
        val drawn = RouteLineSimplifier.simplify(bent)
        assertTrue("expected the apex to be kept, got ${drawn.size} points", drawn.size > 2)
        assertTrue("the apex must be drawn", drawn.any { it === bent[count / 2] })
    }

    @Test
    fun `the drawn line stays within the tolerance of the plan`() {
        val points = wigglyLine(count = 3_000, spacingMeters = 5.0, amplitudeMeters = 3.0, wavelengthMeters = 2_000.0)
        val drawn = RouteLineSimplifier.simplify(points)
        assertTrue("expected a real reduction, got ${drawn.size} of ${points.size}", drawn.size < points.size / 5)
        assertTrue(
            "deviation ${maxDeviationMeters(points, drawn)} m exceeds the tolerance",
            maxDeviationMeters(points, drawn) <= RouteLineSimplifier.DEFAULT_TOLERANCE_METERS + 1e-6,
        )
    }

    @Test
    fun `a zero tolerance keeps every point`() {
        val points = wigglyLine(count = 800, spacingMeters = 5.0, amplitudeMeters = 3.0, wavelengthMeters = 400.0)
        assertSame(points, RouteLineSimplifier.simplify(points, toleranceMeters = 0.0))
    }

    @Test
    fun `a non-finite tolerance is ignored rather than trusted`() {
        val points = straightLine(800)
        assertSame(points, RouteLineSimplifier.simplify(points, toleranceMeters = Double.NaN))
    }

    @Test
    fun `the result is an ordered subsequence of the input`() {
        val points = wigglyLine(count = 900, spacingMeters = 4.0, amplitudeMeters = 6.0, wavelengthMeters = 300.0)
        val drawn = RouteLineSimplifier.simplify(points, toleranceMeters = 0.5)
        var cursor = 0
        for (point in drawn) {
            val index = points.indexOfFirst { it === point }
            assertTrue("point at $index is out of order after $cursor", index >= cursor)
            cursor = index
        }
        assertEquals(points.size - 1, points.indexOfFirst { it === drawn.last() })
    }

    @Test
    fun `a route that crosses the antimeridian is not simplified`() {
        val points = (0 until 800).map { i ->
            val lon = 179.0 + i * 0.01
            GeoPoint(0.0, if (lon > 180.0) lon - 360.0 else lon)
        }
        assertSame(points, RouteLineSimplifier.simplify(points))
    }

    @Test
    fun `repeated points do not break the simplification`() {
        val points = ArrayList<GeoPoint>()
        val step = lonOffset(0.0, 5.0)
        for (i in 0 until 800) {
            val point = GeoPoint(0.0, i * step)
            points.add(point)
            if (i % 7 == 0) points.add(point)
        }
        val drawn = RouteLineSimplifier.simplify(points)
        assertEquals(2, drawn.size)
    }

    @Test
    fun `simplification is deterministic`() {
        val points = wigglyLine(count = 1_200, spacingMeters = 5.0, amplitudeMeters = 4.0, wavelengthMeters = 600.0)
        assertEquals(RouteLineSimplifier.simplify(points), RouteLineSimplifier.simplify(points))
    }

    @Test
    fun `a custom tolerance thins less than a loose one`() {
        val points = wigglyLine(count = 1_500, spacingMeters = 5.0, amplitudeMeters = 5.0, wavelengthMeters = 1_000.0)
        val tight = RouteLineSimplifier.simplify(points, toleranceMeters = 0.25).size
        val loose = RouteLineSimplifier.simplify(points, toleranceMeters = 8.0).size
        assertTrue("tight=$tight loose=$loose", tight > loose)
        assertTrue(loose >= 2)
    }
}

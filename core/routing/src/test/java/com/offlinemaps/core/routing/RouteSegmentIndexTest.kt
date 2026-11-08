package com.offlinemaps.core.routing

import com.offlinemaps.core.common.GeoMath
import com.offlinemaps.core.common.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The index replaces the full scan the tracker used to run when a fix fell outside its follow
 * window, so the one thing that must not change is the answer: these tests check it against the
 * scan itself, and pin how much work a fix costs.
 */
class RouteSegmentIndexTest {

    private val centre = GeoPoint(42.5, 1.5)

    /** A 63 km arc with [points] vertices, so consecutive segments are ~12 m apart. */
    private fun longRoute(points: Int = 5000): List<GeoPoint> =
        (0 until points).map { i ->
            GeoMath.destination(centre, 12_000.0, 300.0 * i / (points - 1))
        }

    /** The fallback the tracker used before the index: measure every segment of the route. */
    private fun scanDistance(geometry: List<GeoPoint>, point: GeoPoint): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until geometry.size - 1) {
            val d = GeoMath.crossTrackDistanceMeters(point, geometry[i], geometry[i + 1])
            if (d < best) best = d
        }
        return best
    }

    private fun fixAt(geometry: List<GeoPoint>, index: Int, meters: Double, bearing: Double): GeoPoint =
        GeoMath.destination(geometry[index], meters, bearing)

    @Test
    fun `agrees with a full scan along a 5000 point route`() {
        val geometry = longRoute()
        val index = RouteSegmentIndex(geometry)
        val random = Random(20261108)
        val offsets = doubleArrayOf(0.0, 4.0, 35.0, 120.0, 400.0, 900.0, 1500.0)
        var checked = 0
        repeat(250) {
            val at = random.nextInt(1, geometry.size - 2)
            val point = fixAt(geometry, at, offsets[random.nextInt(offsets.size)], random.nextDouble() * 360.0)
            val segment = index.nearestSegment(point)
            assertTrue("no segment found for a fix $point", segment >= 0)
            val measured = GeoMath.crossTrackDistanceMeters(
                point,
                geometry[segment],
                geometry[segment + 1],
            )
            assertEquals(
                "nearest distance differs from the full scan",
                scanDistance(geometry, point),
                measured,
                1e-6,
            )
            checked++
        }
        assertEquals(250, checked)
    }

    @Test
    fun `a fix on the route is answered from the cell it falls in`() {
        val geometry = longRoute()
        val index = RouteSegmentIndex(geometry)
        val random = Random(7)
        val perFix = ArrayList<Long>(200)
        repeat(200) {
            val at = random.nextInt(1, geometry.size - 2)
            val before = index.evaluations
            val segment = index.nearestSegment(geometry[at])
            assertTrue("no segment for a fix on the route", segment >= 0)
            perFix.add(index.evaluations - before)
        }
        val average = perFix.average()
        val worst = perFix.maxOrNull() ?: 0L
        println(
            "on-route fix: ${"%.1f".format(average)} segment tests on average, $worst at worst, " +
                "${geometry.size - 1} segments in the route",
        )
        assertTrue("average was $average", average < 300.0)
        assertTrue("worst was $worst", worst < 800L)
    }

    @Test
    fun `picks the nearer pass where the route runs beside itself`() {
        // Out and back along the same road, the return leg 60 m to the north.
        val out = (0..200).map { i -> GeoPoint(42.50000, 1.50000 + i * 0.00005) }
        val back = (200 downTo 0).map { i -> GeoPoint(42.50054, 1.50000 + i * 0.00005) }
        val geometry = out + back
        val index = RouteSegmentIndex(geometry)
        val onReturnLeg = GeoPoint(42.50056, 1.50500)
        val segment = index.nearestSegment(onReturnLeg)
        assertTrue("expected a segment of the return leg, got $segment", segment > 200)
        assertEquals(
            scanDistance(geometry, onReturnLeg),
            GeoMath.crossTrackDistanceMeters(
                onReturnLeg,
                geometry[segment],
                geometry[segment + 1],
            ),
            1e-6,
        )
    }

    @Test
    fun `fixes kilometres off the route are left to the caller`() {
        val geometry = longRoute()
        val index = RouteSegmentIndex(geometry)
        val far = fixAt(geometry, 2500, 5_000.0, 150.0)
        assertEquals(-1, index.nearestSegment(far))
    }

    @Test
    fun `handles geometries without usable segments`() {
        val point = GeoPoint(42.5, 1.5)
        assertEquals(-1, RouteSegmentIndex(emptyList()).nearestSegment(point))
        assertEquals(-1, RouteSegmentIndex(listOf(point)).nearestSegment(point))
        assertEquals(0, RouteSegmentIndex(listOf(point, point)).nearestSegment(point))
    }

    @Test
    fun `benchmark - 500 fixes along a 5000 point route`() {
        val geometry = longRoute()
        val buildStarted = System.nanoTime()
        val index = RouteSegmentIndex(geometry)
        val buildMillis = (System.nanoTime() - buildStarted) / 1_000_000
        val random = Random(99)
        val fixes = (0 until 500).map {
            val at = 1 + it * (geometry.size - 3) / 500
            fixAt(geometry, at, random.nextDouble() * 40.0, random.nextDouble() * 360.0)
        }
        val started = System.nanoTime()
        var checksum = 0L
        for (fix in fixes) checksum += index.nearestSegment(fix).coerceAtLeast(0).toLong()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        println(
            "index build $buildMillis ms, ${fixes.size} fixes in $elapsedMillis ms " +
                "(${index.evaluations} segment tests, ${index.occupiedCells} occupied cells, checksum $checksum)",
        )
        assertTrue("checksum $checksum", checksum > 0)
        assertTrue("${fixes.size} fixes took $elapsedMillis ms", elapsedMillis < 10_000)
        assertTrue(
            "evaluations ${index.evaluations}",
            index.evaluations < fixes.size.toLong() * 800L,
        )
    }
}

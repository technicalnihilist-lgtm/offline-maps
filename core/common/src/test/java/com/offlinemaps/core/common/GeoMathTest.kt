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

    @Test
    fun `bounds contain and expand correctly`() {
        val bounds = GeoBounds(42.42, 1.41, 42.66, 1.79)
        assertTrue(bounds.contains(andorraLaVella))
        assertTrue(!bounds.contains(GeoPoint(48.85, 2.35)))
        val expanded = bounds.expandedBy(1000.0)
        assertTrue(expanded.contains(andorraLaVella) && expanded.north > bounds.north)
    }
}

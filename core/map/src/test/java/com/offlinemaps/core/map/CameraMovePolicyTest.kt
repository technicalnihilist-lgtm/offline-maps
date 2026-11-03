package com.offlinemaps.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which animated camera moves are worth running.
 *
 * A move is only dropped when it lands within a few pixels of the current camera *and* changes
 * nothing else - a zoom, tilt or bearing change is always animated, because it is visible even when
 * the target has not moved. Dropping too little costs frames; dropping too much would leave the map
 * somewhere other than what the UI asked for, so every "not redundant" case here is as important as
 * the redundant ones.
 */
class CameraMovePolicyTest {

    /** Degrees of longitude at [latitude] covering [meters] of ground. */
    private fun lonOffset(latitude: Double, meters: Double): Double =
        meters / (6_371_008.8 * Math.cos(Math.toRadians(latitude))) * 180.0 / Math.PI

    private fun at(latitude: Double, longitude: Double, zoom: Double = 16.0) =
        CameraTarget(latitude = latitude, longitude = longitude, zoom = zoom)

    @Test
    fun `an identical camera is redundant`() {
        val here = at(12.9716, 77.5946, 15.5).copy(tilt = 45.0, bearing = 12.0)
        assertTrue(CameraMovePolicy.isRedundant(here, here.copy()))
    }

    @Test
    fun `a move of a few metres at city zoom is redundant`() {
        // ~3.4 px at zoom 16 on the equator.
        val from = at(0.0, 0.0)
        val to = at(0.0, lonOffset(0.0, 4.0))
        assertTrue(CameraMovePolicy.isRedundant(from, to))
    }

    @Test
    fun `a move of a street is not redundant`() {
        // ~8.4 px at zoom 16 on the equator.
        val from = at(0.0, 0.0)
        val to = at(0.0, lonOffset(0.0, 10.0))
        assertFalse(CameraMovePolicy.isRedundant(from, to))
    }

    @Test
    fun `the same metre offset is measured in pixels, so latitude matters`() {
        // ~6.7 px at latitude 60 because the Mercator projection stretches there.
        val from = at(60.0, 0.0)
        val to = at(60.0, lonOffset(60.0, 4.0))
        assertFalse(CameraMovePolicy.isRedundant(from, to))
    }

    @Test
    fun `the same metre offset at a deeper zoom is more pixels`() {
        val from = at(0.0, 0.0)
        val to = at(0.0, lonOffset(0.0, 5.0))
        assertTrue(CameraMovePolicy.isRedundant(from, to, thresholdPx = 8.0))
        assertFalse(CameraMovePolicy.isRedundant(from.copy(zoom = 18.0), to.copy(zoom = 18.0), thresholdPx = 8.0))
    }

    @Test
    fun `screen distance halves when the zoom goes up by one`() {
        val from = at(0.0, 0.0)
        val to = at(0.0, 0.01)
        val near = CameraMovePolicy.screenDistancePx(from, to, 12.0)
        val closer = CameraMovePolicy.screenDistancePx(from, to, 13.0)
        assertEquals(near / 2.0, closer, 1e-9)
    }

    @Test
    fun `a zoom change is never redundant`() {
        val here = at(12.9716, 77.5946, 15.0)
        assertFalse(CameraMovePolicy.isRedundant(here, here.copy(zoom = 15.5)))
    }

    @Test
    fun `a tilt change is never redundant`() {
        // The 2D/3D toggle keeps the target and the zoom and only pitches the camera.
        val here = at(12.9716, 77.5946).copy(tilt = 0.0)
        assertFalse(CameraMovePolicy.isRedundant(here, here.copy(tilt = 50.0)))
    }

    @Test
    fun `a bearing change is never redundant`() {
        val here = at(12.9716, 77.5946).copy(bearing = 0.0)
        assertFalse(CameraMovePolicy.isRedundant(here, here.copy(bearing = 90.0)))
    }

    @Test
    fun `a bearing that only differs by a full turn is redundant`() {
        val here = at(12.9716, 77.5946).copy(bearing = 0.0)
        assertTrue(CameraMovePolicy.isRedundant(here, here.copy(bearing = 359.99)))
    }

    @Test
    fun `a camera that cannot be compared is always a real move`() {
        val here = at(12.9716, 77.5946)
        assertFalse(CameraMovePolicy.isRedundant(here, here.copy(latitude = Double.NaN)))
        assertFalse(CameraMovePolicy.isRedundant(here.copy(zoom = Double.NaN), here))
        assertFalse(CameraMovePolicy.isRedundant(here, here.copy(zoom = Double.POSITIVE_INFINITY)))
    }

    @Test
    fun `the threshold is a pixel distance, not a fixed tolerance`() {
        val from = at(0.0, 0.0)
        val to = at(0.0, lonOffset(0.0, 10.0))
        assertFalse(CameraMovePolicy.isRedundant(from, to, thresholdPx = 4.0))
        assertTrue(CameraMovePolicy.isRedundant(from, to, thresholdPx = 20.0))
    }

    @Test
    fun `metres per pixel matches the slippy map at the equator`() {
        // 156543 m/px for a 256 px world tile at zoom 0 is the well-known figure; MapLibre's world
        // is 512 px wide, so half of it.
        assertEquals(78_271.5, CameraMovePolicy.metersPerPixel(0.0, 0.0), 0.1)
        assertEquals(78_271.5 / 4.0, CameraMovePolicy.metersPerPixel(0.0, 2.0), 0.1)
    }
}

package com.offlinemaps.core.offline

import com.offlinemaps.core.common.GeoBounds
import com.offlinemaps.core.common.TileMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileRegionEstimatorTest {

    private val andorra = GeoBounds(south = 42.42, west = 1.41, north = 42.66, east = 1.79)
    private val wholeWorld = GeoBounds(south = -85.0, west = -180.0, north = 85.0, east = 180.0)

    @Test
    fun `an ordinary region estimates the same as before the guards`() {
        // 6..14 is a valid range, so clamping must be a no-op for the shipped catalogue.
        val expected = (6..14).sumOf { z ->
            val tiles = TileMath.tileCount(andorra, z, z)
            tiles * (TileRegionEstimator.averageVectorTileBytes(z) + if (z >= 5) 50_000L else 0L)
        }
        assertEquals(expected, TileRegionEstimator.estimateBytes(andorra, 6, 14))
    }

    @Test
    fun `a degenerate zero-area region still costs at least one byte`() {
        val point = GeoBounds(south = 42.5, west = 1.5, north = 42.5, east = 1.5)
        assertTrue(TileRegionEstimator.estimateBytes(point, 6, 12) > 0L)
    }

    @Test
    fun `an inverted zoom range never reports zero bytes`() {
        val inverted = TileRegionEstimator.estimateBytes(andorra, 16, 8)
        assertTrue("inverted range must not read as free", inverted > 0L)
        // It collapses onto the upper bound, which is the conservative direction.
        assertEquals(TileRegionEstimator.estimateBytes(andorra, 8, 8), inverted)
    }

    @Test
    fun `zooms outside the slippy-map range are clamped`() {
        assertEquals(0..8, TileRegionEstimator.normalizeZoomRange(-4, 8))
        assertEquals(18..22, TileRegionEstimator.normalizeZoomRange(18, 40))
        assertEquals(22..22, TileRegionEstimator.normalizeZoomRange(30, 40))
        assertEquals(
            TileRegionEstimator.estimateBytes(andorra, 22, 22),
            TileRegionEstimator.estimateBytes(andorra, 30, 40),
        )
        assertTrue(TileRegionEstimator.estimateBytes(andorra, -4, 0) > 0L)
    }

    @Test
    fun `absurd estimates are clamped instead of overflowing`() {
        val world = TileRegionEstimator.estimateBytes(wholeWorld, 0, 22)
        assertEquals(TileRegionEstimator.MAX_ESTIMATE_BYTES, world)
        assertTrue(world > 0L)
        // Never negative, whatever the input.
        assertTrue(TileRegionEstimator.estimateBytes(wholeWorld, Int.MIN_VALUE, Int.MAX_VALUE) > 0L)
    }

    @Test
    fun `the estimate stays monotonic with zoom depth after clamping`() {
        val shallow = TileRegionEstimator.estimateBytes(andorra, 6, 12, includeTerrain = false)
        val deep = TileRegionEstimator.estimateBytes(andorra, 6, 16, includeTerrain = false)
        assertTrue(deep > shallow)
    }
}

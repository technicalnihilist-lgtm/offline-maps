package com.offlinemaps.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileMathTest {

    @Test
    fun `tile coordinates match the slippy map spec`() {
        assertEquals(0, TileMath.lonToTileX(0.0, 0))
        assertEquals(0, TileMath.latToTileY(0.0, 0))
        assertEquals(511, TileMath.lonToTileX(-0.1276, 10))
        assertEquals(340, TileMath.latToTileY(51.5072, 10))
    }

    @Test
    fun `tile bounds contain the originating point`() {
        val lat = 42.5069
        val lon = 1.5212
        val zoom = 14
        val bounds = TileMath.tileBounds(TileMath.lonToTileX(lon, zoom), TileMath.latToTileY(lat, zoom), zoom)
        assertTrue(lat in bounds.south..bounds.north)
        assertTrue(lon in bounds.west..bounds.east)
    }

    @Test
    fun `tile count grows fourfold per zoom level`() {
        val world = GeoBounds(-85.0, -180.0, 85.0, 180.0)
        assertEquals(1L, TileMath.tileCount(world, 0, 0))
        assertEquals(4L, TileMath.tileCount(world, 1, 1))
    }

    @Test
    fun `tile count for a small region is far below the world`() {
        val andorra = GeoBounds(42.42, 1.41, 42.66, 1.79)
        val count = TileMath.tileCount(andorra, 6, 14)
        assertTrue("expected a few hundred tiles, got $count", count in 200..5_000)
        assertTrue(count < TileMath.tileCountAbove(6, 14))
    }

    @Test
    fun `meters per pixel halves each zoom level`() {
        assertEquals(TileMath.metersPerPixel(42.5, 10) / 2.0, TileMath.metersPerPixel(42.5, 11), 0.0001)
    }

    @Test
    fun `zoom to fit returns a sane level for a country`() {
        val zoom = TileMath.zoomToFit(GeoBounds(6.55, 68.11, 35.51, 97.41))
        assertTrue("zoom $zoom should show a whole country", zoom in 3.0..6.5)
    }

    @Test
    fun `latitudes are clamped to the web mercator limit`() {
        assertEquals(0, TileMath.latToTileY(89.9, 3))
        assertEquals(7, TileMath.latToTileY(-89.9, 3))
    }
}

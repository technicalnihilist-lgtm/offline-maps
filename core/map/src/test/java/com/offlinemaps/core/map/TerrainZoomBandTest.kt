package com.offlinemaps.core.map

import com.offlinemaps.core.common.MapTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The zoom band the DEM source asks MapLibre for, not just its cap.
 *
 * `TerrainZoomClampTest` pins the *top* of the band: the cap comes from the config and is clamped to
 * the zoom the Terrarium set publishes (z15). This class pins the whole band, because the two ways to
 * get it wrong are opposite:
 *
 * * **Above the cap** MapLibre overzooms the tiles it already has, so no request is made for a zoom
 *   the set does not publish - that is why the cap must be clamped and not merely written through.
 * * **Below a floor** a `minzoom` on a `raster-dem` source does not suppress requests, it *multiplies*
 *   them: MapLibre renders zooms below `minzoom` by overzooming the tiles at `minzoom`, so a floor of
 *   z5 asked for at a world view is 16 z5 tiles where the pyramid would have served one z1 tile.
 *   A relief layer is therefore never made cheaper by giving it a floor, and the source must not have
 *   one - if a future change wants to stop fetching DEM at low zooms, the layer has to be dropped for
 *   those zooms, not the source re-based.
 *
 * Everything here is read off the generated document, so it holds for what MapLibre is actually
 * handed rather than for a constant in this module.
 */
class TerrainZoomBandTest {

    @Before
    @After
    fun resetCache() = MapStyleFactory.clearStyleCache()

    private fun json(options: StyleOptions): String = MapStyleFactory.buildJson(options)

    /** The `terrainSource` object only, so a `minzoom` on a layer cannot satisfy an assert. */
    private fun terrainSource(style: String): String {
        assertTrue("style must contain a terrain source", style.contains("\"terrainSource\""))
        return style.substringAfter("\"terrainSource\"").substringBefore("\"terrain\":")
    }

    /** The `maxzoom` written into an already-extracted DEM source, as MapLibre reads it. */
    private fun demMaxZoom(source: String): Int =
        Regex("\"maxzoom\":(\\d+)").find(source)?.groupValues?.get(1)?.toInt()
            ?: error("the DEM source must carry a maxzoom: $source")

    @Test
    fun `the dem band never reaches above the zoom the terrarium set publishes`() {
        for (zoom in -5..30) {
            val source = terrainSource(json(StyleOptions(showTerrain = true, maxTerrainZoom = zoom)))
            val written = demMaxZoom(source)
            assertTrue("cap $zoom was written as $written", written in 0..MapConfig.TERRAIN_MAX_ZOOM_CEILING)
            assertEquals("cap $zoom must be written as its clamped value", MapConfig.clampTerrainZoom(zoom), written)
        }
    }

    @Test
    fun `the dem source carries no minzoom floor`() {
        for (zoom in 0..MapConfig.TERRAIN_MAX_ZOOM_CEILING) {
            val source = terrainSource(json(StyleOptions(showTerrain = true, maxTerrainZoom = zoom)))
            assertFalse(
                "a raster-dem floor multiplies low-zoom requests instead of removing them: $source",
                source.contains("\"minzoom\""),
            )
        }
    }

    @Test
    fun `a lower cap shortens the band without adding a floor`() {
        val source = terrainSource(json(StyleOptions(showTerrain = true, maxTerrainZoom = 8)))

        assertEquals(8, demMaxZoom(source))
        assertFalse(source.contains("\"minzoom\""))
    }

    @Test
    fun `both themes ask for the same dem band`() {
        val light = terrainSource(json(StyleOptions(theme = MapTheme.LIGHT)))
        val dark = terrainSource(json(StyleOptions(theme = MapTheme.DARK)))

        assertEquals("the theme must not move the DEM band", light, dark)
        assertEquals(MapConfig.DEFAULT.terrainMaxZoom, demMaxZoom(light))
    }

    @Test
    fun `the vector source keeps its own cap while the dem band is clamped separately`() {
        val style = json(StyleOptions(showTerrain = true, config = MapConfig.DEFAULT.copy(maxZoom = 18, terrainMaxZoom = 18)))

        assertTrue("the vector source must keep its cap", style.contains("\"maxzoom\":18"))
        assertEquals(MapConfig.TERRAIN_MAX_ZOOM_CEILING, demMaxZoom(terrainSource(style)))
    }
}

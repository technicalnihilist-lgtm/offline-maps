package com.offlinemaps.core.map

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The DEM cap must be one configured number, inside the range the tiles cover.
 *
 * `MapConfig.terrainMaxZoom` used to be dead: the app hardcoded `maxTerrainZoom = 12` in the UI
 * state and the generator wrote whatever it was handed straight into the `raster-dem` source. A cap
 * above zoom 15 asks MapLibre for Terrarium tiles that do not exist (the set stops at z15), so a
 * style built from such a config would request 404s and overzoom nothing.
 *
 * These tests pin down the three parts of the fix: the value comes from the config, an override
 * still wins, and whatever is used is clamped to [MapConfig.TERRAIN_MAX_ZOOM_CEILING].
 */
class TerrainZoomClampTest {

    @Before
    @After
    fun resetCache() = MapStyleFactory.clearStyleCache()

    private fun json(options: StyleOptions): String = MapStyleFactory.buildJson(options)

    /** The `terrainSource` object only, so a `maxzoom` on the vector source cannot satisfy an assert. */
    private fun terrainSource(style: String): String {
        assertTrue("style must contain a terrain source", style.contains("\"terrainSource\""))
        return style.substringAfter("\"terrainSource\"").substringBefore("\"terrain\":")
    }

    @Test
    fun `the cap comes from the config when the style does not override it`() {
        val config = MapConfig.DEFAULT.copy(terrainMaxZoom = 9)
        val options = StyleOptions(showTerrain = true, config = config)

        assertEquals(9, options.effectiveTerrainMaxZoom)
        assertTrue(terrainSource(json(options)).contains("\"maxzoom\":9"))
    }

    @Test
    fun `the default config still emits the same cap as before`() {
        val options = StyleOptions(showTerrain = true)

        assertEquals(MapConfig.DEFAULT.terrainMaxZoom, options.effectiveTerrainMaxZoom)
        assertTrue(terrainSource(json(options)).contains("\"maxzoom\":${MapConfig.DEFAULT.terrainMaxZoom}"))
    }

    @Test
    fun `an explicit per-style cap wins over the config`() {
        val options = StyleOptions(
            showTerrain = true,
            maxTerrainZoom = 10,
            config = MapConfig.DEFAULT.copy(terrainMaxZoom = 8),
        )

        assertEquals(10, options.effectiveTerrainMaxZoom)
        assertTrue(terrainSource(json(options)).contains("\"maxzoom\":10"))
    }

    @Test
    fun `a configured cap above the terrarium ceiling is clamped in the style`() {
        val options = StyleOptions(
            showTerrain = true,
            config = MapConfig.DEFAULT.copy(terrainMaxZoom = 18),
        )

        assertEquals(MapConfig.TERRAIN_MAX_ZOOM_CEILING, options.effectiveTerrainMaxZoom)
        val source = terrainSource(json(options))
        assertTrue("clamped cap must be written", source.contains("\"maxzoom\":15"))
        assertFalse("the out-of-range value must not reach the style", source.contains("\"maxzoom\":18"))
    }

    @Test
    fun `an explicit cap above the ceiling is clamped too`() {
        val options = StyleOptions(showTerrain = true, maxTerrainZoom = 99)

        assertEquals(MapConfig.TERRAIN_MAX_ZOOM_CEILING, options.effectiveTerrainMaxZoom)
        assertTrue(terrainSource(json(options)).contains("\"maxzoom\":15"))
    }

    @Test
    fun `a negative cap is clamped to the floor rather than emitted`() {
        val options = StyleOptions(showTerrain = true, maxTerrainZoom = -4)

        assertEquals(MapConfig.TERRAIN_MAX_ZOOM_FLOOR, options.effectiveTerrainMaxZoom)
        val source = terrainSource(json(options))
        assertTrue(source.contains("\"maxzoom\":0"))
        assertFalse(source.contains("\"maxzoom\":-4"))
    }

    @Test
    fun `clamping never touches the vector source cap`() {
        val options = StyleOptions(
            showTerrain = true,
            config = MapConfig.DEFAULT.copy(maxZoom = 18, terrainMaxZoom = 18),
        )
        val style = json(options)

        assertTrue("vector source keeps its own cap", style.contains("\"maxzoom\":18"))
        assertTrue("DEM source is clamped", terrainSource(style).contains("\"maxzoom\":15"))
    }

    @Test
    fun `clamping is idempotent across the whole plausible range`() {
        for (zoom in -5..30) {
            val once = MapConfig.clampTerrainZoom(zoom)
            assertTrue("clamped value must be in range: $zoom", once in 0..15)
            assertEquals("clamping must be idempotent: $zoom", once, MapConfig.clampTerrainZoom(once))
        }
    }

    @Test
    fun `no terrain source is emitted when terrain is off, whatever the cap`() {
        val style = json(
            StyleOptions(
                showTerrain = false,
                config = MapConfig.DEFAULT.copy(terrainMaxZoom = 40),
            ),
        )

        assertFalse(style.contains("terrainSource"))
        assertFalse(style.contains("\"raster-dem\""))
    }
}

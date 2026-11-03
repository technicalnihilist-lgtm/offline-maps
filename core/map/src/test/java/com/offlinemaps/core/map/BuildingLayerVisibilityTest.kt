package com.offlinemaps.core.map

import com.offlinemaps.core.common.MapTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The 3D-building toggle must not need a style reload.
 *
 * It used to add or drop the `building-3d` layer from the generated document, so flipping it
 * rebuilt every layer, re-created the app's overlay sources and made MapLibre re-request the tiles
 * the style references. The layer is now always in the document and only its `layout.visibility`
 * depends on the flag, which the live style can change with one property write.
 *
 * These tests pin down the two halves of that: the layer is always present with the right
 * visibility, and the two documents differ in nothing else, so hiding the layer renders exactly
 * what omitting it rendered.
 */
class BuildingLayerVisibilityTest {

    @Before
    @After
    fun resetCache() = MapStyleFactory.clearStyleCache()

    private fun json(options: StyleOptions): String = MapStyleFactory.buildJson(options)

    @Test
    fun `the extruded layer is present whether or not 3D buildings are on`() {
        val on = json(StyleOptions(show3DBuildings = true))
        val off = json(StyleOptions(show3DBuildings = false))

        val id = """"id":"${MapStyleFactory.BUILDING_3D_LAYER_ID}""""
        assertTrue("3D buildings on: layer must be in the document", on.contains(id))
        assertTrue("3D buildings off: layer must still be in the document", off.contains(id))
    }

    @Test
    fun `the flag is expressed as layout visibility`() {
        val on = json(StyleOptions(show3DBuildings = true))
        val off = json(StyleOptions(show3DBuildings = false))

        assertTrue(on.contains(""""layout":{"visibility":"visible"}"""))
        assertTrue(off.contains(""""layout":{"visibility":"none"}"""))
    }

    @Test
    fun `the extruded layer keeps its type, source and minzoom`() {
        val off = json(StyleOptions(show3DBuildings = false, buildingsMinZoom = 15))

        assertTrue(
            off.contains(
                """"id":"building-3d","type":"fill-extrusion","source":"osm","source-layer":"building","minzoom":15""",
            ),
        )
        assertTrue(off.contains(""""fill-extrusion-opacity":0.92"""))
    }

    @Test
    fun `turning 3D buildings off changes nothing but the visibility token`() {
        for (theme in MapTheme.entries) {
            val on = json(StyleOptions(theme = theme, show3DBuildings = true))
            val off = json(StyleOptions(theme = theme, show3DBuildings = false))

            assertFalse("the documents must differ", on == off)
            assertEquals(
                "only the visibility value may differ",
                on,
                off.replace(""""visibility":"none"""", """"visibility":"visible""""),
            )
            assertEquals("none is three characters shorter than visible", on.length - 3, off.length)
        }
    }

    @Test
    fun `the flat building layer is unaffected by the toggle`() {
        val on = json(StyleOptions(show3DBuildings = true))
        val off = json(StyleOptions(show3DBuildings = false))

        val flat = """"id":"building-flat","type":"fill""""
        assertTrue(on.contains(flat))
        assertTrue(off.contains(flat))
    }

    @Test
    fun `the two states stay separately cached`() {
        val on = StyleOptions(show3DBuildings = true)
        val off = on.copy(show3DBuildings = false)

        json(on)
        json(off)
        json(on)
        json(off)

        assertEquals(2L, MapStyleFactory.styleCacheMisses())
        assertEquals(2L, MapStyleFactory.styleCacheHits())
        assertEquals(2, MapStyleFactory.cachedStyleCount())
    }
}

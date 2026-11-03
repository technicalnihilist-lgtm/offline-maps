package com.offlinemaps.core.map

import com.offlinemaps.core.common.MapTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which option changes are allowed to skip a style reload.
 *
 * Only the 3D-building flag is: the extruded layer is always in the document, so that change is a
 * layer-visibility write. Everything else (theme, terrain, min-zooms, tile server, attribution)
 * changes the document itself and must go through a reload - getting this wrong would leave the map
 * showing a style that does not match the settings.
 */
class StyleReloadPolicyTest {

    private val base = StyleOptions(theme = MapTheme.LIGHT, show3DBuildings = true, showTerrain = true)

    @Test
    fun `only the buildings flag changed`() {
        assertTrue(
            StyleReloadPolicy.isBuildingVisibilityOnlyChange(base, base.copy(show3DBuildings = false)),
        )
    }

    @Test
    fun `the same applies in the other direction`() {
        val off = base.copy(show3DBuildings = false)
        assertTrue(StyleReloadPolicy.isBuildingVisibilityOnlyChange(off, base))
    }

    @Test
    fun `nothing changed`() {
        assertFalse(StyleReloadPolicy.isBuildingVisibilityOnlyChange(base, base.copy()))
    }

    @Test
    fun `a buildings flag change alongside a theme change is not visibility only`() {
        assertFalse(
            StyleReloadPolicy.isBuildingVisibilityOnlyChange(
                base,
                base.copy(theme = MapTheme.DARK, show3DBuildings = false),
            ),
        )
    }

    @Test
    fun `a buildings flag change alongside a terrain change is not visibility only`() {
        assertFalse(
            StyleReloadPolicy.isBuildingVisibilityOnlyChange(
                base,
                base.copy(showTerrain = false, show3DBuildings = false),
            ),
        )
    }

    @Test
    fun `a buildings flag change alongside a min-zoom change is not visibility only`() {
        assertFalse(
            StyleReloadPolicy.isBuildingVisibilityOnlyChange(
                base,
                base.copy(buildingsMinZoom = 15, show3DBuildings = false),
            ),
        )
        assertFalse(
            StyleReloadPolicy.isBuildingVisibilityOnlyChange(
                base,
                base.copy(maxTerrainZoom = 10, show3DBuildings = false),
            ),
        )
    }

    @Test
    fun `a buildings flag change alongside a tile-server change is not visibility only`() {
        assertFalse(
            StyleReloadPolicy.isBuildingVisibilityOnlyChange(
                base,
                base.copy(
                    config = MapConfig.DEFAULT.copy(vectorTileUrlTemplate = "https://tiles.example/{z}/{x}/{y}.pbf"),
                    show3DBuildings = false,
                ),
            ),
        )
        assertFalse(
            StyleReloadPolicy.isBuildingVisibilityOnlyChange(
                base,
                base.copy(config = MapConfig.DEFAULT.copy(attribution = "other"), show3DBuildings = false),
            ),
        )
    }

    @Test
    fun `a change with the buildings flag untouched is not visibility only`() {
        assertFalse(
            StyleReloadPolicy.isBuildingVisibilityOnlyChange(base, base.copy(showTerrain = false)),
        )
    }
}

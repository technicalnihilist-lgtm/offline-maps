package com.offlinemaps.core.map

import com.offlinemaps.core.common.MapTheme

/**
 * Where map data comes from.
 *
 * Defaults point at OpenFreeMap's public vector tile service, which is free to use, needs no API
 * key, and serves OpenStreetMap data in the OpenMapTiles schema. Terrain uses the Mapzen/AWS
 * "Terrarium" elevation tiles (open data, hosted on AWS Open Data).
 *
 * Nothing here requires a paid key or an account. A user can point the app at their own tile
 * server, which is what self-hosters and offline-only deployments will do.
 */
data class MapConfig(
    val vectorTileUrlTemplate: String = "https://tiles.openfreemap.org/planet/{z}/{x}/{y}.pbf",
    val tileJsonUrl: String? = "https://tiles.openfreemap.org/planet",
    val terrainTileUrlTemplate: String = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png",
    val maxZoom: Int = 14,
    val terrainMaxZoom: Int = 12,
    val attribution: String = "© OpenStreetMap contributors · OpenFreeMap · Terrain: Mapzen/AWS Open Data",
    val styleUrl: String = "https://tiles.openfreemap.org/styles/liberty",
) {
    companion object {
        /**
         * Deepest zoom the Terrarium elevation set actually publishes. A `raster-dem` source capped
         * above this asks MapLibre for tiles that do not exist, so the cap is clamped to it.
         */
        const val TERRAIN_MAX_ZOOM_CEILING: Int = 15

        /** A DEM source below zoom 0 is meaningless; the floor keeps the emitted style valid. */
        const val TERRAIN_MAX_ZOOM_FLOOR: Int = 0

        val DEFAULT = MapConfig()

        /** The configured DEM cap brought inside the range the Terrarium tile set can serve. */
        fun clampTerrainZoom(zoom: Int): Int = zoom.coerceIn(TERRAIN_MAX_ZOOM_FLOOR, TERRAIN_MAX_ZOOM_CEILING)
    }
}

/** Everything the style generator needs for one theme. */
data class StyleOptions(
    val theme: MapTheme = MapTheme.LIGHT,
    val show3DBuildings: Boolean = true,
    val showTerrain: Boolean = true,
    val buildingsMinZoom: Int = 14,
    /**
     * Optional per-style override of the DEM cap. `null` (the normal case) means "take it from
     * [config]", so there is one place to configure it. Whatever is used is clamped to the range
     * the Terrarium tiles cover - see [effectiveTerrainMaxZoom].
     */
    val maxTerrainZoom: Int? = null,
    val config: MapConfig = MapConfig.DEFAULT,
) {
    /** The DEM `maxzoom` as it is written into the style: the configured value, clamped. */
    val effectiveTerrainMaxZoom: Int
        get() = MapConfig.clampTerrainZoom(maxTerrainZoom ?: config.terrainMaxZoom)
}

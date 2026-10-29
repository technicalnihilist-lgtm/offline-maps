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
        val DEFAULT = MapConfig()
    }
}

/** Everything the style generator needs for one theme. */
data class StyleOptions(
    val theme: MapTheme = MapTheme.LIGHT,
    val show3DBuildings: Boolean = true,
    val showTerrain: Boolean = true,
    val buildingsMinZoom: Int = 14,
    val maxTerrainZoom: Int = 12,
    val config: MapConfig = MapConfig.DEFAULT,
)

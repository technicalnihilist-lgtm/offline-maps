package com.offlinemaps.core.offline

import com.offlinemaps.core.common.GeoBounds
import com.offlinemaps.core.common.TileMath

/**
 * Estimates how large an offline tile region will be.
 *
 * This is an *estimate*, and the UI says so. It multiplies the tile count by a measured average
 * tile size per zoom band. Vector tiles are small at low zooms and grow with detail; terrain DEM
 * tiles are far larger because they are raster PNGs. Real sizes vary by region - dense cities cost
 * more per tile than empty desert - so we never present the number as exact.
 */
object TileRegionEstimator {

    private val VECTOR_TILE_BYTES = mapOf(
        0..5 to 3_500L,
        6..8 to 6_000L,
        9..11 to 11_000L,
        12..13 to 16_000L,
        14..15 to 21_000L,
        16..22 to 26_000L,
    )

    /** Terrarium terrain tiles are 256x256 PNGs, roughly 50 KB each. */
    private const val DEM_TILE_BYTES = 50_000L

    fun averageVectorTileBytes(zoom: Int): Long =
        VECTOR_TILE_BYTES.entries.firstOrNull { zoom in it.key }?.value ?: 20_000L

    fun estimateBytes(bounds: GeoBounds, minZoom: Int, maxZoom: Int, includeTerrain: Boolean = true): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val tiles = TileMath.tileCount(bounds, z, z)
            total += tiles * averageVectorTileBytes(z)
            if (includeTerrain && z >= 5) total += tiles * DEM_TILE_BYTES
        }
        return total
    }

    /** Human explanation of what a zoom range means, so coverage is never oversold. */
    fun describeZoom(maxZoom: Int): String = when {
        maxZoom <= 10 -> "Country and region outlines only - no street detail."
        maxZoom <= 12 -> "City outlines and major roads."
        maxZoom <= 14 -> "Street network, but building shapes may be missing."
        maxZoom <= 16 -> "Full street detail with most buildings."
        else -> "Maximum detail including small paths and building footprints."
    }
}

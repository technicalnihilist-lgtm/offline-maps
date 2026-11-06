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

    /**
     * No offline region a phone can actually hold costs more than this; a figure above it means the
     * input was bad (a whole-world bounding box at street zoom), not that the download is that big.
     * The estimate is clamped here rather than allowed to grow into a meaningless number.
     */
    const val MAX_ESTIMATE_BYTES: Long = 10L * 1024 * 1024 * 1024 * 1024 // 10 TiB

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

    /**
     * The zoom range the estimate actually covers.
     *
     * A zoom outside the slippy-map range, or an inverted range (`minZoom > maxZoom`), makes
     * [TileMath] iterate over nothing or over meaningless levels, which used to yield an estimate of
     * zero bytes for a region that is perfectly valid. Both are clamped into `0..[TileMath.MAX_ZOOM]`
     * here, and an inverted range collapses onto its upper bound - the conservative direction for a
     * storage check.
     */
    fun normalizeZoomRange(minZoom: Int, maxZoom: Int): IntRange {
        val high = maxZoom.coerceIn(0, TileMath.MAX_ZOOM)
        val low = minZoom.coerceIn(0, high)
        return low..high
    }

    fun estimateBytes(bounds: GeoBounds, minZoom: Int, maxZoom: Int, includeTerrain: Boolean = true): Long {
        var total = 0L
        for (z in normalizeZoomRange(minZoom, maxZoom)) {
            // A valid bounds always covers at least one tile; the floor keeps a degenerate or
            // clamped range from reporting a region as free.
            val tiles = TileMath.tileCount(bounds, z, z).coerceAtLeast(1L)
            val perTile = averageVectorTileBytes(z) + if (includeTerrain && z >= 5) DEM_TILE_BYTES else 0L
            total += tiles * perTile
            // Stop before the running total can get anywhere near overflowing a Long.
            if (total >= MAX_ESTIMATE_BYTES) return MAX_ESTIMATE_BYTES
        }
        return total.coerceIn(1L, MAX_ESTIMATE_BYTES)
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

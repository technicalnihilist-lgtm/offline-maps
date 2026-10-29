package com.offlinemaps.core.common

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** Slippy-map (XYZ) tile mathematics. Pure functions so tile accounting is testable. */
object TileMath {
    const val MAX_ZOOM = 22

    fun lonToTileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun latToTileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val latRad = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
        return y.coerceIn(0, n - 1)
    }

    fun tileXToLon(x: Int, zoom: Int): Double = x.toDouble() / (1 shl zoom) * 360.0 - 180.0

    fun tileYToLat(y: Int, zoom: Int): Double {
        val n = 1 shl zoom
        val t = PI - 2.0 * PI * y.toDouble() / n
        return 180.0 / PI * atan(sinh(t))
    }

    fun tileBounds(x: Int, y: Int, zoom: Int): GeoBounds = GeoBounds(
        south = tileYToLat(y + 1, zoom),
        west = tileXToLon(x, zoom),
        north = tileYToLat(y, zoom),
        east = tileXToLon(x + 1, zoom),
    )

    /** Inclusive tile range covering [bounds] at [zoom]. */
    fun tileRange(bounds: GeoBounds, zoom: Int): TileRange = TileRange(
        x0 = lonToTileX(bounds.west, zoom),
        x1 = lonToTileX(bounds.east, zoom),
        y0 = latToTileY(bounds.north, zoom),
        y1 = latToTileY(bounds.south, zoom),
    )

    /** Number of tiles needed to cover [bounds] for an inclusive zoom range. */
    fun tileCount(bounds: GeoBounds, minZoom: Int, maxZoom: Int): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val r = tileRange(bounds, z)
            total += (r.x1 - r.x0 + 1).toLong() * (r.y1 - r.y0 + 1).toLong()
        }
        return total
    }

    /** Rough resolution in metres/pixel at a given latitude and zoom, for a 256px tile. */
    fun metersPerPixel(lat: Double, zoom: Int): Double {
        val earth = 40_075_016.686
        return earth * cos(lat * PI / 180.0) / (256.0 * (1 shl zoom))
    }

    /** Zoom at which the whole [bounds] fits in the viewport (used for camera fitting). */
    fun zoomToFit(bounds: GeoBounds, viewportPx: Double = 1024.0): Double {
        val lonSpan = bounds.lonSpan.coerceAtLeast(1e-6)
        val latSpan = bounds.latSpan.coerceAtLeast(1e-6)
        val zLon = ln(360.0 / lonSpan * viewportPx / 256.0) / ln(2.0)
        val zLat = ln(180.0 / latSpan * viewportPx / 256.0) / ln(2.0)
        return minOf(zLon, zLat).coerceIn(0.0, MAX_ZOOM.toDouble())
    }

    fun worldTileCount(zoom: Int): Long = (1L shl zoom) * (1L shl zoom)

    fun tileCountAbove(minZoom: Int, maxZoom: Int): Long {
        var t = 0L
        for (z in minZoom..maxZoom) t += worldTileCount(z)
        return t
    }

    data class TileRange(val x0: Int, val x1: Int, val y0: Int, val y1: Int) {
        val count: Long get() = (x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong()
    }

    /** Cheap sanity check used when validating a user-drawn region before download. */
    fun estimateTileRequests(bounds: GeoBounds, minZoom: Int, maxZoom: Int): Long =
        tileCount(bounds, minZoom, maxZoom)
}

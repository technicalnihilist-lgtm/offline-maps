package com.offlinemaps.core.routing

import com.offlinemaps.core.common.GeoMath
import com.offlinemaps.core.common.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A bucketed spatial index over the segments of one route geometry.
 *
 * [NavigationTracker] normally follows the route from a small window around the last matched
 * segment, which costs the same per fix whatever the route length. That window is only enough while
 * the fix is near the last match; when it is not - a long tunnel, a missed exit, the first fix of a
 * session halfway along the route - the tracker has to find the nearest segment of the whole route.
 * The fallback for that used to be a scan of every segment, so a single such fix on a 5000-point
 * route cost 5000 cross-track calculations, and a driver who stays off the route pays that on every
 * fix.
 *
 * This index replaces that scan with a lookup. The segments are bucketed once per plan into a grid
 * of cells a few hundred metres across, and a query only visits the cells near the fix.
 *
 * The answer is exact, not approximate. The search radius grows from a few hundred metres, and each
 * round visits every cell that a segment within the radius could be in; the result is only returned
 * once the best segment found lies inside the radius it was found with, because then any segment
 * closer than it would have had to be inside that radius too. Two things make the rounds cheap:
 * cells whose distance to the fix is greater than the best segment found so far are skipped (a
 * lower bound on the distance to the cell cannot hide a better answer), and the last round is
 * capped at [MAX_SEARCH_METERS]. Beyond that cap the query returns -1 and the tracker falls back to
 * the full scan it has always done, so a fix that is kilometres off the route behaves exactly as
 * before. The route geometry is never modified.
 *
 * Not thread-safe: one instance belongs to one tracker and is used from the thread that feeds it
 * fixes. Building it is O(n) and it happens lazily, so a session that never leaves the follow window
 * never pays for it.
 */
internal class RouteSegmentIndex(private val geometry: List<GeoPoint>) {

    /** Number of segments (point pairs) in the geometry. */
    val segmentCount: Int = (geometry.size - 1).coerceAtLeast(0)

    /**
     * Cross-track calculations performed by [nearestSegment] since construction. Diagnostics only -
     * the benchmark test pins the work a fix costs with this instead of with timing.
     */
    var evaluations: Long = 0
        private set

    /** Number of cells holding at least one segment. Diagnostics only. */
    val occupiedCells: Int get() = cells.size

    /** Cell size in degrees; the grid is square in degrees, not in metres. */
    private val cellDegrees: Double

    private val minLat: Double
    private val minLon: Double
    private val rows: Int
    private val cols: Int

    /** Metres per degree in the spherical model [GeoMath] uses. */
    private val metersPerDegree = GeoMath.EARTH_RADIUS_M * DEGREES_TO_RADIANS

    /** Largest absolute latitude in the route, used for the metres-per-degree of longitude. */
    private val boxMaxAbsLat: Double

    /** Worst-case size of a cell, used to pick the first search radius. */
    private val cellDiagonalMeters: Double

    private val cells: HashMap<Long, IntArray>

    init {
        if (segmentCount == 0) {
            cellDegrees = MIN_CELL_DEGREES
            minLat = geometry.firstOrNull()?.latitude ?: 0.0
            minLon = geometry.firstOrNull()?.longitude ?: 0.0
            rows = 1
            cols = 1
            boxMaxAbsLat = 0.0
            cellDiagonalMeters = 0.0
            cells = HashMap()
        } else {
            var loLat = Double.MAX_VALUE
            var hiLat = -Double.MAX_VALUE
            var loLon = Double.MAX_VALUE
            var hiLon = -Double.MAX_VALUE
            var longestLat = 0.0
            var longestLon = 0.0
            for (i in 0..segmentCount) {
                val p = geometry[i]
                loLat = min(loLat, p.latitude)
                hiLat = max(hiLat, p.latitude)
                loLon = min(loLon, p.longitude)
                hiLon = max(hiLon, p.longitude)
                if (i < segmentCount) {
                    val q = geometry[i + 1]
                    longestLat = max(longestLat, abs(q.latitude - p.latitude))
                    longestLon = max(longestLon, abs(q.longitude - p.longitude))
                }
            }
            val spanLat = hiLat - loLat
            val spanLon = hiLon - loLon
            val span = max(spanLat, spanLon)
            // A cell is the span divided into a fixed number of columns, but never smaller than
            // ~275 m and never smaller than a quarter of the longest segment, so one segment cannot
            // straddle a large number of cells.
            var cell = max(span / GRID_AXIS_TARGET, MIN_CELL_DEGREES)
            cell = max(cell, max(longestLat, longestLon) / 4.0)
            if (span > 0.0 && span / cell > MAX_AXIS_CELLS) cell = span / MAX_AXIS_CELLS
            cellDegrees = cell
            minLat = loLat
            minLon = loLon
            rows = if (span > 0.0) (spanLat / cell).toInt() + 1 else 1
            cols = if (span > 0.0) (spanLon / cell).toInt() + 1 else 1
            boxMaxAbsLat = min(89.0, max(abs(loLat), abs(hiLat)))
            cellDiagonalMeters = hypot(
                cell * metersPerDegree,
                cell * metersPerDegree * cos(Math.toRadians(boxMaxAbsLat)),
            )
            cells = buildCells()
        }
    }

    /**
     * Index of the segment nearest to [point], exactly as a scan of the whole geometry would find
     * it, or -1 when the geometry has no segments or when the nearest one is further away than
     * [MAX_SEARCH_METERS].
     */
    fun nearestSegment(point: GeoPoint): Int {
        if (segmentCount == 0) return -1
        val cosLat = cosLatitude(point)
        var best = Hit.NONE
        var radius = min(max(START_RADIUS_METERS, 1.25 * cellDiagonalMeters), MAX_SEARCH_METERS)
        while (true) {
            best = scan(point, radius, cosLat, best)
            // Everything within `radius` of the fix has now been measured, so a best segment that
            // lies inside the radius cannot be beaten by anything that was skipped.
            if (best.index >= 0 && best.distanceMeters <= radius) return best.index
            if (radius >= MAX_SEARCH_METERS) return -1
            radius = min(radius * 4.0, MAX_SEARCH_METERS)
        }
    }

    private class Hit(val index: Int, val distanceMeters: Double) {
        companion object {
            val NONE = Hit(-1, Double.MAX_VALUE)
        }
    }

    private fun scan(point: GeoPoint, radius: Double, cosLat: Double, start: Hit): Hit {
        val dLat = radius / metersPerDegree
        val dLon = radius / (metersPerDegree * cosLat)
        val rowLo = rowOf(point.latitude - dLat)
        val rowHi = rowOf(point.latitude + dLat)
        val colLo = colOf(point.longitude - dLon)
        val colHi = colOf(point.longitude + dLon)
        var best = start
        for (row in rowLo..rowHi) {
            for (col in colLo..colHi) {
                val segments = cells[key(row, col)] ?: continue
                // A cell that cannot be closer than the best segment so far cannot hold a better
                // answer; skipping it is what keeps a fix that is a kilometre off the route cheap.
                if (lowerBoundMeters(point, row, col, cosLat) > min(radius, best.distanceMeters)) {
                    continue
                }
                for (segment in segments) {
                    evaluations++
                    val d = GeoMath.crossTrackDistanceMeters(
                        point,
                        geometry[segment],
                        geometry[segment + 1],
                    )
                    if (d < best.distanceMeters) best = Hit(segment, d)
                }
            }
        }
        return best
    }

    private fun buildCells(): HashMap<Long, IntArray> {
        val buckets = HashMap<Long, MutableList<Int>>()
        for (segment in 0 until segmentCount) {
            val a = geometry[segment]
            val b = geometry[segment + 1]
            val rowLo = rowOf(min(a.latitude, b.latitude))
            val rowHi = rowOf(max(a.latitude, b.latitude))
            val colLo = colOf(min(a.longitude, b.longitude))
            val colHi = colOf(max(a.longitude, b.longitude))
            if ((rowHi - rowLo + 1) * (colHi - colLo + 1) <= MAX_BBOX_CELLS) {
                for (row in rowLo..rowHi) {
                    for (col in colLo..colHi) {
                        buckets.getOrPut(key(row, col)) { ArrayList(2) }.add(segment)
                    }
                }
            } else {
                // A long segment: mark the cells it actually passes through, plus their neighbours,
                // which covers a cell it only clips at a corner.
                val span = max(abs(b.latitude - a.latitude), abs(b.longitude - a.longitude))
                val steps = max(1, (span / (cellDegrees / 2.0)).toInt() + 1)
                for (s in 0..steps) {
                    val t = s.toDouble() / steps
                    val row = rowOf(a.latitude + (b.latitude - a.latitude) * t)
                    val col = colOf(a.longitude + (b.longitude - a.longitude) * t)
                    for (r in max(0, row - 1)..min(rows - 1, row + 1)) {
                        for (c in max(0, col - 1)..min(cols - 1, col + 1)) {
                            buckets.getOrPut(key(r, c)) { ArrayList(2) }.add(segment)
                        }
                    }
                }
            }
        }
        val out = HashMap<Long, IntArray>(buckets.size * 2)
        for ((k, v) in buckets) out[k] = v.toIntArray()
        return out
    }

    private fun rowOf(latitude: Double): Int =
        ((latitude - minLat) / cellDegrees).toInt().coerceIn(0, rows - 1)

    private fun colOf(longitude: Double): Int =
        ((longitude - minLon) / cellDegrees).toInt().coerceIn(0, cols - 1)

    private fun key(row: Int, col: Int): Long =
        (row.toLong() shl 32) or (col.toLong() and 0xffffffffL)

    /**
     * Cosine of the largest latitude either the fix or the route reaches: the smallest number of
     * metres in a degree of longitude anywhere involved, so a distance built from it is never more
     * than the real one.
     */
    private fun cosLatitude(point: GeoPoint): Double =
        cos(Math.toRadians(min(89.0, max(boxMaxAbsLat, abs(point.latitude)))))

    /**
     * A distance that no point of the cell is closer to the fix than. The [BOUND_SAFETY] factor
     * absorbs the difference between the flat approximation used here and the great-circle distance
     * the tracker measures; over the few hundred metres this is compared against, that difference
     * is a fraction of a percent.
     */
    private fun lowerBoundMeters(point: GeoPoint, row: Int, col: Int, cosLat: Double): Double {
        val latLo = minLat + row * cellDegrees
        val lonLo = minLon + col * cellDegrees
        val dLat = max(0.0, max(latLo - point.latitude, point.latitude - (latLo + cellDegrees)))
        val dLon = max(0.0, max(lonLo - point.longitude, point.longitude - (lonLo + cellDegrees)))
        return hypot(dLat * metersPerDegree, dLon * metersPerDegree * cosLat) * BOUND_SAFETY
    }

    private companion object {
        const val DEGREES_TO_RADIANS = Math.PI / 180.0

        /** Smallest cell, ~275 m of latitude. */
        const val MIN_CELL_DEGREES = 0.0025

        /** Cells per axis for an ordinary route. */
        const val GRID_AXIS_TARGET = 256.0

        /** Hard cap on the grid, so a very long route does not make the cell walk expensive. */
        const val MAX_AXIS_CELLS = 512.0

        /** Segment bounding boxes up to this many cells are indexed cell by cell. */
        const val MAX_BBOX_CELLS = 16

        /** First search radius, comfortably more than the 250 m the tracker cares about. */
        const val START_RADIUS_METERS = 300.0

        /**
         * Furthest the index will search. Past this the fix is kilometres from the route, the
         * answer would need most of the route anyway, and the tracker's own scan is as good.
         */
        const val MAX_SEARCH_METERS = 2000.0

        /** Keeps the flat distance estimate under the great-circle one it is compared against. */
        const val BOUND_SAFETY = 0.9
    }
}

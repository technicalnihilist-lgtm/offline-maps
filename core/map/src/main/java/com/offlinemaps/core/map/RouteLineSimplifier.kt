package com.offlinemaps.core.map

import com.offlinemaps.core.common.GeoPoint
import kotlin.math.cos

/**
 * Douglas–Peucker simplification for the *drawn* route line.
 *
 * A routing plan carries a point every few metres: a long intercity route is easily a few thousand
 * coordinates, and every one of them is formatted into a string and handed to MapLibre on the main
 * thread when the route is pushed to its GeoJSON source. Most of those points carry no information
 * on screen - a straight stretch of motorway needs two points, not two hundred - so the line can be
 * thinned without the drawn shape changing: every dropped point lies within [toleranceMeters] of the
 * line that is drawn in its place, which at the zooms this app draws a route (vector tiles stop at
 * zoom 14, so roughly 2.4 m per pixel on the equator at 14) is a fraction of a pixel.
 *
 * Only the geometry that is *drawn* is simplified. The plan's own `geometry` list - which the
 * cumulative distances, the manoeuvre indices and the navigation tracker in `core:routing` all index
 * into - is never touched, so progress, off-route detection and instructions keep working against
 * the full-resolution line. The caller keeps passing the original list as the payload key,
 * so an unchanged plan is still recognised as unchanged and nothing is re-serialised.
 *
 * The projection is a local equirectangular one around the route's middle latitude: over the extent
 * of a single route the difference from the spheroid is far below the tolerance, and it costs a
 * multiply per point instead of the trigonometry a great-circle cross-track distance would need on
 * every candidate of every recursion level.
 */
object RouteLineSimplifier {

    /** Ground distance a dropped point may lie from the drawn line. Well under a pixel at route zoom. */
    const val DEFAULT_TOLERANCE_METERS = 1.0

    /**
     * Routes shorter than this are drawn as they are. Below it the saving is a few dozen
     * coordinates, which is not worth a pass over the list on the main thread.
     */
    const val MIN_POINTS_TO_SIMPLIFY = 512

    /** Metres per degree of latitude; the same constant [com.offlinemaps.core.common.GeoBounds] uses. */
    private const val METERS_PER_DEGREE = 111_320.0

    /**
     * Returns the points to draw for [points].
     *
     * The first and last points are always kept, and kept as the caller's own objects; the result is
     * a subsequence of the input in the original order. The input is returned unchanged (the same
     * list instance) when it is short enough, when [toleranceMeters] is not a positive finite
     * number, or when it spans the antimeridian - a case the planar projection here cannot express.
     */
    fun simplify(
        points: List<GeoPoint>,
        toleranceMeters: Double = DEFAULT_TOLERANCE_METERS,
        minPoints: Int = MIN_POINTS_TO_SIMPLIFY,
    ): List<GeoPoint> {
        if (points.size <= minPoints.coerceAtLeast(2)) return points
        if (!toleranceMeters.isFinite() || toleranceMeters <= 0.0) return points

        val n = points.size
        val lat0 = points[n / 2].latitude
        val lon0 = points[n / 2].longitude
        val metresPerDegreeLon = METERS_PER_DEGREE * cos(Math.toRadians(lat0)).coerceAtLeast(1e-6)

        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (i in 0 until n) {
            val point = points[i]
            if (point.longitude < minLon) minLon = point.longitude
            if (point.longitude > maxLon) maxLon = point.longitude
            xs[i] = (point.longitude - lon0) * metresPerDegreeLon
            ys[i] = (point.latitude - lat0) * METERS_PER_DEGREE
        }
        // A route that crosses the antimeridian has a longitude span of nearly 360 degrees and
        // cannot be flattened this way; draw it exactly as planned.
        if (maxLon - minLon > 180.0) return points

        val toleranceSq = toleranceMeters * toleranceMeters
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true

        // Iterative rather than recursive: a few thousand points on a stack is nothing, a deep
        // recursion on the main thread is a crash waiting for a pathological route.
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val range = stack.removeLast()
            val first = range[0]
            val last = range[1]
            if (last <= first + 1) continue
            var worstIndex = -1
            var worstDistSq = 0.0
            for (i in first + 1 until last) {
                val distSq = distanceToSegmentSq(xs[i], ys[i], xs[first], ys[first], xs[last], ys[last])
                if (distSq > worstDistSq) {
                    worstDistSq = distSq
                    worstIndex = i
                }
            }
            if (worstIndex > 0 && worstDistSq > toleranceSq) {
                keep[worstIndex] = true
                stack.addLast(intArrayOf(first, worstIndex))
                stack.addLast(intArrayOf(worstIndex, last))
            }
        }

        val drawn = ArrayList<GeoPoint>(n)
        for (i in 0 until n) if (keep[i]) drawn.add(points[i])
        // Two distinct endpoints always survive, so this only guards against a caller passing a
        // list whose ends are the same point.
        return if (drawn.size >= 2) drawn else points
    }

    /**
     * Squared distance from ([px], [py]) to the segment ([ax], [ay])–([bx], [by]) in the projected
     * plane. Squared, because the comparison is against a squared tolerance and a square root per
     * candidate point would be the most expensive thing in the loop.
     *
     * A point beyond either end of the segment is measured to that end, which is what keeps a
     * hairpin turn from being straightened away.
     */
    private fun distanceToSegmentSq(
        px: Double,
        py: Double,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy
        if (lengthSq <= 0.0) {
            val ex = px - ax
            val ey = py - ay
            return ex * ex + ey * ey
        }
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
        val ex = px - (ax + t * dx)
        val ey = py - (ay + t * dy)
        return ex * ex + ey * ey
    }
}

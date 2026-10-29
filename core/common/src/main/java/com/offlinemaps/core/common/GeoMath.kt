package com.offlinemaps.core.common

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Spherical-earth geometry helpers. Pure functions, no Android dependencies. */
object GeoMath {
    const val EARTH_RADIUS_M = 6_371_008.8
    private const val DEG = Math.PI / 180.0

    /** Great-circle distance in metres (haversine). */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = (b.latitude - a.latitude) * DEG
        val dLon = (b.longitude - a.longitude) * DEG
        val lat1 = a.latitude * DEG
        val lat2 = b.latitude * DEG
        val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h).coerceIn(0.0, 1.0))
    }

    /** Initial bearing from [a] to [b] in degrees, clockwise from true north (0..360). */
    fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = a.latitude * DEG
        val lat2 = b.latitude * DEG
        val dLon = (b.longitude - a.longitude) * DEG
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (atan2(y, x) / DEG + 360.0) % 360.0
    }

    /** Signed shortest angular difference [to] - [from], normalised to (-180, 180]. */
    fun angleDelta(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /**
     * Distance in metres along the great circle [start]-[end] at which [point] projects.
     * Negative when the projection falls behind [start]; larger than the segment length when it
     * falls past [end].
     */
    fun alongTrackDistanceMeters(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        val d12 = distanceMeters(start, end)
        if (d12 < 0.5) return 0.0
        val d13 = distanceMeters(start, point) / EARTH_RADIUS_M
        if (d13 == 0.0) return 0.0
        val theta13 = bearingDegrees(start, point)
        val theta12 = bearingDegrees(start, end)
        val dxt = asin((sin(d13) * sin((theta13 - theta12) * DEG)).coerceIn(-1.0, 1.0))
        val dat = acos((cos(d13) / cos(dxt)).coerceIn(-1.0, 1.0)) * EARTH_RADIUS_M
        return if (abs(angleDelta(theta12, theta13)) > 90.0) -dat else dat
    }

    /**
     * Perpendicular distance in metres from [point] to the segment [start]-[end]. Points that
     * project outside the segment are measured to the nearer endpoint. This is the primitive
     * behind off-route detection.
     */
    fun crossTrackDistanceMeters(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        val segLength = distanceMeters(start, end)
        if (segLength < 0.5) return distanceMeters(point, start)
        val along = alongTrackDistanceMeters(point, start, end)
        if (along < 0.0) return distanceMeters(point, start)
        if (along > segLength) return distanceMeters(point, end)
        val d13 = distanceMeters(start, point) / EARTH_RADIUS_M
        if (d13 == 0.0) return 0.0
        val dxt = asin(
            (sin(d13) * sin((bearingDegrees(start, point) - bearingDegrees(start, end)) * DEG))
                .coerceIn(-1.0, 1.0),
        )
        return abs(dxt) * EARTH_RADIUS_M
    }

    /** Point at [distance] metres and [bearing] degrees from [origin]. */
    fun destination(origin: GeoPoint, distanceMeters: Double, bearingDegrees: Double): GeoPoint {
        val d = distanceMeters / EARTH_RADIUS_M
        val t = bearingDegrees * DEG
        val lat1 = origin.latitude * DEG
        val lon1 = origin.longitude * DEG
        val lat2 = asin((sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(t)).coerceIn(-1.0, 1.0))
        val lon2 = lon1 + atan2(sin(t) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return GeoPoint(latitude = lat2 / DEG, longitude = ((lon2 / DEG + 540.0) % 360.0) - 180.0)
    }

    /** Total length in metres of a polyline. */
    fun pathLengthMeters(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) total += distanceMeters(points[i - 1], points[i])
        return total
    }
}

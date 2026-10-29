package com.offlinemaps.core.common

import java.util.Locale

/**
 * An immutable WGS84 coordinate. Latitude/longitude in degrees, optional altitude in metres.
 *
 * Kept as a plain value class rather than a map SDK type so every piece of geometry logic in the
 * app can be unit-tested on the JVM without an emulator.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }

    fun isValid(): Boolean = latitude in -90.0..90.0 && longitude in -180.0..180.0

    /** Human readable coordinate string, e.g. "18.520430, 73.856744". */
    fun format(decimals: Int = 6): String = String.format(
        Locale.US, "%.${decimals}f, %.${decimals}f", latitude, longitude,
    )

    /** Geo URI per RFC 5870 - understood by most map apps for sharing a destination. */
    fun toGeoUri(): String {
        val alt = altitude?.let { String.format(Locale.US, ",%.1f", it) } ?: ""
        return String.format(Locale.US, "geo:%.6f,%.6f%s", latitude, longitude, alt)
    }

    /** OpenStreetMap link - no API key, no tracking, works in any browser. */
    fun toOsmLink(zoom: Int = 17): String = String.format(
        Locale.US,
        "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=%d/%.6f/%.6f",
        latitude, longitude, zoom, latitude, longitude,
    )

    companion object {
        val ZERO = GeoPoint(0.0, 0.0)
    }
}

/** Bounding box in degrees. [south] <= [north]; antimeridian-crossing boxes are not represented. */
data class GeoBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    init {
        require(south <= north) { "south ($south) must be <= north ($north)" }
        require(west <= east) { "west ($west) must be <= east ($east) - split antimeridian bounds" }
    }

    val center: GeoPoint get() = GeoPoint((south + north) / 2.0, (west + east) / 2.0)
    val latSpan: Double get() = north - south
    val lonSpan: Double get() = east - west

    fun contains(p: GeoPoint): Boolean = p.latitude in south..north && p.longitude in west..east

    fun intersects(other: GeoBounds): Boolean =
        !(other.west > east || other.east < west || other.south > north || other.north < south)

    fun expandedBy(meters: Double): GeoBounds {
        val dLat = meters / 111_320.0
        val cosLat = kotlin.math.cos(Math.toRadians(center.latitude)).coerceAtLeast(1e-6)
        val dLon = meters / (111_320.0 * cosLat)
        return GeoBounds(
            south = (south - dLat).coerceAtLeast(-90.0),
            west = (west - dLon).coerceAtLeast(-180.0),
            north = (north + dLat).coerceAtMost(90.0),
            east = (east + dLon).coerceAtMost(180.0),
        )
    }

    companion object {
        val WORLD = GeoBounds(-85.05112878, -180.0, 85.05112878, 180.0)

        fun of(points: Iterable<GeoPoint>): GeoBounds? {
            val it = points.iterator()
            if (!it.hasNext()) return null
            var s = Double.MAX_VALUE
            var w = Double.MAX_VALUE
            var n = -Double.MAX_VALUE
            var e = -Double.MAX_VALUE
            it.forEach { p ->
                if (p.latitude < s) s = p.latitude
                if (p.latitude > n) n = p.latitude
                if (p.longitude < w) w = p.longitude
                if (p.longitude > e) e = p.longitude
            }
            return GeoBounds(s, w, n, e)
        }
    }
}

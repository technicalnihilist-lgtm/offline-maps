package com.offlinemaps.core.common

/**
 * A single position update. Lives in the common module because both the location source and the
 * navigation engine consume it, and neither should depend on the other.
 */
data class LocationFix(
    val point: GeoPoint,
    val accuracyMeters: Float? = null,
    val bearingDegrees: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
)

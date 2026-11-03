package com.offlinemaps.core.map

import com.offlinemaps.core.common.GeoMath
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.tan

/**
 * A camera position the app asks the map to move to, or one the map is already at.
 *
 * Deliberately a plain value: the policy below has to be testable without a map, a renderer or an
 * Android context, and the MapLibre types (`CameraPosition`, `LatLng`) are neither.
 */
data class CameraTarget(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val tilt: Double = 0.0,
    val bearing: Double = 0.0,
)

/**
 * Decides whether an animated camera move would visibly do anything.
 *
 * Every animated move runs an animation on the renderer and, on a low-end device, spends a few
 * hundred milliseconds of frames to interpolate between two positions that are the same pixel on
 * screen. That happens routinely: a repeated tap on "my location" while already centred, a
 * settings-driven re-sync, a re-selection of the place that is already in view. Such a move can be
 * dropped without the user seeing any difference, which is what [isRedundant] reports.
 *
 * The comparison is in screen pixels, not metres, because "a few pixels" is what the eye can see
 * and what the item asks for: the same metre offset is invisible at zoom 12 and obvious at zoom 19.
 * Pixels are computed in Web Mercator at the current zoom, using MapLibre's 512 px world tile, and
 * through the Mercator projection rather than a flat metre-to-pixel factor, so a move at a high
 * latitude is measured the way it is drawn (the projection stretches with latitude).
 *
 * A near-identical position is not enough on its own: zoom, tilt and bearing are part of the camera
 * too, so a 2D/3D switch (tilt), a pinch (zoom) or a compass reset (bearing) always animates, even
 * when the target has not moved at all.
 */
object CameraMovePolicy {

    /** Screen distance under which a move is considered invisible. */
    const val DEFAULT_PIXEL_THRESHOLD = 4.0

    /** Zoom difference under which two cameras are considered to be at the same zoom. */
    const val ZOOM_EPSILON = 0.01

    /** Tilt and bearing difference, in degrees, under which the orientation is unchanged. */
    const val ANGLE_EPSILON = 0.5

    /** Width in pixels of the whole world at zoom 0, i.e. MapLibre's 512 px tile. */
    private const val WORLD_PIXELS_AT_ZOOM_0 = 512.0

    /**
     * True when moving to [requested] from [current] cannot produce a visible frame change, so the
     * animation can be skipped. [thresholdPx] is the tolerated on-screen distance.
     *
     * Anything that cannot be compared (a non-finite coordinate, zoom or angle) is reported as a
     * real move: the policy must never swallow a move it does not understand.
     */
    fun isRedundant(
        current: CameraTarget,
        requested: CameraTarget,
        thresholdPx: Double = DEFAULT_PIXEL_THRESHOLD,
    ): Boolean {
        if (!current.isFinite() || !requested.isFinite()) return false
        if (abs(current.zoom - requested.zoom) > ZOOM_EPSILON) return false
        if (abs(current.tilt - requested.tilt) > ANGLE_EPSILON) return false
        if (abs(GeoMath.angleDelta(current.bearing, requested.bearing)) > ANGLE_EPSILON) return false
        return screenDistancePx(current, requested, current.zoom) <= thresholdPx
    }

    /**
     * Distance in screen pixels between the targets of [from] and [to] at [zoom], through the
     * Mercator projection the map draws with. Independent of tilt and bearing.
     */
    fun screenDistancePx(from: CameraTarget, to: CameraTarget, zoom: Double): Double {
        val world = WORLD_PIXELS_AT_ZOOM_0 * Math.pow(2.0, zoom)
        val dx = mercatorX(to.longitude, world) - mercatorX(from.longitude, world)
        val dy = mercatorY(to.latitude, world) - mercatorY(from.latitude, world)
        return hypot(dx, dy)
    }

    /** Metres covered by one screen pixel at [latitude] and [zoom], for reference and logging. */
    fun metersPerPixel(latitude: Double, zoom: Double): Double =
        (40_075_016.686 * cos(Math.toRadians(latitude))) /
            (WORLD_PIXELS_AT_ZOOM_0 * Math.pow(2.0, zoom))

    private fun mercatorX(longitude: Double, world: Double): Double = (longitude + 180.0) / 360.0 * world

    private fun mercatorY(latitude: Double, world: Double): Double {
        // Clamped to the drawable latitude range, as the projection is undefined at the poles.
        val latRad = Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))
        val mercator = ln(tan(latRad) + 1.0 / cos(latRad))
        return (1.0 - mercator / Math.PI) / 2.0 * world
    }

    private fun CameraTarget.isFinite(): Boolean =
        latitude.isFinite() && longitude.isFinite() && zoom.isFinite() &&
            tilt.isFinite() && bearing.isFinite()
}

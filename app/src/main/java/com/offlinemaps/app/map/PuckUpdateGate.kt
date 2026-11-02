package com.offlinemaps.app.map

import com.offlinemaps.core.common.GeoMath
import com.offlinemaps.core.common.GeoPoint
import kotlin.math.abs
import kotlin.math.max

/**
 * Decides whether a new location fix is worth pushing to the puck source.
 *
 * A GNSS receiver keeps reporting while the device stands still: the position wanders by a metre or
 * two inside the reported accuracy circle, and the accuracy estimate itself breathes with the
 * satellite geometry. Each of those fixes used to rebuild the puck document and hand it to MapLibre
 * for a change the user cannot see. This gate keeps the last *accepted* fix and lets a new one
 * through only when it is meaningful:
 *
 * * the position moved at least [minMoveMeters] since the last accepted fix, or
 * * the reported accuracy changed materially - by at least [minAccuracyDeltaMeters] and by at least
 *   [minAccuracyRelativeChange] of the previous value, so a good fix does not get re-rendered for
 *   every half-metre of noise while a poor one still tracks its (large) ring.
 *
 * The comparison is always against the last accepted fix, not the previous one, so a slow drift of
 * sub-threshold steps still reaches the puck once it has accumulated past the threshold - the puck
 * lags by at most [minMoveMeters], it never freezes.
 *
 * A hidden puck ([MapController.setUserLocation] with a null point) calls [reset]: the next fix
 * after the puck is shown again is always accepted, because nothing is on screen to compare with.
 *
 * Pure Kotlin, no Android types, so it is unit-tested on the JVM.
 */
internal class PuckUpdateGate(
    private val minMoveMeters: Double = DEFAULT_MIN_MOVE_METERS,
    private val minAccuracyDeltaMeters: Float = DEFAULT_MIN_ACCURACY_DELTA_METERS,
    private val minAccuracyRelativeChange: Float = DEFAULT_MIN_ACCURACY_RELATIVE_CHANGE,
) {

    private var lastAcceptedPoint: GeoPoint? = null
    private var lastAcceptedAccuracy: Float? = null

    /** Fixes that were let through. */
    var accepted: Int = 0
        private set

    /** Fixes dropped as visually identical to the accepted one. */
    var skipped: Int = 0
        private set

    /** True once a fix has been accepted since the last [reset]. */
    fun hasAcceptedFix(): Boolean = lastAcceptedPoint != null

    /** The position currently on screen, or null when nothing has been accepted yet. */
    fun acceptedPoint(): GeoPoint? = lastAcceptedPoint

    /**
     * Returns true when [point] (with the given [accuracyMeters]) should be pushed. An accepted fix
     * becomes the new reference; a rejected one leaves the reference alone so the movement is not
     * lost.
     */
    fun shouldPush(point: GeoPoint, accuracyMeters: Float?): Boolean {
        val previous = lastAcceptedPoint
        if (previous == null) {
            accept(point, accuracyMeters)
            return true
        }
        if (GeoMath.distanceMeters(previous, point) >= minMoveMeters) {
            accept(point, accuracyMeters)
            return true
        }
        if (accuracyChangedMaterially(lastAcceptedAccuracy, accuracyMeters)) {
            accept(point, accuracyMeters)
            return true
        }
        skipped++
        return false
    }

    /** Forgets the accepted fix. Call when the puck is hidden or the source is recreated. */
    fun reset() {
        lastAcceptedPoint = null
        lastAcceptedAccuracy = null
    }

    private fun accept(point: GeoPoint, accuracyMeters: Float?) {
        lastAcceptedPoint = point
        lastAcceptedAccuracy = accuracyMeters
        accepted++
    }

    private fun accuracyChangedMaterially(previous: Float?, current: Float?): Boolean {
        val before = previous ?: 0f
        val after = current ?: 0f
        val delta = abs(after - before)
        return delta >= max(minAccuracyDeltaMeters, minAccuracyRelativeChange * before)
    }

    companion object {
        /** A few metres: below the puck's own radius, so the movement is not resolvable on screen. */
        const val DEFAULT_MIN_MOVE_METERS = 3.0
        const val DEFAULT_MIN_ACCURACY_DELTA_METERS = 2.5f
        const val DEFAULT_MIN_ACCURACY_RELATIVE_CHANGE = 0.25f
    }
}

package com.offlinemaps.core.location

import com.offlinemaps.core.common.GeoMath
import com.offlinemaps.core.common.LocationFix
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

/**
 * Decides which position updates are worth publishing to the map while the user is exploring.
 *
 * `LocationSource.updates()` asks the platform for a minimum time *and* a minimum distance, but
 * neither is a guarantee: the distance filter is advisory and several providers and OEM builds
 * ignore it, the two providers we register can report the same instant, and `onProviderDisabled`
 * re-emits the last known fix on purpose. Every one of those reaches the collector, and in explore
 * mode each fix used to be written into the screen state, which recomposed the map screen and
 * restarted the puck effect for a position the user cannot tell apart from the one already there.
 *
 * A fix is published when it is the first one, or when it is [minDistanceMeters] away from the last
 * *published* fix - comparing against the published fix, not the previous one, so a slow drift of
 * sub-threshold steps still arrives once it has accumulated. A fix older than the published one is
 * dropped (the network provider lags the GPS one, and accepting it would walk the marker backwards),
 * and so is a repeat of the published fix at the same instant. While the device stands still nothing
 * passes the distance test, so [maxHoldMillis] puts a ceiling on how stale the published position can
 * get: one fix is let through once that long has passed, which keeps the marker and its accuracy
 * ring honest without going back to an update per fix.
 *
 * Pure Kotlin, no Android types, so it is unit-tested on the JVM.
 */
class ExploreFixGate(
    private val minDistanceMeters: Double = DEFAULT_MIN_DISTANCE_METERS,
    private val maxHoldMillis: Long = DEFAULT_MAX_HOLD_MILLIS,
) {

    private var lastPublished: LocationFix? = null

    /** Fixes let through. */
    var accepted: Int = 0
        private set

    /** Fixes dropped as duplicates, stale or too close to the published one. */
    var dropped: Int = 0
        private set

    /** The fix currently published to the screen, or null before the first one. */
    fun publishedFix(): LocationFix? = lastPublished

    /** Returns true when [fix] should be published. An accepted fix becomes the new reference. */
    fun accept(fix: LocationFix): Boolean {
        val previous = lastPublished
        if (previous == null) {
            publish(fix)
            return true
        }
        // Out of order: an older fix from the slower provider must not move the marker backwards.
        if (fix.timestampMillis < previous.timestampMillis) {
            dropped++
            return false
        }
        // The same instant, same position: the platform handing us one fix twice.
        if (fix.timestampMillis == previous.timestampMillis && fix.point == previous.point) {
            dropped++
            return false
        }
        if (GeoMath.distanceMeters(previous.point, fix.point) >= minDistanceMeters) {
            publish(fix)
            return true
        }
        // Stationary: let the clock, not the movement, decide when the next one goes out.
        if (fix.timestampMillis - previous.timestampMillis >= maxHoldMillis) {
            publish(fix)
            return true
        }
        dropped++
        return false
    }

    /** Forgets the published fix, so the next one is always accepted. */
    fun reset() {
        lastPublished = null
    }

    private fun publish(fix: LocationFix) {
        lastPublished = fix
        accepted++
    }

    companion object {
        /** The same 8 m the explore-mode registration asks the platform for. */
        const val DEFAULT_MIN_DISTANCE_METERS = 8.0

        /** Half a minute: the longest the published position may stand still before it refreshes. */
        const val DEFAULT_MAX_HOLD_MILLIS = 30_000L
    }
}

/**
 * Explore-mode position stream: the fixes in [this] flow that [gate] lets through.
 *
 * Only the map screen's own collector uses this. A navigation session streams at ~1 s / 2 m and
 * must not have fixes held back, so it stays on the raw flow.
 */
fun Flow<LocationFix>.exploreFiltered(gate: ExploreFixGate = ExploreFixGate()): Flow<LocationFix> =
    filter(gate::accept)

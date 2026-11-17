package com.offlinemaps.core.routing

import com.offlinemaps.core.common.GeoMath
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.LocationFix
import kotlin.math.max
import kotlin.math.min

/** Live navigation state derived from position updates. */
data class NavigationState(
    val alongRouteMeters: Double,
    val remainingMeters: Double,
    val remainingSeconds: Long,
    val nextManeuver: Maneuver?,
    val nextManeuverIndex: Int,
    val distanceToNextManeuverMeters: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
    val progressFraction: Float,
    val snappedPoint: GeoPoint,
    val currentStreetName: String?,
    val gpsSignalWeak: Boolean,
    val headingDegrees: Double,
)

data class NavigationConfig(
    /** Base off-route threshold; the effective value grows with the accuracy used for the fix. */
    val offRouteThresholdMeters: Double = 45.0,
    /** Consecutive off-route fixes required before we declare a deviation and recalculate. */
    val offRouteFixesToTrigger: Int = 3,
    /** Arrival radius. */
    val arrivalRadiusMeters: Double = 25.0,
    /** Accuracy worse than this is treated as a weak fix and is not used to trigger rerouting. */
    val weakAccuracyMeters: Float = 60f,
    /**
     * Accuracy assumed for a fix that carries none.
     *
     * `Location.hasAccuracy()` is false when the provider never set an estimate, and
     * such a fix used to fall back to `0f` - the tightest off-route threshold there is, so a fix
     * with no estimate at all was trusted more than any real one. A missing estimate is not evidence
     * of precision, so the geometry uses this coarse stand-in instead: a fix of unknown accuracy has
     * to be clearly off the route before it counts as a deviation. It is not called weak, though -
     * the weak-signal notice reports what the receiver said, and a receiver that said nothing is a
     * different thing from one that said "poor".
     */
    val unknownAccuracyMeters: Float = 60f,
)

/**
 * Tracks progress along a route from a stream of GPS fixes.
 *
 * All of this is plain arithmetic on the route geometry, so it is unit-tested on the JVM. A fix is
 * matched against geometry near the last known position; only when it lands outside that window is
 * the rest of the route searched, and then through a [RouteSegmentIndex] rather than a scan, so a
 * 10 000-point route stays cheap to follow on a mid-range phone.
 */
class NavigationTracker(
    private var plan: RoutePlan,
    private val config: NavigationConfig = NavigationConfig(),
) {
    private var lastGeometryIndex = 0
    private var offRouteCount = 0
    private var arrived = false

    /** Built on the first fix that falls outside the follow window, then reused for the plan. */
    private var segmentIndex: RouteSegmentIndex? = null

    /**
     * Per-segment length and bearing, measured the first time a fix touches a segment and reused
     * after that. The follow window re-measures nothing on a later fix, and the index scans through
     * the same instance, so a segment is measured once per plan however many fixes cross it
     * (OPTIMIZATION.md J-20).
     */
    private var segmentMeasures: SegmentMeasures? = null

    /** Segments whose length and bearing have been measured so far. Diagnostics only. */
    internal val segmentMeasurements: Long get() = segmentMeasures?.measurements ?: 0L

    /**
     * Refuses a fix older than the last accepted one. A late fix from a batched or replayed stream
     * used to move the matched segment, the off-route counter and the arrival flag backwards.
     */
    private val fixOrder = FixOrderGate()

    val route: RoutePlan get() = plan

    fun updatePlan(newPlan: RoutePlan) {
        plan = newPlan
        segmentIndex = null
        segmentMeasures = null
        lastGeometryIndex = 0
        offRouteCount = 0
        arrived = false
    }

    /**
     * Folds a fix into the navigation state, or returns null when the fix is older than the last
     * accepted one and was therefore ignored - nothing moves, and the caller announces nothing.
     */
    fun onLocation(fix: LocationFix): NavigationState? {
        // Out-of-order delivery: an old fix must not rewind progress. The watermark is deliberately
        // not cleared by [updatePlan]: a recalculation changes the route, not the clock.
        if (!fixOrder.accepts(fix)) return null
        val geometry = plan.geometry
        if (geometry.size < 2) {
            return NavigationState(
                alongRouteMeters = 0.0,
                remainingMeters = plan.distanceMeters,
                remainingSeconds = plan.durationSeconds,
                nextManeuver = plan.maneuvers.firstOrNull(),
                nextManeuverIndex = 0,
                distanceToNextManeuverMeters = 0.0,
                offRoute = false,
                arrived = true,
                progressFraction = 1f,
                snappedPoint = fix.point,
                currentStreetName = null,
                gpsSignalWeak = isWeak(fix),
                headingDegrees = headingFor(fix),
            )
        }

        // Search a window around the last matched index first (fast path), then fall back to the
        // spatial index if the user is far away (e.g. after a long tunnel or a teleport).
        val window = 40
        var bestIndex = lastGeometryIndex
        var bestDistance = Double.MAX_VALUE
        val fastStart = (lastGeometryIndex - 5).coerceAtLeast(0)
        val fastEnd = min(geometry.size - 2, lastGeometryIndex + window)
        val segments = measures()
        for (i in fastStart..fastEnd) {
            val d = segments.crossTrackDistanceMeters(fix.point, i)
            if (d < bestDistance) { bestDistance = d; bestIndex = i }
        }
        if (bestDistance > FAR_FROM_WINDOW_METERS) {
            val candidate = searchIndex().nearestSegment(fix.point)
            if (candidate >= 0) {
                val d = segments.crossTrackDistanceMeters(fix.point, candidate)
                if (d < bestDistance) { bestDistance = d; bestIndex = candidate }
            } else {
                // Further from the route than the index searches: kilometres away, which means a
                // bad fix or a jump to another place. Rare enough that scanning is the right answer.
                for (i in 0..geometry.size - 2) {
                    val d = segments.crossTrackDistanceMeters(fix.point, i)
                    if (d < bestDistance) { bestDistance = d; bestIndex = i }
                }
            }
        }
        lastGeometryIndex = bestIndex

        val segStart = geometry[bestIndex]
        val segEnd = geometry[bestIndex + 1]
        // One pass over the segment answers both questions this fix asks of it: how far along the
        // segment the fix projects, and where it snaps to. Measured separately they cost three
        // lengths and three bearings of the same segment, plus the bearing and distance to the fix
        // twice (OPTIMIZATION.md J-19).
        val projection = GeoMath.projectOnSegment(fix.point, segStart, segEnd)
        val alongSegment = projection.clampedAlongMeters
        val baseCumulative = plan.cumulativeMeters.getOrElse(bestIndex) { 0.0 }
        val alongRoute = baseCumulative + alongSegment
        val remaining = (plan.distanceMeters - alongRoute).coerceAtLeast(0.0)

        // Manoeuvres that are still ahead of us.
        val nextIndex = plan.maneuvers.indexOfFirst { it.distanceFromStartMeters > alongRoute + 1.0 }
            .let { if (it < 0) plan.maneuvers.lastIndex else it }
        val nextManeuver = plan.maneuvers.getOrNull(nextIndex)
        val distanceToNext = nextManeuver
            ?.let { (it.distanceFromStartMeters - alongRoute).coerceAtLeast(0.0) }
            ?: remaining

        val weak = isWeak(fix)
        val threshold = max(config.offRouteThresholdMeters, accuracyForDecision(fix) * 2.0)
        val isOff = bestDistance > threshold
        if (isOff && !weak) offRouteCount++ else if (!isOff) offRouteCount = 0
        val offRoute = offRouteCount >= config.offRouteFixesToTrigger

        if (!arrived && remaining <= config.arrivalRadiusMeters) arrived = true

        val speed = fix.speedMetersPerSecond?.toDouble()?.takeIf { it > 0.5 }
        val remainingSeconds = if (speed != null) {
            (remaining / speed).toLong()
        } else {
            val fraction = if (plan.distanceMeters > 0) remaining / plan.distanceMeters else 1.0
            (plan.durationSeconds * fraction).toLong()
        }

        val currentStreet = plan.maneuvers
            .lastOrNull { it.distanceFromStartMeters <= alongRoute + 1.0 }
            ?.streetName

        return NavigationState(
            alongRouteMeters = alongRoute,
            remainingMeters = remaining,
            remainingSeconds = remainingSeconds,
            nextManeuver = nextManeuver,
            nextManeuverIndex = nextIndex,
            distanceToNextManeuverMeters = distanceToNext,
            offRoute = offRoute,
            arrived = arrived,
            progressFraction = if (plan.distanceMeters > 0) {
                (alongRoute / plan.distanceMeters).toFloat().coerceIn(0f, 1f)
            } else 0f,
            snappedPoint = projection.snappedPoint,
            currentStreetName = currentStreet,
            gpsSignalWeak = weak,
            headingDegrees = fix.bearingDegrees?.toDouble()
                ?: if (plan.geometry.size > bestIndex + 1) {
                    segments.bearingDegrees(bestIndex)
                } else 0.0,
        )
    }

    /**
     * Heading for a plan too short to have a direction. A fix that reported no bearing used to
     * become due north (0°), which is a real direction the receiver never claimed; fall back to the
     * bearing towards the destination instead, and only to 0° when there is no direction at all.
     */
    private fun headingFor(fix: LocationFix): Double =
        fix.bearingDegrees?.toDouble()
            ?: GeoMath.bearingDegrees(fix.point, plan.destination)
                .takeIf { GeoMath.distanceMeters(fix.point, plan.destination) > 1.0 }
            ?: 0.0

    private fun searchIndex(): RouteSegmentIndex =
        segmentIndex ?: RouteSegmentIndex(plan.geometry, measures()).also { segmentIndex = it }

    private fun measures(): SegmentMeasures =
        segmentMeasures ?: SegmentMeasures(plan.geometry).also { segmentMeasures = it }

    private fun isWeak(fix: LocationFix): Boolean =
        fix.accuracyMeters?.let { it > config.weakAccuracyMeters } ?: false

    /**
     * Accuracy to use for the off-route test. A missing estimate is replaced by a coarse stand-in
     * rather than by zero, so the fix is never treated as the most precise one we could get.
     */
    private fun accuracyForDecision(fix: LocationFix): Float =
        fix.accuracyMeters ?: config.unknownAccuracyMeters

    companion object {
        /**
         * Beyond this the follow window is not trusted and the whole route is searched (through the
         * spatial index). Same value the tracker has always used for that decision.
         */
        private const val FAR_FROM_WINDOW_METERS = 250.0

        /** True when the user has clearly moved away from the route and we should recalculate. */
        fun shouldRecalculate(state: NavigationState): Boolean = state.offRoute && !state.arrived
    }
}

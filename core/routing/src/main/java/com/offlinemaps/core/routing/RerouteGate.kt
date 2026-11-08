package com.offlinemaps.core.routing

import com.offlinemaps.core.common.GeoMath
import com.offlinemaps.core.common.GeoPoint

/**
 * Limits how often a deviation may trigger a recalculation.
 *
 * A recalculation is expensive (a full route through the graph), so it is never attempted on every
 * off-route fix. Two conditions have to hold before an attempt is made: enough time has passed since
 * the last one ([ReroutePolicy.cooldownMillis]) *and* the driver has actually moved since the last
 * one ([ReroutePolicy.minTravelMeters]). The distance condition is what stops a stationary off-route
 * fix - a red light, a parked car, a tunnel where GPS wanders - from spending the whole attempt
 * budget recalculating from the same point every cooldown; a driver who is really moving covers the
 * minimum distance well inside the cooldown, so the time limit stays the one that paces a moving
 * session, exactly as before.
 *
 * Pure arithmetic on a fix's point and a clock value, so it is unit-tested on the JVM. Not
 * thread-safe: it is owned by one navigation loop, which calls it from a single thread.
 */
class RerouteGate(private val policy: ReroutePolicy = ReroutePolicy()) {

    private var attempts = 0
    private var lastAttemptAtMillis = 0L
    private var lastAttemptPoint: GeoPoint? = null
    private var hasAttempted = false

    /** True when a recalculation may be attempted now, at [nowMillis] and [point]. */
    fun shouldAttempt(nowMillis: Long, point: GeoPoint): Boolean {
        if (attempts >= policy.maxAttempts) return false
        if (hasAttempted && nowMillis - lastAttemptAtMillis < policy.cooldownMillis) return false
        val last = lastAttemptPoint
        if (last != null && GeoMath.distanceMeters(last, point) < policy.minTravelMeters) return false
        return true
    }

    /**
     * Records that an attempt was made, so it counts against [ReroutePolicy.maxAttempts] and anchors
     * the next cooldown and distance check. Call it only after [shouldAttempt] returned true.
     */
    fun recordAttempt(nowMillis: Long, point: GeoPoint) {
        attempts++
        lastAttemptAtMillis = nowMillis
        lastAttemptPoint = point
        hasAttempted = true
    }

    /**
     * The recalculation succeeded and a fresh route is being followed: the attempt budget starts
     * over. The cooldown and the anchor point are kept, so a deviation immediately after a
     * recalculation still waits and still needs real movement before it is answered.
     */
    fun onRerouted() {
        attempts = 0
    }

    /** Forgets everything, for a new session. */
    fun reset() {
        attempts = 0
        lastAttemptAtMillis = 0L
        lastAttemptPoint = null
        hasAttempted = false
    }
}

/**
 * Tuning for [RerouteGate]. The defaults are the values navigation has always used for the time
 * limit and the attempt budget, plus the movement the driver has to make before another attempt.
 */
data class ReroutePolicy(
    /** Minimum time between two attempts. */
    val cooldownMillis: Long = 12_000L,
    /** Minimum distance travelled since the last attempt before another one is worth making. */
    val minTravelMeters: Double = 30.0,
    /** Attempts without a successful recalculation before the gate stops trying. */
    val maxAttempts: Int = 5,
)

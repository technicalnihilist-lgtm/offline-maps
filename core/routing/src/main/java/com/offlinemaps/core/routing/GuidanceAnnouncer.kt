package com.offlinemaps.core.routing

/**
 * Decides *when* to speak and what to say.
 *
 * The rules that matter in practice: announce each manoeuvre a small number of times at shrinking
 * distances, never repeat the exact same announcement, and never talk over the user's own audio
 * more than necessary. Kept pure so the whole thing is unit-tested.
 *
 * [onState] runs once per GPS fix - several times a second while guidance is on - so it allocates
 * nothing: the "what did we last say" record is a small set of primitive fields plus one enum
 * constant, not the `"<maneuverIndex>@<threshold>"` key string this used to build on every fix
 * within announcing range. The only allocation left on this path is the spoken sentence itself, and
 * that happens once per bucket rather than once per fix.
 */
class GuidanceAnnouncer(
    private val speak: (String) -> Unit,
    private val metricUnits: Boolean = true,
    private val thresholdsMeters: List<Double> = listOf(600.0, 250.0, 80.0, 25.0),
) {
    /**
     * What was last announced. The manoeuvre key used to be a string; it is now the pair
     * ([latchedManeuverIndex], [latchedThresholdMeters]) under [Latch.MANEUVER], and the two states
     * that are not manoeuvres are their own latch values. Same decisions, no per-fix garbage.
     */
    private var latch: Latch = Latch.NONE
    private var latchedManeuverIndex = -1
    private var latchedThresholdMeters = -1
    private var announcedManeuverIndex = -1
    private var arrivalAnnounced = false
    private var lastOffRouteAnnouncementAt = 0L

    fun reset() {
        latch = Latch.NONE
        latchedManeuverIndex = -1
        latchedThresholdMeters = -1
        announcedManeuverIndex = -1
        arrivalAnnounced = false
        lastOffRouteAnnouncementAt = 0L
    }

    fun onState(state: NavigationState, nowMillis: Long = System.currentTimeMillis()) {
        if (state.arrived) {
            if (!arrivalAnnounced) {
                arrivalAnnounced = true
                speak("You have arrived at your destination.")
            }
            return
        }

        val maneuver = state.nextManeuver ?: return
        val distance = state.distanceToNextManeuverMeters

        // Thresholds are ordered widest-first, so the last match is the tightest bucket the
        // remaining distance has already passed (e.g. 240 m -> 250 m bucket, 70 m -> 80 m bucket).
        val bucketIndex = tightestBucketIndex(distance)
        if (bucketIndex < 0) return
        val threshold = thresholdsMeters[bucketIndex]
        val bucket = threshold.toInt()

        if (latch == Latch.MANEUVER &&
            latchedManeuverIndex == state.nextManeuverIndex &&
            latchedThresholdMeters == bucket
        ) {
            return
        }
        if (state.nextManeuverIndex == announcedManeuverIndex && distance > threshold + 1.0) return

        latch = Latch.MANEUVER
        latchedManeuverIndex = state.nextManeuverIndex
        latchedThresholdMeters = bucket
        announcedManeuverIndex = state.nextManeuverIndex
        val spokenDistance = if (metricUnits) distance else distance * 3.28084
        speak(
            InstructionMapper.spoken(
                type = maneuver.type,
                streetName = maneuver.streetName,
                distanceToManeuverMeters = spokenDistance,
            ),
        )
    }

    /**
     * Index of the tightest threshold [distance] has passed, or -1 for none. Written as a plain
     * descending index loop so the per-fix path neither iterates through a lambda nor boxes the
     * result: a `Double?` return would allocate on every fix that misses.
     */
    private fun tightestBucketIndex(distance: Double): Int {
        var i = thresholdsMeters.size - 1
        while (i >= 0) {
            if (distance <= thresholdsMeters[i]) return i
            i--
        }
        return -1
    }

    /**
     * Called when the route has been recalculated after a deviation.
     *
     * The manoeuvre buckets are cleared - they belong to the old route - but [arrivalAnnounced] is
     * deliberately kept. A recalculation that lands after the arrival fix (it was started from the
     * last off-route position and finishes a moment later) used to clear the flag, and the next fix
     * then set `arrived` again on the new, very short plan and spoke the arrival a second time. A
     * session reaches its destination once; a new one starts with a fresh announcer.
     */
    fun onRerouted(nowMillis: Long = System.currentTimeMillis()) {
        latch = Latch.NONE
        latchedManeuverIndex = -1
        latchedThresholdMeters = -1
        announcedManeuverIndex = -1
        if (nowMillis - lastOffRouteAnnouncementAt > 8_000L) {
            lastOffRouteAnnouncementAt = nowMillis
            speak("Route recalculated.")
        }
    }

    /** Called when GPS is lost for long enough that navigation cannot continue confidently. */
    fun onGpsLost() {
        if (latch != Latch.GPS_LOST) {
            latch = Latch.GPS_LOST
            speak("GPS signal lost. Navigation is paused until your position is available again.")
        }
    }

    fun onGpsRestored() {
        if (latch == Latch.GPS_LOST) {
            latch = Latch.NONE
            speak("GPS signal restored. Continuing navigation.")
        }
    }

    private enum class Latch { NONE, MANEUVER, GPS_LOST }
}

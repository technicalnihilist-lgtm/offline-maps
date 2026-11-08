package com.offlinemaps.core.routing

/**
 * Decides *when* to speak and what to say.
 *
 * The rules that matter in practice: announce each manoeuvre a small number of times at shrinking
 * distances, never repeat the exact same announcement, and never talk over the user's own audio
 * more than necessary. Kept pure so the whole thing is unit-tested.
 */
class GuidanceAnnouncer(
    private val speak: (String) -> Unit,
    private val metricUnits: Boolean = true,
    private val thresholdsMeters: List<Double> = listOf(600.0, 250.0, 80.0, 25.0),
) {
    private var announcedKey: String? = null
    private var announcedManeuverIndex = -1
    private var arrivalAnnounced = false
    private var lastOffRouteAnnouncementAt = 0L

    fun reset() {
        announcedKey = null
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
        val threshold = thresholdsMeters.lastOrNull { distance <= it } ?: return
        val key = "${state.nextManeuverIndex}@${threshold.toInt()}"
        if (key == announcedKey) return
        if (state.nextManeuverIndex == announcedManeuverIndex && distance > threshold + 1.0) return

        announcedKey = key
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
     * Called when the route has been recalculated after a deviation.
     *
     * The manoeuvre buckets are cleared - they belong to the old route - but [arrivalAnnounced] is
     * deliberately kept. A recalculation that lands after the arrival fix (it was started from the
     * last off-route position and finishes a moment later) used to clear the flag, and the next fix
     * then set `arrived` again on the new, very short plan and spoke the arrival a second time. A
     * session reaches its destination once; a new one starts with a fresh announcer.
     */
    fun onRerouted(nowMillis: Long = System.currentTimeMillis()) {
        announcedKey = null
        announcedManeuverIndex = -1
        if (nowMillis - lastOffRouteAnnouncementAt > 8_000L) {
            lastOffRouteAnnouncementAt = nowMillis
            speak("Route recalculated.")
        }
    }

    /** Called when GPS is lost for long enough that navigation cannot continue confidently. */
    fun onGpsLost() {
        if (announcedKey != GPS_LOST) {
            announcedKey = GPS_LOST
            speak("GPS signal lost. Navigation is paused until your position is available again.")
        }
    }

    fun onGpsRestored() {
        if (announcedKey == GPS_LOST) {
            announcedKey = null
            speak("GPS signal restored. Continuing navigation.")
        }
    }

    private companion object {
        const val GPS_LOST = "gps-lost"
    }
}

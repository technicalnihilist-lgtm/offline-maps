package com.offlinemaps.core.routing

import com.graphhopper.util.Instruction

/**
 * Maps GraphHopper instruction signs to our own manoeuvre types and produces the text used both on
 * screen and by the speech engine. Keeping this pure makes turn instructions testable without a
 * device.
 */
object InstructionMapper {

    fun typeForSign(sign: Int): ManeuverType = when (sign) {
        Instruction.CONTINUE_ON_STREET -> ManeuverType.CONTINUE
        Instruction.TURN_LEFT -> ManeuverType.TURN_LEFT
        Instruction.TURN_SLIGHT_LEFT -> ManeuverType.TURN_SLIGHT_LEFT
        Instruction.TURN_SHARP_LEFT -> ManeuverType.TURN_SHARP_LEFT
        Instruction.TURN_RIGHT -> ManeuverType.TURN_RIGHT
        Instruction.TURN_SLIGHT_RIGHT -> ManeuverType.TURN_SLIGHT_RIGHT
        Instruction.TURN_SHARP_RIGHT -> ManeuverType.TURN_SHARP_RIGHT
        Instruction.KEEP_LEFT -> ManeuverType.KEEP_LEFT
        Instruction.KEEP_RIGHT -> ManeuverType.KEEP_RIGHT
        Instruction.U_TURN_LEFT, Instruction.U_TURN_RIGHT, Instruction.U_TURN_UNKNOWN -> ManeuverType.U_TURN
        Instruction.USE_ROUNDABOUT, Instruction.LEAVE_ROUNDABOUT -> ManeuverType.ROUNDABOUT_ENTER
        Instruction.FINISH -> ManeuverType.ARRIVE
        Instruction.REACHED_VIA -> ManeuverType.ARRIVE
        else -> ManeuverType.UNKNOWN
    }

    /** Screen text for a manoeuvre. Street names come from the map data and may be absent. */
    fun text(type: ManeuverType, streetName: String?, exitNumber: Int? = null): String {
        val onto = streetName?.takeIf { it.isNotBlank() }
        return when (type) {
            ManeuverType.DEPART -> onto?.let { "Head out on $it" } ?: "Start your trip"
            ManeuverType.CONTINUE -> onto?.let { "Continue on $it" } ?: "Continue straight"
            ManeuverType.TURN_LEFT -> onto?.let { "Turn left onto $it" } ?: "Turn left"
            ManeuverType.TURN_SLIGHT_LEFT -> onto?.let { "Bear left onto $it" } ?: "Bear left"
            ManeuverType.TURN_SHARP_LEFT -> onto?.let { "Sharp left onto $it" } ?: "Sharp left"
            ManeuverType.TURN_RIGHT -> onto?.let { "Turn right onto $it" } ?: "Turn right"
            ManeuverType.TURN_SLIGHT_RIGHT -> onto?.let { "Bear right onto $it" } ?: "Bear right"
            ManeuverType.TURN_SHARP_RIGHT -> onto?.let { "Sharp right onto $it" } ?: "Sharp right"
            ManeuverType.KEEP_LEFT -> onto?.let { "Keep left towards $it" } ?: "Keep left"
            ManeuverType.KEEP_RIGHT -> onto?.let { "Keep right towards $it" } ?: "Keep right"
            ManeuverType.U_TURN -> "Make a U-turn"
            ManeuverType.ROUNDABOUT_ENTER -> exitNumber?.let { "Enter the roundabout and take exit $it" }
                ?: "Enter the roundabout"
            ManeuverType.ROUNDABOUT_EXIT -> onto?.let { "Leave the roundabout onto $it" } ?: "Leave the roundabout"
            ManeuverType.ARRIVE -> "You have arrived at your destination"
            ManeuverType.UNKNOWN -> onto?.let { "Continue on $it" } ?: "Continue"
        }
    }

    /** Short spoken prefix with the distance to the manoeuvre, e.g. "In 200 metres, turn right". */
    fun spoken(type: ManeuverType, streetName: String?, distanceToManeuverMeters: Double?): String {
        val base = text(type, streetName)
        if (distanceToManeuverMeters == null || type == ManeuverType.ARRIVE) return base
        val d = when {
            distanceToManeuverMeters < 30 -> "now"
            distanceToManeuverMeters < 1000 -> "in ${(distanceToManeuverMeters / 10).toInt() * 10} metres"
            else -> "in ${String.format(java.util.Locale.US, "%.1f", distanceToManeuverMeters / 1000.0)} kilometres"
        }
        return if (d == "now") base else "$d, ${base.replaceFirstChar { it.lowercase() }}"
    }
}

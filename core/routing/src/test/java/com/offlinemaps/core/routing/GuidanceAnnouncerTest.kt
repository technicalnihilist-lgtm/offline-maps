package com.offlinemaps.core.routing

import com.offlinemaps.core.common.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceAnnouncerTest {

    private val spoken = mutableListOf<String>()

    private fun state(distanceToNext: Double, maneuverIndex: Int = 1, arrived: Boolean = false) = NavigationState(
        alongRouteMeters = 0.0,
        remainingMeters = 1000.0,
        remainingSeconds = 60,
        nextManeuver = Maneuver(
            type = ManeuverType.TURN_RIGHT,
            sign = 2,
            streetName = "Carrer Major",
            legDistanceMeters = 100.0,
            legTimeSeconds = 20,
            geometryIndex = 5,
            distanceFromStartMeters = 200.0,
            text = "Turn right onto Carrer Major",
        ),
        nextManeuverIndex = maneuverIndex,
        distanceToNextManeuverMeters = distanceToNext,
        offRoute = false,
        arrived = arrived,
        progressFraction = 0.1f,
        snappedPoint = GeoPoint(42.5, 1.5),
        currentStreetName = null,
        gpsSignalWeak = false,
        headingDegrees = 0.0,
    )

    private fun announcer() = GuidanceAnnouncer(speak = { spoken.add(it) })

    /** Same announcer, but with the two speech channels kept apart so the routing can be asserted. */
    private fun splitAnnouncer() = GuidanceAnnouncer(
        speak = { spoken.add("instruction:$it") },
        notice = { spoken.add("notice:$it") },
    )

    @Test
    fun `announces once per threshold not per fix`() {
        val announcer = announcer()
        announcer.onState(state(distanceToNext = 240.0))
        announcer.onState(state(distanceToNext = 235.0))
        announcer.onState(state(distanceToNext = 200.0))
        assertEquals(1, spoken.size)
        assertTrue(
            "spoken text should name the turn: ${spoken.first()}",
            spoken.first().contains("turn right onto Carrer Major", ignoreCase = true),
        )
    }

    @Test
    fun `announces again at the next threshold`() {
        val announcer = announcer()
        announcer.onState(state(distanceToNext = 240.0))
        announcer.onState(state(distanceToNext = 70.0))
        assertEquals(2, spoken.size)
    }

    @Test
    fun `does not announce when far from the manoeuvre`() {
        announcer().onState(state(distanceToNext = 5000.0))
        assertEquals(0, spoken.size)
    }

    @Test
    fun `announces arrival exactly once`() {
        val announcer = announcer()
        announcer.onState(state(distanceToNext = 0.0, arrived = true))
        announcer.onState(state(distanceToNext = 0.0, arrived = true))
        assertEquals(1, spoken.size)
        assertTrue(spoken.first().contains("arrived"))
    }

    @Test
    fun `rerouting resets the announcement state and speaks once`() {
        val announcer = announcer()
        announcer.onState(state(distanceToNext = 70.0))
        announcer.onRerouted(nowMillis = 100_000)
        announcer.onRerouted(nowMillis = 101_000)
        assertEquals(2, spoken.size)
        assertTrue(spoken.last().contains("recalculated"))
    }

    @Test
    fun `a recalculation after arrival does not announce arrival again`() {
        val announcer = announcer()
        announcer.onState(state(distanceToNext = 0.0, arrived = true))
        // The reroute was started from the last off-route fix and lands a moment after the arrival
        // one; the tracker clears its arrival latch on the new plan and the next fix sets it again.
        announcer.onRerouted(nowMillis = 100_000)
        announcer.onState(state(distanceToNext = 0.0, arrived = true))
        announcer.onState(state(distanceToNext = 0.0, arrived = true))
        assertEquals(1, spoken.count { it.contains("arrived", ignoreCase = true) })
    }

    @Test
    fun `a recalculation before arrival does not swallow the arrival announcement`() {
        val announcer = announcer()
        announcer.onState(state(distanceToNext = 70.0))
        announcer.onRerouted(nowMillis = 100_000)
        announcer.onState(state(distanceToNext = 0.0, arrived = true))
        assertEquals(1, spoken.count { it.contains("arrived", ignoreCase = true) })
    }

    @Test
    fun `gps loss is announced once`() {
        val announcer = announcer()
        announcer.onGpsLost()
        announcer.onGpsLost()
        assertEquals(1, spoken.size)
        announcer.onGpsRestored()
        assertEquals(2, spoken.size)
    }

    @Test
    fun `the same distance for a different manoeuvre is announced again`() {
        // The announcement record is keyed by manoeuvre as well as by bucket: turning at 240 m is
        // not the same announcement as the next turn at 240 m.
        val announcer = announcer()
        announcer.onState(state(distanceToNext = 240.0, maneuverIndex = 1))
        announcer.onState(state(distanceToNext = 240.0, maneuverIndex = 2))
        assertEquals(2, spoken.size)
    }

    @Test
    fun `a distance exactly on a threshold falls in the tighter bucket`() {
        val announcer = announcer()
        announcer.onState(state(distanceToNext = 251.0)) // 600 m bucket
        announcer.onState(state(distanceToNext = 250.0)) // 250 m bucket
        announcer.onState(state(distanceToNext = 250.0)) // same bucket, nothing new
        assertEquals(2, spoken.size)
    }

    @Test
    fun `a gps loss and restore re-arms the manoeuvre announcement`() {
        val announcer = announcer()
        announcer.onState(state(distanceToNext = 70.0))
        announcer.onGpsLost()
        announcer.onGpsRestored()
        announcer.onState(state(distanceToNext = 70.0))
        assertEquals(4, spoken.size)
        assertTrue(spoken.last().contains("turn right onto Carrer Major", ignoreCase = true))
    }

    @Test
    fun `the announcer keeps no string state between fixes`() {
        // The per-fix announcement key used to be a `"<index>@<threshold>"` string built on every
        // fix in range. Nothing on the fix path may hold or build a String now.
        val stringFields = GuidanceAnnouncer::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.name }
        assertTrue("GuidanceAnnouncer holds String state: $stringFields", stringFields.isEmpty())
    }

    @Test
    fun `a recalculation is a notice and does not cut an instruction short`() {
        val announcer = splitAnnouncer()
        announcer.onState(state(distanceToNext = 240.0))
        announcer.onRerouted(nowMillis = 100_000L)

        assertEquals(2, spoken.size)
        assertTrue("first should be the instruction: $spoken", spoken[0].startsWith("instruction:"))
        assertTrue("second should be the notice: $spoken", spoken[1].startsWith("notice:"))
        assertTrue(spoken[1].contains("recalculated", ignoreCase = true))
    }

    @Test
    fun `a restored signal is a notice while a lost one supersedes`() {
        val announcer = splitAnnouncer()
        announcer.onGpsLost()
        announcer.onGpsRestored()

        assertEquals(2, spoken.size)
        assertTrue("the loss must interrupt: $spoken", spoken[0].startsWith("instruction:"))
        assertTrue("the restore must queue: $spoken", spoken[1].startsWith("notice:"))
    }

    @Test
    fun `maneuvers and arrival stay on the instruction channel`() {
        val announcer = splitAnnouncer()
        announcer.onState(state(distanceToNext = 240.0))
        announcer.onState(state(distanceToNext = 0.0, arrived = true))

        assertEquals(2, spoken.size)
        assertTrue(spoken.all { it.startsWith("instruction:") })
    }

}

class InstructionMapperTest {

    @Test
    fun `signs map to manoeuvre types`() {
        assertEquals(ManeuverType.TURN_LEFT, InstructionMapper.typeForSign(-2))
        assertEquals(ManeuverType.TURN_RIGHT, InstructionMapper.typeForSign(2))
        assertEquals(ManeuverType.CONTINUE, InstructionMapper.typeForSign(0))
        assertEquals(ManeuverType.ARRIVE, InstructionMapper.typeForSign(4))
        assertEquals(ManeuverType.ROUNDABOUT_ENTER, InstructionMapper.typeForSign(6))
        assertEquals(ManeuverType.U_TURN, InstructionMapper.typeForSign(-8))
    }

    @Test
    fun `text includes the street name when known`() {
        assertEquals("Turn left onto Main Street", InstructionMapper.text(ManeuverType.TURN_LEFT, "Main Street"))
        assertEquals("Turn left", InstructionMapper.text(ManeuverType.TURN_LEFT, null))
        assertEquals("Turn left", InstructionMapper.text(ManeuverType.TURN_LEFT, "   "))
    }

    @Test
    fun `roundabout text mentions the exit when known`() {
        assertEquals(
            "Enter the roundabout and take exit 3",
            InstructionMapper.text(ManeuverType.ROUNDABOUT_ENTER, null, exitNumber = 3),
        )
    }

    @Test
    fun `spoken text rounds the distance`() {
        val spoken = InstructionMapper.spoken(ManeuverType.TURN_RIGHT, "Main Street", 247.0)
        assertTrue(spoken.startsWith("in 240 metres"))
        assertTrue(spoken.contains("turn right onto Main Street"))
    }

    @Test
    fun `spoken text says now when very close`() {
        assertEquals("Turn right onto Main Street", InstructionMapper.spoken(ManeuverType.TURN_RIGHT, "Main Street", 10.0))
    }

    @Test
    fun `spoken text uses kilometres above a kilometre`() {
        assertTrue(InstructionMapper.spoken(ManeuverType.CONTINUE, "A2", 2500.0).startsWith("in 2.5 kilometres"))
    }
}

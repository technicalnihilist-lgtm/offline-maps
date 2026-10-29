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
    fun `gps loss is announced once`() {
        val announcer = announcer()
        announcer.onGpsLost()
        announcer.onGpsLost()
        assertEquals(1, spoken.size)
        announcer.onGpsRestored()
        assertEquals(2, spoken.size)
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

package com.offlinemaps.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `distance switches units at one kilometre`() {
        assertEquals("350 m", Formatters.distance(350.0))
        assertEquals("1.2 km", Formatters.distance(1234.0))
        assertEquals("18 km", Formatters.distance(18_400.0))
    }

    @Test
    fun `imperial distance uses feet and miles`() {
        assertEquals("328 ft", Formatters.distance(100.0, metric = false))
        assertEquals("1.0 mi", Formatters.distance(1609.344, metric = false))
    }

    @Test
    fun `duration formats hours and minutes`() {
        assertEquals("45 s", Formatters.duration(45))
        assertEquals("12 min", Formatters.duration(12 * 60))
        assertEquals("1 h 25 min", Formatters.duration(85 * 60))
    }

    @Test
    fun `byte sizes use binary units`() {
        assertEquals("512 B", Formatters.bytes(512))
        assertEquals("1.0 MB", Formatters.bytes(1024L * 1024L))
        assertEquals("2.00 GB", Formatters.bytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `speed converts to kmh and mph`() {
        assertEquals("36 km/h", Formatters.speed(10.0))
        assertEquals("22 mph", Formatters.speed(10.0, metric = false))
    }

    @Test
    fun `remaining time is derived from rate`() {
        assertEquals("10 s", Formatters.remainingTime(1000, 100))
        assertEquals("-", Formatters.remainingTime(1000, 0))
    }

    @Test
    fun `compass direction names the octant`() {
        assertEquals("N", Formatters.bearingCompass(0.0))
        assertEquals("NE", Formatters.bearingCompass(45.0))
        assertEquals("S", Formatters.bearingCompass(180.0))
        assertEquals("W", Formatters.bearingCompass(270.0))
    }

    @Test
    fun `coordinate formatting is stable`() {
        assertEquals("18.520430, 73.856744", GeoPoint(18.52043, 73.856744).format(6))
    }
}

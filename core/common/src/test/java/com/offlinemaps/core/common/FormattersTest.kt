package com.offlinemaps.core.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        // The table is a constant now, so the wrap and the negative side are pinned here.
        assertEquals("N", Formatters.bearingCompass(360.0))
        assertEquals("N", Formatters.bearingCompass(-360.0))
        assertEquals("NW", Formatters.bearingCompass(-45.0))
        assertEquals("N", Formatters.bearingCompass(359.0))
    }

    @Test
    fun `coordinate formatting is stable`() {
        assertEquals("18.520430, 73.856744", GeoPoint(18.52043, 73.856744).format(6))
    }

    // ---- the cached ETA formatter (OPTIMIZATION.md J-24) ---------------------------------------

    /**
     * The cached formatter has to answer what a fresh `SimpleDateFormat` answers, for every minute
     * of a day and both patterns: the reuse is only worth anything if the strings are identical.
     */
    @Test
    fun `eta matches a freshly built formatter for every minute of a day`() {
        val base = 1_765_000_000_000L
        val locale = Locale.getDefault()
        val clock24 = SimpleDateFormat("HH:mm", locale)
        val clock12 = SimpleDateFormat("h:mm a", locale)
        var millis = base
        repeat(24 * 60) {
            val date = Date(millis)
            // Twice per instant: the second read is the one that comes off the cache.
            assertEquals(clock24.format(date), Formatters.eta(millis))
            assertEquals(clock24.format(date), Formatters.eta(millis))
            assertEquals(clock12.format(date), Formatters.eta(millis, is24Hour = false))
            assertEquals(clock12.format(date), Formatters.eta(millis, is24Hour = false))
            millis += 60_000
        }
    }

    @Test
    fun `eta builds one formatter per thread, not one per read`() {
        // Warm this thread's cache for both patterns first, so the count below covers only reads.
        Formatters.eta(1_765_000_000_000L)
        Formatters.eta(1_765_000_000_000L, is24Hour = false)
        val before = Formatters.etaFormatsBuilt()
        repeat(500) { step ->
            Formatters.eta(1_765_000_000_000L + step * 1_000L)
            Formatters.eta(1_765_000_000_000L + step * 1_000L, is24Hour = false)
        }
        assertEquals(
            "500 pairs of reads built ${Formatters.etaFormatsBuilt() - before} formatters",
            before,
            Formatters.etaFormatsBuilt(),
        )
    }

    @Test
    fun `a second thread gets its own formatter`() {
        Formatters.eta(1_765_000_000_000L)
        val before = Formatters.etaFormatsBuilt()
        var answer: String? = null
        val worker = Thread { answer = Formatters.eta(1_765_000_000_000L) }
        worker.start()
        worker.join()
        assertEquals(
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(1_765_000_000_000L)),
            answer,
        )
        assertTrue(
            "the second thread reused the first thread's formatter",
            Formatters.etaFormatsBuilt() > before,
        )
    }

    /**
     * A language or time-zone change has to be picked up on the next read, exactly as a formatter
     * built at that moment would have picked it up. Both defaults are process-wide, so they are
     * restored before the test returns.
     */
    @Test
    fun `eta follows a change of the default locale or time zone`() {
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            Formatters.eta(1_765_000_000_000L)

            val beforeLocale = Formatters.etaFormatsBuilt()
            Locale.setDefault(Locale.FRANCE)
            assertEquals(
                SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(1_765_000_000_000L)),
                Formatters.eta(1_765_000_000_000L),
            )
            assertTrue("a locale change did not rebuild the formatter", Formatters.etaFormatsBuilt() > beforeLocale)

            val beforeZone = Formatters.etaFormatsBuilt()
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            val expected = SimpleDateFormat("HH:mm", Locale.FRANCE)
                .apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }
                .format(Date(1_765_000_000_000L))
            assertEquals(expected, Formatters.eta(1_765_000_000_000L))
            assertTrue("a time-zone change did not rebuild the formatter", Formatters.etaFormatsBuilt() > beforeZone)
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }

    /**
     * `SimpleDateFormat` is not thread-safe, which is why the cache is per thread. Eight threads
     * reading at once must all get the reference string and must not corrupt each other's formatter.
     */
    @Test
    fun `eta reads the same answer from eight threads at once`() {
        val locale = Locale.getDefault()
        val base = 1_765_000_000_000L
        // The references are taken on this thread: a `SimpleDateFormat` is not safe to share, and
        // this test is about the formatter under test, not about the one it is checked against.
        val clock24 = SimpleDateFormat("HH:mm", locale)
        val clock12 = SimpleDateFormat("h:mm a", locale)
        val expected = (0 until 250).map { step ->
            val millis = base + step * 37_000L
            val date = Date(millis)
            millis to (clock24.format(date) to clock12.format(date))
        }
        val failures = ConcurrentLinkedQueue<String>()
        val threads = (0 until 8).map {
            Thread {
                expected.forEach { (millis, strings) ->
                    val fast = Formatters.eta(millis)
                    if (fast != strings.first) failures.add("24h $millis: $fast")
                    val slow = Formatters.eta(millis, is24Hour = false)
                    if (slow != strings.second) failures.add("12h $millis: $slow")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals("mismatched ETA strings:\n" + failures.take(10).joinToString("\n"), 0, failures.size)
    }
}

package com.offlinemaps.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

/**
 * Pins how a places pack is read when the file is not perfect (OPTIMIZATION.md I-04).
 *
 * The pack arrives from a download, so it can be cut short by a dropped connection or written by a
 * tool that emitted a line the app cannot decode. None of that may cost the user the places that
 * did survive, and none of it may end the read with an exception that takes the install down.
 */
class PlacesPackReaderTest {

    private fun record(i: Int) =
        """{"id":"n$i","name":"Place number $i of Andorra","type":"town","lat":42.5,"lon":1.5}"""

    private fun packText(count: Int) = (1..count).joinToString("\n", postfix = "\n") { record(it) }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray, stats: PlacesPackStats = PlacesPackStats()): List<PlaceRecord> =
        PlacesPackReader.read(ByteArrayInputStream(bytes), stats).toList()

    @Test
    fun `reads every record from a gzip pack`() {
        val stats = PlacesPackStats()
        val records = read(gzip(packText(3)), stats)
        assertEquals(listOf("Place number 1 of Andorra", "Place number 2 of Andorra", "Place number 3 of Andorra"), records.map { it.name })
        assertEquals(3, stats.records)
        assertEquals(0, stats.skippedLines)
        assertFalse(stats.truncated)
        assertFalse(stats.damaged)
    }

    @Test
    fun `reads a plain uncompressed pack`() {
        val stats = PlacesPackStats()
        val records = read(packText(2).toByteArray(), stats)
        assertEquals(2, records.size)
        assertFalse(stats.damaged)
    }

    @Test
    fun `blank lines are ignored and not counted as damage`() {
        val text = "\n" + record(1) + "\n\n" + record(2) + "\n\n"
        val stats = PlacesPackStats()
        val records = read(gzip(text), stats)
        assertEquals(2, records.size)
        assertEquals(0, stats.skippedLines)
        assertFalse(stats.damaged)
    }

    @Test
    fun `a line that is not JSON is skipped and the rest still reads`() {
        val text = record(1) + "\nthis is not json\n" + record(2) + "\n"
        val stats = PlacesPackStats()
        val records = read(gzip(text), stats)
        assertEquals(listOf("Place number 1 of Andorra", "Place number 2 of Andorra"), records.map { it.name })
        assertEquals(1, stats.skippedLines)
        assertFalse(stats.truncated)
        assertTrue(stats.damaged)
    }

    @Test
    fun `a record without a name is skipped`() {
        val text = """{"id":"x","name":"","type":"town","lat":42.5,"lon":1.5}""" + "\n" + record(1) + "\n"
        val stats = PlacesPackStats()
        val records = read(gzip(text), stats)
        assertEquals(1, records.size)
        assertEquals(1, stats.skippedLines)
    }

    @Test
    fun `a record with a coordinate outside the world is skipped`() {
        val text = record(1) + "\n" +
            """{"id":"bad1","name":"Impossible latitude","type":"town","lat":91.0,"lon":1.5}""" + "\n" +
            """{"id":"bad2","name":"Impossible longitude","type":"town","lat":42.5,"lon":180.5}""" + "\n" +
            record(2) + "\n"
        val stats = PlacesPackStats()
        val records = read(gzip(text), stats)
        assertEquals(listOf("Place number 1 of Andorra", "Place number 2 of Andorra"), records.map { it.name })
        assertEquals(2, stats.skippedLines)
        assertEquals(2, stats.records)
        assertTrue(stats.damaged)
    }

    @Test
    fun `the poles and the antimeridian are still in range`() {
        val text = """{"id":"p1","name":"North pole","type":"place","lat":90.0,"lon":180.0}""" + "\n" +
            """{"id":"p2","name":"South pole","type":"place","lat":-90.0,"lon":-180.0}""" + "\n"
        val stats = PlacesPackStats()
        val records = read(text.toByteArray(), stats)
        assertEquals(listOf("North pole", "South pole"), records.map { it.name })
        assertEquals(0, stats.skippedLines)
        assertFalse(stats.damaged)
    }

    @Test
    fun `a gzip body that ends early keeps the records that decoded`() {
        val full = gzip(packText(300))
        val truncated = full.copyOf(full.size / 2)
        val stats = PlacesPackStats()
        val records = read(truncated, stats)
        assertTrue("expected some records from the readable prefix, got ${records.size}", records.isNotEmpty())
        assertTrue("expected the cut to lose records, got ${records.size}", records.size < 300)
        assertTrue("expected the pack to be reported as truncated", stats.truncated)
        assertTrue(stats.damaged)
        assertEquals(records.size, stats.records)
    }

    @Test
    fun `a gzip that is cut inside its header throws so the install can report it`() {
        try {
            read(byteArrayOf(0x1f, 0x8b.toByte()))
            fail("expected an IOException for an unreadable gzip header")
        } catch (expected: IOException) {
            // Nothing is recoverable from a stream whose header cannot be read; PackageInstaller
            // turns this into the "damaged or incomplete" sentence.
        }
    }

    @Test
    fun `a pack of plain junk yields nothing and is reported as damaged`() {
        val stats = PlacesPackStats()
        val records = read("not a pack at all\nnor this line\n".toByteArray(), stats)
        assertTrue(records.isEmpty())
        assertEquals(0, stats.records)
        assertEquals(2, stats.skippedLines)
        assertTrue(stats.damaged)
    }
}

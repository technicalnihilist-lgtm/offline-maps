package com.offlinemaps.core.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.SearchSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Tests the SQLite FTS index that powers offline search, including the gzip pack importer used by
 * the region download flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchIndexTest {

    private lateinit var context: Context
    private lateinit var index: SearchIndex

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("offline_search.db")
        index = SearchIndex(context)
    }

    @After
    fun tearDown() {
        index.close()
        context.deleteDatabase("offline_search.db")
    }

    private fun writePack(records: List<PlaceRecord>): File {
        val file = File.createTempFile("places", ".jsonl.gz")
        GZIPOutputStream(file.outputStream()).bufferedWriter().use { writer ->
            records.forEach { record ->
                writer.write(
                    """{"id":"${record.id}","name":"${record.name}","type":"${record.type}",""" +
                        """"lat":${record.lat},"lon":${record.lon}}""",
                )
                writer.write("\n")
            }
        }
        return file
    }

    @Test
    fun `imports a gzip pack and reports the row count`() {
        index.importPack(
            "andorra",
            writePack(
                listOf(
                    PlaceRecord("n1", "Andorra la Vella", "town", 42.5069, 1.5212),
                    PlaceRecord("n2", "Escaldes-Engordany", "town", 42.5090, 1.5340),
                    PlaceRecord("n3", "Ordino", "town", 42.5562, 1.5335),
                ),
            ),
        )
        assertEquals(3, index.count())
        assertEquals(3, index.countForRegion("andorra"))
    }

    @Test
    fun `prefix search finds a place by its first letters`() {
        index.importPack("andorra", writePack(listOf(PlaceRecord("n1", "Andorra la Vella", "town", 42.5069, 1.5212))))
        val results = index.search("Andor")
        assertTrue("expected a hit, got $results", results.isNotEmpty())
        assertEquals("Andorra la Vella", results.first().name)
        assertEquals(SearchSource.OFFLINE_INDEX, results.first().source)
    }

    @Test
    fun `search is case insensitive and handles multi word queries`() {
        index.importPack("andorra", writePack(listOf(PlaceRecord("n1", "Andorra la Vella", "town", 42.5069, 1.5212))))
        assertTrue(index.search("andorra vella").isNotEmpty())
        assertTrue(index.search("VELLA").isNotEmpty())
    }

    @Test
    fun `search returns nothing for an unknown place`() {
        index.importPack("andorra", writePack(listOf(PlaceRecord("n1", "Ordino", "town", 42.5562, 1.5335))))
        assertTrue(index.search("Tokyo").isEmpty())
    }

    @Test
    fun `search survives quotes and operators in the query`() {
        index.importPack("andorra", writePack(listOf(PlaceRecord("n1", "Ordino", "town", 42.5562, 1.5335))))
        index.search("\"Ordino")
        index.search("Ordino*")
        index.search("AND OR NOT")
        index.search("Ordino; DROP TABLE places;")
        assertEquals(1, index.count())
    }

    @Test
    fun `nearby search orders by distance and respects the radius`() {
        index.importPack(
            "andorra",
            writePack(
                listOf(
                    PlaceRecord("n1", "Andorra la Vella", "town", 42.5069, 1.5212),
                    PlaceRecord("n2", "Escaldes-Engordany", "town", 42.5090, 1.5340),
                    PlaceRecord("n3", "Ordino", "town", 42.5562, 1.5335),
                ),
            ),
        )
        val near = index.nearby(GeoPoint(42.5069, 1.5212), radiusMeters = 2_000.0)
        assertTrue("expected at least two places nearby", near.size >= 2)
        assertEquals("Andorra la Vella", near.first().name)
        assertTrue("Ordino is ~5.5 km away and must be excluded", near.none { it.name == "Ordino" })
    }

    @Test
    fun `re-importing a region replaces its rows instead of duplicating them`() {
        val file = writePack(listOf(PlaceRecord("n1", "Ordino", "town", 42.5562, 1.5335)))
        index.importPack("andorra", file)
        index.importPack("andorra", file)
        assertEquals(1, index.count())
    }

    @Test
    fun `deleting a region removes only its places`() {
        index.importPack("andorra", writePack(listOf(PlaceRecord("n1", "Ordino", "town", 42.5562, 1.5335))))
        index.importPack("other", writePack(listOf(PlaceRecord("n9", "Malta", "country", 35.9, 14.5))))
        assertEquals(2, index.count())
        index.deleteRegion("andorra")
        assertEquals(1, index.count())
        assertTrue(index.search("Ordino").isEmpty())
        assertTrue(index.search("Malta").isNotEmpty())
    }

    @Test
    fun `weighting puts cities above shops`() {
        assertTrue(SearchIndex.weightForType("city") < SearchIndex.weightForType("poi"))
        assertTrue(SearchIndex.weightForType("country") < SearchIndex.weightForType("city"))
    }

    @Test
    fun `fts terms are sanitised`() {
        assertEquals("Main* AND Street*", SearchIndex.toFtsTerms("Main Street"))
        assertEquals("", SearchIndex.toFtsTerms("   "))
        assertEquals("Ordino*", SearchIndex.toFtsTerms("Ordino;"))
    }

    @Test
    fun `repository parses typed coordinates`() {
        val result = SearchRepository.parseCoordinate("18.5204, 73.8567")
        assertNotNull(result)
        assertEquals(SearchSource.COORDINATE, result!!.source)
        assertEquals(18.5204, result.point.latitude, 1e-6)
        assertEquals(73.8567, result.point.longitude, 1e-6)
        assertNull(SearchRepository.parseCoordinate("not a coordinate"))
        assertNull(SearchRepository.parseCoordinate("91.0, 200.0"))
    }

    @Test
    fun `repository explains an empty index instead of returning nothing silently`() {
        val repository = SearchRepository(index)
        assertTrue(repository.search("Andorra").isEmpty())
        assertNotNull(repository.lastCoverageNote)
        assertTrue(repository.lastCoverageNote!!.contains("No offline search data"))
    }
}

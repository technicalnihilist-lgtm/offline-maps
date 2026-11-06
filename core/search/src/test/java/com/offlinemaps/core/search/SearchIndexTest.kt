package com.offlinemaps.core.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking

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
        assertEquals("Main* Street*", SearchIndex.toFtsTerms("Main Street"))
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
    fun `repository explains an empty index instead of returning nothing silently`() = runBlocking {
        val repository = SearchRepository(index)
        assertTrue(repository.search("Andorra").isEmpty())
        assertNotNull(repository.lastCoverageNote)
        assertTrue(repository.lastCoverageNote!!.contains("No offline search data"))
    }

    /**
     * Every repository call opens and reads the SQLite file, so none of them may run on the
     * caller's thread - for search that thread is the main thread (OPTIMIZATION.md A-02).
     *
     * The dispatcher here counts hops and runs the block inline, so the test stays single-threaded
     * while still proving the work goes through the dispatcher instead of executing where it was
     * called from.
     */
    @Test
    fun `every repository call is dispatched through the injected io dispatcher`() = runBlocking {
        val dispatcher = CountingDispatcher()
        val repository = SearchRepository(index, dispatcher)
        val file = writePack(listOf(PlaceRecord("n1", "Ordino", "town", 42.5562, 1.5335)))

        repository.importPack("andorra", file)
        repository.search("Ordino")
        repository.nearby(GeoPoint(42.5562, 1.5335))
        repository.indexSize()
        repository.removeRegion("andorra")
        repository.clear()

        assertEquals(
            "every repository call must hop to the io dispatcher, saw ${dispatcher.dispatches.get()}",
            6,
            dispatcher.dispatches.get(),
        )
    }

    @Test
    fun `repository returns hits and clears regions through the io dispatcher`() = runBlocking {
        val repository = SearchRepository(index, CountingDispatcher())
        repository.importPack("andorra", writePack(listOf(PlaceRecord("n1", "Ordino", "town", 42.5562, 1.5335))))
        assertTrue(repository.search("Ord").isNotEmpty())
        assertEquals(1, repository.indexSize())
        repository.removeRegion("andorra")
        assertTrue(repository.search("Ord").isEmpty())
    }

    /** OPTIMIZATION.md A-06: the warm-up opens the index and reports what is in it. */
    @Test
    fun `warm-up opens the index and reports the place count`() {
        index.importPack(
            "andorra",
            writePack(
                listOf(
                    PlaceRecord("n1", "Andorra la Vella", "town", 42.5069, 1.5212),
                    PlaceRecord("n2", "Ordino", "town", 42.5562, 1.5335),
                ),
            ),
        )
        assertEquals(2, index.warmUp())
        // The warm-up is read-only: it must not change a row.
        assertEquals(2, index.count())
        assertTrue(index.search("Andor").isNotEmpty())
    }

    @Test
    fun `warm-up of an empty index is harmless and reports zero`() {
        assertEquals(0, index.warmUp())
        assertEquals(0, index.count())
    }

    @Test
    fun `repository prewarm is one-shot and times the first search`() = runBlocking {
        val dispatcher = CountingDispatcher()
        val lines = mutableListOf<String>()
        val repository = SearchRepository(index, dispatcher, logger = { lines += it })
        repository.importPack("andorra", writePack(listOf(PlaceRecord("n1", "Ordino", "town", 42.5562, 1.5335))))
        val dispatchesAfterImport = dispatcher.dispatches.get()

        assertNull(repository.warmupMillis)
        repository.prewarmIndex()
        repository.prewarmIndex()
        assertEquals("the index must be opened once, not per call", dispatchesAfterImport + 1, dispatcher.dispatches.get())
        assertEquals(1, repository.warmupPlaces)
        assertNotNull(repository.warmupMillis)

        assertNull(repository.firstSearchMillis)
        repository.search("Ord")
        repository.search("Ord")
        assertNotNull("the first search after the warm-up must be timed", repository.firstSearchMillis)
        assertTrue("the timings must reach the logger, saw $lines", lines.size >= 2)
    }

    /**
     * OPTIMIZATION.md D-01: search only ever runs `term*` queries, so the FTS table keeps prefix
     * indexes for 2- and 3-character prefixes instead of scanning a term's whole doclist.
     */
    @Test
    fun `fts table is created with prefix indexes`() {
        index.count()
        val sql = ftsTableSql()
        assertTrue("expected a prefix index on places_fts, saw: $sql", sql.contains("prefix=\"2,3\""))
    }

    /**
     * The prefix indexes are a storage change, so an index written by version 1 must be rebuilt in
     * place: the places it holds survive, nothing is re-downloaded and search still answers.
     */
    @Test
    fun `upgrading a version 1 index keeps its places and adds the prefix index`() {
        writeVersion1Database()

        assertEquals(1, index.count())
        assertEquals("Ordino", index.search("Ord").first().name)
        assertTrue(index.search("Ord").isNotEmpty())
        assertTrue(
            "the upgrade must rebuild places_fts with the prefix index, saw: ${ftsTableSql()}",
            ftsTableSql().contains("prefix=\"2,3\""),
        )
    }

    /** Writes the version 1 schema straight to disk, as an install that predates D-01 would have it. */
    private fun writeVersion1Database() {
        val file = context.getDatabasePath("offline_search.db")
        file.parentFile?.mkdirs()
        file.delete()
        val v1 = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            v1.execSQL(
                """
                CREATE TABLE places (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    admin TEXT,
                    street TEXT,
                    region_id TEXT NOT NULL,
                    weight INTEGER NOT NULL DEFAULT 50
                )
                """.trimIndent(),
            )
            v1.execSQL("CREATE VIRTUAL TABLE places_fts USING fts4(name, admin, street, tokenize=unicode61)")
            v1.execSQL(
                "INSERT INTO places VALUES ('n1', 'Ordino', 'town', 42.5562, 1.5335, NULL, NULL, 'andorra', 20)",
            )
            v1.execSQL("INSERT INTO places_fts(rowid, name, admin, street) VALUES (1, 'Ordino', '', '')")
            v1.version = 1
        } finally {
            v1.close()
        }
    }

    private fun ftsTableSql(): String =
        index.readableDatabase.rawQuery("SELECT sql FROM sqlite_master WHERE name = 'places_fts'", null)
            .use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }

    /** Counts dispatches and runs them inline, so the test needs no second thread. */
    private class CountingDispatcher : CoroutineDispatcher() {
        val dispatches = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            block.run()
        }
    }
}

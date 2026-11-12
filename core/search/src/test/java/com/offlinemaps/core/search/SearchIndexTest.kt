package com.offlinemaps.core.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.offlinemaps.core.common.GeoBounds
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.SearchSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `a truncated pack still indexes the places it could read`() {
        val records = (1..300).map { PlaceRecord("n$it", "Place number $it of Andorra", "town", 42.5, 1.5) }
        val file = writePack(records)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 2))

        val stats = index.importPack("andorra", file)

        assertTrue("expected the readable prefix to be indexed, got ${stats.records}", stats.records > 0)
        assertTrue("expected the cut pack to be reported as truncated", stats.truncated)
        assertEquals(stats.records, index.countForRegion("andorra"))
        assertTrue("search must still work over what was indexed", index.search("Place").isNotEmpty())
    }

    @Test
    fun `a pack with a broken line keeps the sound rows`() {
        val file = File.createTempFile("places", ".jsonl.gz")
        GZIPOutputStream(file.outputStream()).bufferedWriter().use { writer ->
            writer.write("""{"id":"n1","name":"Ordino","type":"town","lat":42.5562,"lon":1.5335}""")
            writer.write("\n")
            writer.write("{not json at all\n")
            writer.write("""{"id":"n2","name":"Canillo","type":"village","lat":42.5667,"lon":1.6}""")
            writer.write("\n")
        }

        val stats = index.importPack("andorra", file)

        assertEquals(2, stats.records)
        assertEquals(1, stats.skippedLines)
        assertFalse(stats.truncated)
        assertTrue(stats.damaged)
        assertEquals(2, index.countForRegion("andorra"))
        assertTrue(index.search("Canillo").isNotEmpty())
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

    /**
     * OPTIMIZATION.md D-04: `nearby` bounds its scan by latitude, so the bounding-box select has to
     * read `idx_places_latlon`. A plan that says `SCAN places` is the regression this pins - the
     * query stays correct either way, it just stops being a lookup.
     */
    @Test
    fun `nearby bounding box select is answered from the lat lon index`() {
        importGrid(4_000)
        val plan = nearbyPlan()
        assertTrue("expected the bbox select to read idx_places_latlon, saw: $plan", plan.contains("idx_places_latlon"))
        assertFalse("the bbox select must not walk the whole table, saw: $plan", plan.contains("SCAN places"))
    }

    /**
     * OPTIMIZATION.md D-04: counting and deleting a region both filter on `region_id`, so both are
     * answered from `idx_places_region` rather than from a table scan.
     */
    @Test
    fun `region lookups are answered from the region index`() {
        importGrid(4_000)
        val countPlan = queryPlan("SELECT COUNT(*) FROM places WHERE region_id = ?", "andorra")
        val deletePlan = queryPlan("DELETE FROM places WHERE region_id = ?", "andorra")
        assertTrue("expected the region count to read idx_places_region, saw: $countPlan", countPlan.contains("idx_places_region"))
        assertTrue("expected the region delete to read idx_places_region, saw: $deletePlan", deletePlan.contains("idx_places_region"))
        assertFalse("the region count must not walk the whole table, saw: $countPlan", countPlan.contains("SCAN places"))
        assertFalse("the region delete must not walk the whole table, saw: $deletePlan", deletePlan.contains("SCAN places"))
    }

    /**
     * OPTIMIZATION.md D-04: an index file that reached an upgrade without the lookup indexes - the
     * version 1 fixture below is exactly that - must come out of the upgrade with both of them, and
     * with its places intact.
     */
    @Test
    fun `upgrading a version 1 index without lookup indexes adds both`() {
        writeVersion1Database()

        assertEquals(1, index.count())
        val names = indexNames()
        assertTrue("expected idx_places_latlon after the upgrade, saw: $names", "idx_places_latlon" in names)
        assertTrue("expected idx_places_region after the upgrade, saw: $names", "idx_places_region" in names)
        val plan = nearbyPlan()
        assertTrue("expected the upgraded index to answer nearby from idx_places_latlon, saw: $plan", plan.contains("idx_places_latlon"))
    }

    /**
     * OPTIMIZATION.md D-05: the bounding box is filtered in SQL, so a place outside the box is
     * never read - it is not fetched and then dropped in Kotlin.
     */
    @Test
    fun `nearby bounding box filter is applied in sql`() {
        index.importPack(
            "andorra",
            writePack(
                listOf(
                    PlaceRecord("in", "Inside the box", "town", 42.5069, 1.5212),
                    // ~0.5 degrees north: outside a 1.5 km box, so SQL must exclude it.
                    PlaceRecord("out", "Outside the box", "town", 42.5562, 1.5212),
                ),
            ),
        )
        val bounds = GeoBounds.of(listOf(GeoPoint(42.5069, 1.5212)))!!.expandedBy(1_500.0)
        val names = readableNames(bounds, rowCap = SearchIndex.rowCapFor(40))
        assertEquals("the box filter must run in SQL, saw: $names", listOf("Inside the box"), names)
    }

    /**
     * OPTIMIZATION.md D-05: the exact radius test is the only filter left in Kotlin. A place inside
     * the box but past the radius - a box corner - is dropped, and a place inside the radius is not.
     */
    @Test
    fun `nearby drops the box corners that are past the radius`() {
        index.importPack(
            "andorra",
            writePack(
                listOf(
                    PlaceRecord("near", "Just north", "town", 42.5150, 1.5212),
                    // Inside the 1.5 km box but ~1.6 km away diagonally: only the radius rejects it.
                    PlaceRecord("corner", "Box corner", "town", 42.5180, 1.5340),
                ),
            ),
        )
        val found = index.nearby(GeoPoint(42.5069, 1.5212), radiusMeters = 1_500.0)
        assertEquals(listOf("Just north"), found.map { it.name })
    }

    /**
     * OPTIMIZATION.md I-01: `nearby` builds its bounding box straight from the centre point instead
     * of going through `GeoBounds.of(listOf(center))!!`. The box must still collapse onto that point
     * when the radius is zero, so only a place at the exact centre comes back.
     */
    @Test
    fun `nearby with a zero radius keeps only the place at the centre`() {
        index.importPack(
            "andorra",
            writePack(
                listOf(
                    PlaceRecord("here", "At the centre", "town", 42.5069, 1.5212),
                    PlaceRecord("away", "A block away", "town", 42.5070, 1.5212),
                ),
            ),
        )
        val found = index.nearby(GeoPoint(42.5069, 1.5212), radiusMeters = 0.0)
        assertEquals(listOf("At the centre"), found.map { it.name })
    }

    /** OPTIMIZATION.md D-05: the row cap follows the requested limit and never exceeds its ceiling. */
    @Test
    fun `nearby row cap follows the limit and is bounded`() {
        assertEquals(20, SearchIndex.rowCapFor(5))
        assertEquals(160, SearchIndex.rowCapFor(40))
        assertEquals(SearchIndex.NEARBY_MAX_ROWS, SearchIndex.rowCapFor(10_000))
        assertTrue("a zero or negative limit still reads one row at most", SearchIndex.rowCapFor(0) <= 4)
    }

    /** OPTIMIZATION.md D-05: the select itself returns no more rows than the cap it is given. */
    @Test
    fun `nearby bounding box select respects the row cap`() {
        importGrid(4_000)
        val bounds = GeoBounds(42.40, 1.40, 42.60, 1.60)
        val names = readableNames(bounds, rowCap = 20)
        assertEquals("the select must stop at the cap it was given, saw ${names.size} rows", 20, names.size)
    }

    /**
     * OPTIMIZATION.md D-05: the cap must not cost correctness. With more rows in the box than the
     * cap allows, `nearby` still returns the nearest ones - the select orders by distance, so the
     * rows the cap drops are the farthest.
     */
    @Test
    fun `nearby keeps the nearest places when the box holds more rows than the cap`() {
        val center = GeoPoint(42.5069, 1.5212)
        // 60 places marching north from the centre, ~20 m apart: all inside the radius, and the
        // box holds far more of them than the cap for a limit of 5 (4 * 5 = 20 rows).
        index.importPack(
            "andorra",
            (1..60).asSequence().map { step ->
                PlaceRecord(
                    id = "step$step",
                    name = "Step $step",
                    type = "poi",
                    lat = center.latitude + step * 0.00018,
                    lon = center.longitude,
                )
            },
        )
        val found = index.nearby(center, radiusMeters = 2_000.0, limit = 5)
        assertEquals(5, found.size)
        assertEquals(listOf("Step 1", "Step 2", "Step 3", "Step 4", "Step 5"), found.map { it.name })
    }

    /** Names the bounding-box select returns for [bounds], bound exactly as `nearby` binds it. */
    private fun readableNames(bounds: GeoBounds, rowCap: Int): List<String> {
        val center = bounds.center
        return index.readableDatabase
            .rawQuery(SearchIndex.NEARBY_BBOX_SQL, SearchIndex.nearbyArgs(center, bounds, rowCap))
            .use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
    }

    /** EXPLAINs the bounding-box select with the arguments `nearby` really binds (D-05). */
    private fun nearbyPlan(): String {
        val bounds = GeoBounds(42.40, 1.40, 42.60, 1.60)
        return queryPlan(
            SearchIndex.NEARBY_BBOX_SQL,
            *SearchIndex.nearbyArgs(bounds.center, bounds, SearchIndex.rowCapFor(40)),
        )
    }

    /** Fills the index with [count] places spread over a one-degree grid around Andorra. */
    private fun importGrid(count: Int) {
        index.importPack(
            "andorra",
            (0 until count).asSequence().map { i ->
                PlaceRecord(
                    id = "g$i",
                    name = "Grid place $i",
                    type = "poi",
                    lat = 42.40 + (i % 100) / 100.0,
                    lon = 1.40 + (i / 100 % 100) / 100.0,
                )
            },
        )
    }

    private fun queryPlan(sql: String, vararg args: String): String =
        index.readableDatabase.rawQuery("EXPLAIN QUERY PLAN $sql", args).use { cursor ->
            // The plan row's columns differ across SQLite versions, so keep all of them.
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).joinToString(" ") { cursor.getString(it) ?: "" })
                }
            }.joinToString(" | ")
        }

    private fun indexNames(): Set<String> =
        index.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'places'",
            null,
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) cursor.getString(0)?.let { add(it) } }
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

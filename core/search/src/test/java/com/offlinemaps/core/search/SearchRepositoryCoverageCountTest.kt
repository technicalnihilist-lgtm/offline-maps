package com.offlinemaps.core.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OPTIMIZATION.md J-17: the coverage note is decided without counting the whole index when the
 * query already matched something.
 *
 * `SELECT COUNT(*)` walks every row of `places`, and it is only needed to tell "nothing is
 * installed" from "nothing matched" - both of which are answers to a query that returned no rows.
 * A query that returned rows can only have done so from a table that has some, so the count could
 * not change its note. These tests pin both halves: the count is skipped when there are results,
 * and it is still run - once - when there are none, with the same note each time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchRepositoryCoverageCountTest {

    private lateinit var context: Context
    private lateinit var index: SearchIndex

    /** How many times the repository asked the index how many places it holds. */
    private var counts = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("offline_search.db")
        counts = 0
        index = SearchIndex(context)
    }

    @After
    fun tearDown() {
        index.close()
        context.deleteDatabase("offline_search.db")
    }

    private fun repository(): SearchRepository = SearchRepository(
        index = index,
        io = InlineDispatcher(),
        countRows = {
            counts++
            index.count()
        },
    )

    private fun importAndorra() {
        val file = File.createTempFile("places", ".jsonl.gz")
        try {
            GZIPOutputStream(file.outputStream()).bufferedWriter().use { writer ->
                listOf(
                    PlaceRecord("n1", "Andorra la Vella", "town", 42.5069, 1.5212),
                    PlaceRecord("n2", "Ordino", "town", 42.5562, 1.5335),
                ).forEach { record ->
                    writer.write(
                        """{"id":"${record.id}","name":"${record.name}","type":"${record.type}",""" +
                            """"lat":${record.lat},"lon":${record.lon}}""",
                    )
                    writer.write("\n")
                }
            }
            index.importPack("andorra", file)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a query that matched does not count the index`() = runBlocking {
        importAndorra()
        val repository = repository()

        val results = repository.search("andorra")

        assertFalse("the query should have matched the imported place", results.isEmpty())
        assertEquals("Andorra la Vella", results.first().name)
        assertNull(repository.lastCoverageNote)
        assertEquals("a search with results must not count the index", 0, counts)
    }

    @Test
    fun `a query that matched nothing still counts the index and says so`() = runBlocking {
        importAndorra()
        val repository = repository()

        val results = repository.search("zzzzz")

        assertTrue(results.isEmpty())
        assertEquals(SearchRepository.NOTHING_MATCHED, repository.lastCoverageNote)
        assertEquals("the note needs the count exactly once", 1, counts)
    }

    @Test
    fun `an empty index still reports that nothing is installed`() = runBlocking {
        val repository = repository()

        val results = repository.search("andorra")

        assertTrue(results.isEmpty())
        assertEquals(SearchRepository.NO_DATA_NOTE, repository.lastCoverageNote)
        assertEquals(1, counts)
    }

    @Test
    fun `a repeated search with results keeps skipping the count`() = runBlocking {
        importAndorra()
        val repository = repository()

        repeat(5) {
            assertFalse(repository.search("ordino").isEmpty())
            assertNull(repository.lastCoverageNote)
        }

        assertEquals(0, counts)
    }

    /** Runs the block where it was dispatched from, so the test needs no second thread. */
    private class InlineDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    }
}

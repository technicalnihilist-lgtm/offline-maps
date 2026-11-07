package com.offlinemaps.core.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
 * A query with no letters or digits cannot match anything, so the repository answers it without
 * touching the index at all (OPTIMIZATION.md D-06). These tests pin the two halves of that: the
 * database file is never opened, and the answer and the coverage note are the ones the screen
 * already shows for a query that matched nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchRepositoryBlankQueryTest {

    private lateinit var context: Context
    private lateinit var index: SearchIndex
    private lateinit var repository: SearchRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("offline_search.db")
        index = SearchIndex(context)
        repository = SearchRepository(index = index, io = InlineDispatcher())
    }

    @After
    fun tearDown() {
        index.close()
        context.deleteDatabase("offline_search.db")
    }

    private fun databaseFile(): File = context.getDatabasePath("offline_search.db")

    @Test
    fun `a punctuation-only query answers empty without opening the index`() = runBlocking {
        val results = repository.search("!!!")

        assertTrue(results.isEmpty())
        assertEquals(SearchRepository.NOTHING_MATCHED, repository.lastCoverageNote)
        assertFalse("the index must not be opened for a query that cannot match", databaseFile().exists())
    }

    @Test
    fun `quotes and operators alone answer empty without opening the index`() = runBlocking {
        val results = repository.search("\"...\"*?!")

        assertTrue(results.isEmpty())
        assertEquals(SearchRepository.NOTHING_MATCHED, repository.lastCoverageNote)
        assertFalse(databaseFile().exists())
    }

    @Test
    fun `a whitespace-only query still publishes no note`() = runBlocking {
        val results = repository.search("   ")

        assertTrue(results.isEmpty())
        assertNull(repository.lastCoverageNote)
        assertFalse(databaseFile().exists())
    }

    @Test
    fun `a query with letters still opens the index and reports its coverage`() = runBlocking {
        val results = repository.search("Andorra")

        assertTrue(results.isEmpty())
        assertTrue(databaseFile().exists())
        assertEquals(SearchRepository.NO_DATA_NOTE, repository.lastCoverageNote)
    }

    /** Runs the block where it was dispatched from, so the test needs no second thread. */
    private class InlineDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    }
}

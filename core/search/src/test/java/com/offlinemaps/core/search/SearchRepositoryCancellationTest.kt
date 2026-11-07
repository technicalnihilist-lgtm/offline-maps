package com.offlinemaps.core.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A superseded search is cancelled by [com.offlinemaps.app.ui.QueryDebouncer] (OPTIMIZATION.md
 * D-02), but the index query behind it is a blocking SQLite call, so the cancellation lands after
 * that call returns, not inside it. These tests pin down the two things the repository must then
 * not do: publish the superseded query's coverage note, and record its timing as the first search
 * of the process (OPTIMIZATION.md D-03).
 *
 * The cancellation is injected through the repository's own clock, which runs inside the query
 * block right before the blocking call - the same place a newer keystroke would cancel from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchRepositoryCancellationTest {

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

    @Test
    fun `a search cancelled inside its query publishes no note and no timing`() = runBlocking {
        lateinit var job: Job
        val cancelOnFirstClock = AtomicBoolean(true)
        val repository = SearchRepository(
            index = index,
            io = InlineDispatcher(),
            clock = {
                if (cancelOnFirstClock.getAndSet(false)) job.cancel()
                0L
            },
        )

        job = launch(start = CoroutineStart.LAZY) { repository.search("Andorra") }
        job.start()
        job.join()

        assertTrue("the superseded search must be cancelled", job.isCancelled)
        assertNull("a cancelled search must not publish a coverage note", repository.lastCoverageNote)
        assertNull("a cancelled search must not claim the first-search timing", repository.firstSearchMillis)
    }

    @Test
    fun `the search after a cancelled one still publishes its own note`() = runBlocking {
        lateinit var job: Job
        val cancelNext = AtomicBoolean(true)
        val repository = SearchRepository(
            index = index,
            io = InlineDispatcher(),
            clock = {
                if (cancelNext.getAndSet(false)) job.cancel()
                0L
            },
        )

        job = launch(start = CoroutineStart.LAZY) { repository.search("Andorra") }
        job.start()
        job.join()
        assertNull(repository.lastCoverageNote)

        // Nothing cancels this one, so it behaves exactly as before.
        val results = repository.search("Andorra")
        assertTrue(results.isEmpty())
        assertNotNull(repository.lastCoverageNote)
        assertTrue(repository.lastCoverageNote!!.contains("No offline search data"))
    }

    /** Runs the block where it was dispatched from, so the test needs no second thread. */
    private class InlineDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    }
}

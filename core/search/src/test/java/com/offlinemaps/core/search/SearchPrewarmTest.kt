package com.offlinemaps.core.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests the one-shot search-index warm-up (OPTIMIZATION.md A-06): it must run once per process,
 * survive a failing warm-up, and record the cost of the first search afterwards.
 *
 * The clock is a fake that jumps a fixed amount per read, so the arithmetic is pinned down instead
 * of being timed by the wall clock.
 */
class SearchPrewarmTest {

    /** Clock that advances by [step] milliseconds on every read. */
    private class FakeClock(private val step: Long) {
        private var now = 1_000L
        fun read(): Long {
            val value = now
            now += step
            return value
        }
    }

    @Test
    fun `warm-up runs the warm block once and records its duration`() = runBlocking {
        val calls = AtomicInteger()
        val lines = mutableListOf<String>()
        val prewarm = SearchPrewarm(
            warm = { calls.incrementAndGet(); 42 },
            clock = FakeClock(step = 7).let { clock -> { clock.read() } },
            logger = { lines += it },
        )

        val elapsed = prewarm.run()

        assertEquals(1, calls.get())
        assertEquals(42, prewarm.places)
        assertEquals(7L, elapsed)
        assertEquals(7L, prewarm.warmupMillis)
        assertTrue(prewarm.hasRun)
        assertEquals(1, lines.size)
        assertTrue("log line should carry the count, was: ${lines[0]}", lines[0].contains("42 places"))
        assertTrue("log line should carry the cost, was: ${lines[0]}", lines[0].contains("7 ms"))
    }

    @Test
    fun `a second warm-up does not open the index again`() = runBlocking {
        val calls = AtomicInteger()
        val prewarm = SearchPrewarm(warm = { calls.incrementAndGet(); 5 }, clock = FakeClock(step = 3).let { c -> { c.read() } })

        val first = prewarm.run()
        val second = prewarm.run()
        val third = prewarm.run()

        assertEquals("the index must be opened once per process", 1, calls.get())
        assertEquals(first, second)
        assertEquals(first, third)
    }

    @Test
    fun `a failing warm-up is recorded as empty instead of throwing`() = runBlocking {
        val lines = mutableListOf<String>()
        val prewarm = SearchPrewarm(
            warm = { error("database is locked") },
            clock = FakeClock(step = 2).let { c -> { c.read() } },
            logger = { lines += it },
        )

        val elapsed = prewarm.run()

        assertNotNull(elapsed)
        assertEquals(0, prewarm.places)
        assertTrue(prewarm.hasRun)
        assertTrue("log line should say nothing is installed, was: ${lines.single()}", lines.single().contains("nothing installed"))
    }

    @Test
    fun `only the first search after the warm-up is recorded`() = runBlocking {
        val lines = mutableListOf<String>()
        val prewarm = SearchPrewarm(
            warm = { 120 },
            clock = FakeClock(step = 4).let { c -> { c.read() } },
            logger = { lines += it },
        )

        assertNull(prewarm.firstSearchMillis)
        prewarm.run()
        prewarm.recordSearch(31)
        prewarm.recordSearch(99)

        assertEquals(31L, prewarm.firstSearchMillis)
        assertEquals("one line for the warm-up and one for the first search", 2, lines.size)
        assertTrue(
            "the search line should mention the prewarm, was: ${lines[1]}",
            lines[1].contains("31 ms") && lines[1].contains("after a 4 ms prewarm"),
        )
    }

    @Test
    fun `a search before any warm-up is still recorded and says so`() {
        val lines = mutableListOf<String>()
        val prewarm = SearchPrewarm(warm = { 0 }, logger = { lines += it })

        prewarm.recordSearch(12)

        assertEquals(12L, prewarm.firstSearchMillis)
        assertFalse(prewarm.hasRun)
        assertTrue(
            "the search line should admit there was no prewarm, was: ${lines.single()}",
            lines.single().contains("with no prewarm"),
        )
    }
}

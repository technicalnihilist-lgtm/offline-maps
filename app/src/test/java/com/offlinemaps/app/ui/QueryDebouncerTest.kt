package com.offlinemaps.app.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Search is typed one character at a time and each character used to reach SQLite
 * (OPTIMIZATION.md D-02). These tests pin down that only the query the user paused on runs, that a
 * superseded search cannot land afterwards, and that clearing the field drops the pending work.
 */
class QueryDebouncerTest {

    /**
     * Virtual time to step past, comfortably longer than the window. `advanceUntilIdle` is not used:
     * it leaves the background scope alone, so the debounced job would never get to run.
     */
    private val SETTLE_MILLIS = 1_000L

    @Test
    fun `a burst of keystrokes runs only the last query`() = runTest {
        val debouncer = QueryDebouncer(backgroundScope, debounceMillis = 120)
        val seen = mutableListOf<String>()

        for (query in listOf("b", "be", "ben", "beng", "benga", "bengal")) {
            debouncer.submit(query) { seen += it }
            advanceTimeBy(20)
        }

        // Nothing has run yet: every keystroke arrived inside the debounce window.
        assertThat(seen).isEmpty()
        assertThat(debouncer.dispatched).isEqualTo(0)

        advanceTimeBy(SETTLE_MILLIS)

        assertThat(seen).containsExactly("bengal")
        assertThat(debouncer.dispatched).isEqualTo(1)
        assertThat(debouncer.superseded).isEqualTo(5)
        assertThat(debouncer.hasPending).isFalse()
    }

    @Test
    fun `a pause longer than the window runs the query`() = runTest {
        val debouncer = QueryDebouncer(backgroundScope, debounceMillis = 120)
        val seen = mutableListOf<String>()

        debouncer.submit("goa") { seen += it }
        advanceTimeBy(119)
        assertThat(seen).isEmpty()

        advanceTimeBy(2)
        assertThat(seen).containsExactly("goa")
        assertThat(debouncer.dispatched).isEqualTo(1)
        assertThat(debouncer.superseded).isEqualTo(0)
    }

    @Test
    fun `a newer query stops the older one from applying its result`() = runTest {
        val debouncer = QueryDebouncer(backgroundScope, debounceMillis = 120)
        val applied = mutableListOf<String>()

        debouncer.submit("bengaluru") { query ->
            // Stands in for the SQLite round trip: the result arrives after the user has typed on.
            kotlinx.coroutines.delay(50)
            applied += query
        }
        advanceTimeBy(130) // the first search is now running
        debouncer.submit("bengaluruu") { query -> applied += query }
        advanceTimeBy(SETTLE_MILLIS)

        assertThat(applied).containsExactly("bengaluruu")
    }

    @Test
    fun `cancel drops the pending search`() = runTest {
        val debouncer = QueryDebouncer(backgroundScope, debounceMillis = 120)
        val seen = mutableListOf<String>()

        debouncer.submit("goa") { seen += it }
        debouncer.cancel()
        advanceTimeBy(SETTLE_MILLIS)

        assertThat(seen).isEmpty()
        assertThat(debouncer.dispatched).isEqualTo(0)
        assertThat(debouncer.hasPending).isFalse()
    }

    @Test
    fun `default window is 120 milliseconds`() {
        assertThat(QueryDebouncer.DEFAULT_DEBOUNCE_MILLIS).isEqualTo(120L)
    }
}

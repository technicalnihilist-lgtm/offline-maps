package com.offlinemaps.core.search

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot warm-up of the offline search index (OPTIMIZATION.md A-06).
 *
 * The SQLite file behind search is opened lazily, on the first query, so the first search after a
 * cold start pays for opening the database, reading the FTS segment list and preparing the
 * statements - work that has nothing to do with what the user typed. [SearchIndex.warmUp] does that
 * work once per process, in the background, as soon as the first frame is out, and this class keeps
 * the "once" and the measurement.
 *
 * Two numbers come out of it: how long the warm-up itself cost, and how long the first real search
 * took afterwards. Both are handed to [logger], which the app points at a debug-only log call, so
 * the improvement is measured rather than assumed and release builds pay nothing. Nothing here is
 * user visible: no state, no message, no timing on screen.
 *
 * The clock is injected so a JVM test can pin the arithmetic down.
 */
class SearchPrewarm(
    private val warm: suspend () -> Int,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val logger: (String) -> Unit = {},
) {

    /** Guards [run] so the index is opened at most once per process. */
    private val claimed = AtomicBoolean(false)

    private val firstSearch = AtomicReference<Long?>(null)

    /** Places in the index when the warm-up ran; null until it has run. */
    @Volatile
    var places: Int? = null
        private set

    /** How long opening and touching the index took, in milliseconds; null until it has run. */
    @Volatile
    var warmupMillis: Long? = null
        private set

    /** How long the first search after the warm-up took, in milliseconds; null until one has run. */
    val firstSearchMillis: Long? get() = firstSearch.get()

    /** True once a warm-up has been started, whether it succeeded or not. */
    val hasRun: Boolean get() = claimed.get()

    /**
     * Opens and touches the index, once per process.
     *
     * A second call does not repeat the work; it returns whatever the first call recorded (null if
     * that call is still in flight). A warm-up that throws is recorded as an empty index rather
     * than propagated: this is a performance hint, so it must never be able to break startup.
     */
    suspend fun run(): Long? {
        if (!claimed.compareAndSet(false, true)) return warmupMillis
        val startedAt = clock()
        val count = runCatching { warm() }.getOrDefault(0)
        val elapsed = clock() - startedAt
        places = count
        warmupMillis = elapsed
        logger(
            if (count > 0) {
                "search prewarm: index opened with $count places in $elapsed ms"
            } else {
                "search prewarm: index opened, nothing installed yet ($elapsed ms)"
            },
        )
        return elapsed
    }

    /**
     * Records the cost of a search. Only the first one after a warm-up is kept and logged; the
     * point is the cold-start comparison, not a profile of every keystroke.
     */
    fun recordSearch(durationMillis: Long) {
        if (!firstSearch.compareAndSet(null, durationMillis)) return
        val warm = warmupMillis?.let { "after a $it ms prewarm" } ?: "with no prewarm"
        logger("search: first query took $durationMillis ms ($warm)")
    }
}

package com.offlinemaps.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Collapses a burst of keystrokes into a single search (OPTIMIZATION.md D-02).
 *
 * Typing "bengaluru" used to run nine SQLite queries, eight of which were thrown away before the
 * user saw their results. [submit] cancels whatever was pending and schedules the work
 * [debounceMillis] later, so a pause in typing is what actually reaches the database.
 *
 * The job is cancelled rather than left to finish: a superseded search never runs, and one that
 * had already started cannot write its stale results into the state afterwards.
 */
class QueryDebouncer(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) {

    private var pending: Job? = null

    /** Queries that reached the action, i.e. survived the debounce window. */
    var dispatched: Int = 0
        private set

    /** Queries dropped because a newer one arrived first. */
    var superseded: Int = 0
        private set

    val hasPending: Boolean
        get() = pending?.isActive == true

    /** Schedules [action] for [query], replacing any search still waiting or still running. */
    fun submit(query: String, action: suspend (String) -> Unit) {
        if (hasPending) superseded++
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMillis)
            dispatched++
            action(query)
        }
    }

    /** Drops the pending search, if any. */
    fun cancel() {
        if (hasPending) superseded++
        pending?.cancel()
        pending = null
    }

    companion object {
        /**
         * Short enough that a fast typist does not feel the delay, long enough that ordinary typing
         * lands inside one window. Deliberately below the ~200 ms that starts to read as lag.
         */
        const val DEFAULT_DEBOUNCE_MILLIS: Long = 120L
    }
}

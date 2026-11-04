package com.offlinemaps.core.offline

/**
 * What a single progress tick should do.
 *
 * [publishUi] - republish the in-memory `DownloadRuntime` (cheap: one immutable map copy) so the
 * progress bar stays smooth. [persist] - also write the byte count to the [DownloadStore], which is
 * a Room UPDATE and the expensive half of the tick.
 */
data class ProgressPublish(val publishUi: Boolean, val persist: Boolean) {
    companion object {
        val NONE = ProgressPublish(publishUi = false, persist = false)
        val BOTH = ProgressPublish(publishUi = true, persist = true)
    }
}

/**
 * Decides how often a running download republishes progress, and - separately and far less often -
 * how often that progress is written to the durable store.
 *
 * A transfer reads its body in fixed 128 KB chunks, so publishing per chunk would mean thousands of
 * store writes for a large map package. The two cadences are deliberately different: the UI state
 * update is free and keeps the existing 250 ms rhythm (so nothing on screen changes), while the
 * store write - which only matters after a process death, and even then only for the number shown
 * before the part file is re-measured on resume - is held to one every couple of seconds.
 *
 * Pure and clock-injected, so the cadence is testable without sleeping.
 */
class ProgressPublishGate(
    private val uiIntervalMillis: Long = DEFAULT_UI_INTERVAL_MILLIS,
    private val storeIntervalMillis: Long = DEFAULT_STORE_INTERVAL_MILLIS,
) {
    private var lastUiAt: Long? = null
    private var lastStoreAt: Long? = null

    /** UI republications decided so far - diagnostics and tests. */
    var uiPublishes: Long = 0L
        private set

    /** Store writes decided so far - diagnostics and tests. */
    var storeWrites: Long = 0L
        private set

    init {
        require(uiIntervalMillis > 0L) { "uiIntervalMillis must be positive" }
        require(storeIntervalMillis > 0L) { "storeIntervalMillis must be positive" }
    }

    /**
     * Records a tick at [nowMillis] and reports what to do with it.
     *
     * [force] is for the ticks that must not be dropped whatever the interval - the end of a
     * transfer, so the store ends up holding the final byte count.
     */
    fun decide(nowMillis: Long, force: Boolean = false): ProgressPublish {
        val publishUi = force || due(lastUiAt, nowMillis, uiIntervalMillis)
        // A store write rides along with a UI publish, never on its own: there is nothing to write
        // that the caller would not have published anyway, and a slot must never be spent unused.
        val persist = publishUi && (force || due(lastStoreAt, nowMillis, storeIntervalMillis))

        if (publishUi) {
            lastUiAt = nowMillis
            uiPublishes++
        }
        if (persist) {
            lastStoreAt = nowMillis
            storeWrites++
        }
        return ProgressPublish(publishUi = publishUi, persist = persist)
    }

    private fun due(last: Long?, nowMillis: Long, intervalMillis: Long): Boolean = when {
        last == null -> true
        // A wall clock that stepped backwards must not starve progress until it catches up.
        nowMillis < last -> true
        else -> nowMillis - last >= intervalMillis
    }

    companion object {
        /** The cadence the progress bar has always used. */
        const val DEFAULT_UI_INTERVAL_MILLIS: Long = 250L

        /** At most one store write every two seconds per running download. */
        const val DEFAULT_STORE_INTERVAL_MILLIS: Long = 2_000L
    }
}

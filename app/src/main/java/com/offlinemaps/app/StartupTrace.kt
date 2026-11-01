package com.offlinemaps.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log

/**
 * Cold-start phase timings (OPTIMIZATION.md A-03).
 *
 * MapLibre used to be initialised from `Application.onCreate` and the style document was rebuilt on
 * every read of the UI state, both before the map view existed. Both now happen when the view is
 * attached, so this trace records the phases that matter and prints a single line per cold start in
 * debuggable builds. That line is how the difference is measured rather than assumed: release
 * builds pay nothing (the logger is a no-op) and nothing is written anywhere.
 *
 * The trace is a plain class driven by an injected clock so a JVM test can pin the arithmetic down;
 * [process] is the instance the app itself uses.
 */
class StartupTrace(
    private val clock: () -> Long,
    private val logger: (String) -> Unit,
) {

    private var origin: Long? = null
    private var mapLibreInitStartedAt: Long? = null
    private var mapLibreInitMillis: Long? = null
    private var firstStyleMillis: Long? = null

    /** Marks the start of the cold start; the first thing `Application.onCreate` does. */
    fun begin() {
        origin = clock()
        mapLibreInitStartedAt = null
        mapLibreInitMillis = null
        firstStyleMillis = null
    }

    fun mapLibreInitStarted() {
        if (origin != null) mapLibreInitStartedAt = clock()
    }

    fun mapLibreInitFinished() {
        val startedAt = mapLibreInitStartedAt ?: return
        mapLibreInitMillis = clock() - startedAt
    }

    /**
     * Records the first style application of the process, relative to [begin], and logs the
     * breakdown. Later calls are ignored: the trace describes cold start, not every map open.
     */
    fun firstStyleApplied() {
        val start = origin ?: return
        if (firstStyleMillis != null) return
        firstStyleMillis = clock() - start
        logger(summary() ?: return)
    }

    /** One-line breakdown, or null when [begin] has not run. */
    fun summary(): String? {
        if (origin == null) return null
        val mapLibre = mapLibreInitMillis?.let { "$it ms" } ?: "not initialised"
        val style = firstStyleMillis?.let { "$it ms after Application.onCreate" } ?: "not applied"
        return "cold start: MapLibre init $mapLibre, first style applied $style"
    }

    companion object {
        const val TAG = "StartupTrace"

        private var debugBuild = false

        /** The instance the app drives; it only logs when the build is debuggable. */
        val process = StartupTrace(
            clock = { SystemClock.elapsedRealtime() },
            logger = { line -> if (debugBuild) Log.d(TAG, line) },
        )

        /** Enables the timing line for debuggable builds; cheap and silent everywhere else. */
        fun install(app: Application) {
            debugBuild = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }
    }
}

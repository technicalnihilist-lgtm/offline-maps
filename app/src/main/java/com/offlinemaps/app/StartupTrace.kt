package com.offlinemaps.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log

/**
 * Cold-start phase timings (OPTIMIZATION.md A-03, A-07).
 *
 * MapLibre used to be initialised from `Application.onCreate` and the style document was rebuilt on
 * every read of the UI state, both before the map view existed. Both now happen when the view is
 * attached, so this trace records the phases that matter and prints a single line per cold start in
 * debuggable builds. That line is how the difference is measured rather than assumed: release
 * builds pay nothing (the logger is a no-op) and nothing is written anywhere.
 *
 * A-07 generalised the two hand-wired measurements into a small Trace/Timber-style wrapper: any
 * phase can be wrapped with [trace], or bracketed with [beginPhase]/[endPhase] when it cannot be
 * expressed as a block, and [finishStartup] logs one breakdown line listing every phase in the order
 * it finished plus the totals. The MapLibre-specific entry points remain because callers and tests
 * already use them; they are now thin aliases over the same phase machinery, so their numbers and
 * their log line are unchanged.
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
    private var firstFrameMillis: Long? = null

    /** Finished phases in the order they finished: label to duration in milliseconds. */
    private val phases = LinkedHashMap<String, Long>()

    /** Phases that are currently open: label to the moment they started. */
    private val running = HashMap<String, Long>()

    private var breakdownLogged = false

    /** Marks the start of the cold start; the first thing `Application.onCreate` does. */
    fun begin() {
        origin = clock()
        mapLibreInitStartedAt = null
        mapLibreInitMillis = null
        firstStyleMillis = null
        firstFrameMillis = null
        phases.clear()
        running.clear()
        breakdownLogged = false
    }

    /**
     * Opens the phase [label]. Ignored before [begin], and a second open of the same label restarts
     * it, so a phase cannot be counted twice by accident.
     */
    fun beginPhase(label: String) {
        if (origin == null) return
        running[label] = clock()
    }

    /** Closes the phase [label] and records its duration; a phase that was never opened is ignored. */
    fun endPhase(label: String) {
        val startedAt = running.remove(label) ?: return
        phases[label] = clock() - startedAt
    }

    /**
     * Runs [block] as the phase [label], recording its duration even when the block throws, and
     * returns whatever the block returned. This is the wrapper the cold-start call sites use.
     */
    fun <T> trace(label: String, block: () -> T): T {
        beginPhase(label)
        try {
            return block()
        } finally {
            endPhase(label)
        }
    }

    /** Records when the first frame reached the screen, relative to [begin]. Later calls are ignored. */
    fun firstFrameReached() {
        val start = origin ?: return
        if (firstFrameMillis != null) return
        firstFrameMillis = clock() - start
    }

    fun mapLibreInitStarted() {
        if (origin != null) mapLibreInitStartedAt = clock()
        beginPhase(PHASE_MAP_LIBRE_INIT)
    }

    fun mapLibreInitFinished() {
        if (mapLibreInitStartedAt == null) return
        endPhase(PHASE_MAP_LIBRE_INIT)
        mapLibreInitMillis = phases[PHASE_MAP_LIBRE_INIT]
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

    /** One-line breakdown of the named phases, or null when [begin] has not run. */
    fun summary(): String? {
        if (origin == null) return null
        val mapLibre = mapLibreInitMillis?.let { "$it ms" } ?: "not initialised"
        val style = firstStyleMillis?.let { "$it ms after Application.onCreate" } ?: "not applied"
        return "cold start: MapLibre init $mapLibre, first style applied $style"
    }

    /**
     * Every finished phase in completion order, followed by the elapsed marks, followed by the total
     * time since [begin]. Null when [begin] has not run.
     */
    fun breakdown(): String? {
        val start = origin ?: return null
        val parts = ArrayList<String>(phases.size + 2)
        phases.forEach { (label, millis) -> parts += "$label $millis ms" }
        firstFrameMillis?.let { parts += "first frame at $it ms" }
        firstStyleMillis?.let { parts += "first style at $it ms" }
        val total = firstStyleMillis ?: (clock() - start)
        val detail = if (parts.isEmpty()) "no phases recorded" else parts.joinToString(", ")
        return "cold start breakdown: $detail | total $total ms"
    }

    /**
     * Logs [breakdown] once per cold start. Called when the startup window closes, so the line
     * includes everything that ran before the first frame and the work queued just after it.
     */
    fun finishStartup() {
        if (breakdownLogged) return
        val line = breakdown() ?: return
        breakdownLogged = true
        logger(line)
    }

    /** Duration recorded for [label], or null when it never finished. For diagnostics and tests. */
    fun phaseMillis(label: String): Long? = phases[label]

    companion object {
        const val TAG = "StartupTrace"

        /** Phase labels shared with the call sites, so the log reads the same everywhere. */
        const val PHASE_APPLICATION_ONCREATE = "Application.onCreate"
        const val PHASE_ACTIVITY_ONCREATE = "MainActivity.onCreate"
        const val PHASE_MAP_LIBRE_INIT = "MapLibre init"
        const val PHASE_FIRST_FRAME_WORK = "post-first-frame work"

        private var debugBuild = false

        /** The instance the app drives; it only logs when the build is debuggable. */
        val process = StartupTrace(
            clock = { SystemClock.elapsedRealtime() },
            logger = { line -> if (debugBuild) Log.d(TAG, line) },
        )

        /** Enables the timing lines for debuggable builds; cheap and silent everywhere else. */
        fun install(app: Application) {
            debugBuild = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }
    }
}

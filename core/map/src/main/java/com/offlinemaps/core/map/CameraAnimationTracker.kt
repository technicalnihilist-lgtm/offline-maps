package com.offlinemaps.core.map

/**
 * Records the animated camera move this app started, so the next one can replace it instead of
 * queueing behind it.
 *
 * MapLibre runs an animated move as a flight on the renderer: it interpolates for the whole
 * duration and only stops early when something cancels it. Rapid taps ("my location", the zoom
 * buttons, picking a second search result while the first is still flying) therefore ask for a
 * second flight before the first has landed, and the picture spends the whole time in transit. The
 * fix is to stop the flight that is running before issuing the next one, which needs an app-side
 * answer to "is one still running?" - that is what this class keeps.
 *
 * The bookkeeping is by token, not by a single boolean, because MapLibre reports the end of a
 * cancelled flight on its own handler: `cancelTransitions()` posts `onCancel` to the main looper,
 * so that callback arrives *after* the move that replaced it has already started. A plain flag
 * would be cleared by that late callback and the new flight would be believed to be over. Each
 * [start] bumps the generation, and an [finish]/[cancel] carrying an older token is ignored.
 *
 * Plain Kotlin, no map and no Android: the state machine is what needs testing, not the renderer.
 */
class CameraAnimationTracker {

    private var generation: Long = 0L
    private var inFlight: Boolean = false

    /** True while an animation this tracker started has neither finished nor been cancelled. */
    val isInFlight: Boolean get() = inFlight

    /** Token of the animation currently in flight; pass it back to [finish] or [cancel]. */
    val currentToken: Long get() = generation

    /** Animated moves started. */
    var started: Int = 0
        private set

    /** Starts that replaced an animation that had not landed yet. */
    var interrupted: Int = 0
        private set

    /** Animations that ran to the end. */
    var finished: Int = 0
        private set

    /** Animations stopped before the end, by the app or by a user gesture. */
    var cancelled: Int = 0
        private set

    /**
     * Registers an animated move about to be issued and returns its token.
     *
     * A start while one is still in flight counts as [interrupted]: the caller is expected to stop
     * the old flight first (or to have stopped it already).
     */
    fun start(): Long {
        if (inFlight) interrupted++
        generation++
        inFlight = true
        started++
        return generation
    }

    /** The animation identified by [token] reached its target. */
    fun finish(token: Long) = end(token, completed = true)

    /** The animation identified by [token] was stopped before its target. */
    fun cancel(token: Long) = end(token, completed = false)

    /**
     * Forgets the animation in flight without counting it as either outcome.
     *
     * Used when the flight was stopped out of band (the style was replaced, the map was destroyed)
     * or right after an explicit cancel, so the callback that follows is treated as superseded
     * rather than as the end of the next animation.
     */
    fun reset() {
        generation++
        inFlight = false
    }

    private fun end(token: Long, completed: Boolean) {
        // A late or superseded callback: it describes an animation that has already been replaced.
        if (token != generation || !inFlight) return
        inFlight = false
        if (completed) finished++ else cancelled++
    }
}

package com.offlinemaps.core.location

/**
 * Decides whether the explore-mode position stream should be registered at this moment
 * (OPTIMIZATION.md G-04).
 *
 * The app holds no wake lock of its own - there is no `PowerManager` call anywhere in it - but what
 * keeps a phone awake on its behalf is the GPS subscription, and that subscription used to outlive
 * everything that could read it. Tapping "my location" once and then locking the screen left both
 * providers registered, with the distance filter and the time interval still driving fixes into a
 * stopped screen: the map is not visible, nothing displays the result, and the radio stays on until
 * the view model dies. On a walk or a drive with the phone in a pocket that is the whole session.
 *
 * This is the small state machine the screen drives instead of deciding by hand. The stream is
 * *wanted* only after the user asked for it, it is *held* only while the screen showing it is
 * started, and it comes back on its own when the screen returns. Nothing here touches what a fix
 * looks like, the cadence of the request or the filter applied to it - only how long the
 * registration is allowed to live.
 *
 * Duplicate events are deliberately ignored: `LifecycleRegistry` re-delivers `ON_START` when an
 * observer is added to an already-started owner, and a rotation re-delivers the whole run, so a
 * second `START` for a stream that is already running must not register it twice.
 */
class ExploreStreamLifetime {

    /** What the owner should do with the stream after a call. */
    enum class Action {
        /** Nothing changed: leave the registration exactly as it is. */
        NONE,

        /** The stream should be registered now. */
        START,

        /** The stream should be released now; the request is kept for the next [START]. */
        STOP,
    }

    /** The user asked for position updates and has not released them. */
    private var wanted = false

    /** Whether the screen that shows the position is started. */
    private var visible = true

    /** Whether the stream is registered right now. */
    private var running = false

    /**
     * The user asked for the explore stream (the "my location" button, or a granted permission).
     * The caller registers it; this only records that it is wanted and held.
     */
    fun onRequested() {
        wanted = true
        running = true
    }

    /**
     * The stream was released for good - the owner is going away - so a later visibility change
     * must not bring it back. A new [onRequested] is what restarts it.
     */
    fun onReleased() {
        wanted = false
        running = false
    }

    /**
     * The screen was started ([visible] `true`) or stopped.
     *
     * Returns [Action.STOP] when a held stream should be released because the screen went away, and
     * [Action.START] when a stream released for that reason should come back. A screen that went
     * away before the user ever asked for the stream changes nothing, and a repeated event for the
     * state the stream is already in is [Action.NONE].
     */
    fun onVisibilityChanged(visible: Boolean): Action {
        if (this.visible == visible) return Action.NONE
        this.visible = visible
        if (!wanted) return Action.NONE
        return if (visible) {
            if (running) {
                Action.NONE
            } else {
                running = true
                Action.START
            }
        } else {
            if (running) {
                running = false
                Action.STOP
            } else {
                Action.NONE
            }
        }
    }

    /** True while a registration is expected to be live. Diagnostics and assertions only. */
    fun isRunning(): Boolean = running
}

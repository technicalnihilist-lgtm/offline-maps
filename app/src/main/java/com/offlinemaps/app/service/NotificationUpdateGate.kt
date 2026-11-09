package com.offlinemaps.app.service

/**
 * Decides whether the navigation notification is worth re-posting.
 *
 * The session state behind the notification is recomputed on every accepted GPS fix - roughly once
 * a second - and the service used to answer each of those with a fresh `NotificationCompat.Builder`,
 * two `PendingIntent` lookups and a `notify` across the binder to the system server. Nothing on
 * screen justified that rate: the notification has two pieces of text, and only one of them can
 * change in a way the driver cares about between fixes.
 *
 * * The **instruction** ("Turn left onto MG Road") is what the notification is for. A change there is
 *   posted immediately, whatever the clock says, because it is the one line that must never lag.
 * * The **remaining distance** ticks every second. Re-posting it that often buys nothing a human can
 *   read, so it is refreshed at most once per [minDistanceIntervalMs] - the text on screen is at most
 *   that far behind, and the notification is posted a handful of times a minute instead of sixty.
 *
 * Nothing about the notification itself changes: same title, same text, same action, same channel.
 * Only the rate at which it is handed over does.
 *
 * Pure Kotlin, no Android types and a swappable [clock], so it is unit-tested on the JVM.
 */
internal class NotificationUpdateGate(
    private val minDistanceIntervalMs: Long = DEFAULT_DISTANCE_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var lastTitle: String? = null
    private var lastText: String? = null
    private var lastPostAtMs: Long = 0L
    private var hasPosted: Boolean = false

    /** Posts the gate has allowed. */
    var posted: Int = 0
        private set

    /** Updates dropped as either identical to the posted one or too soon after it. */
    var skipped: Int = 0
        private set

    /**
     * Returns true when a notification carrying [title] and [text] should be posted now. A post that
     * is allowed becomes the new reference; a skipped one leaves the reference alone, so a change
     * that arrives inside the interval is still posted once the interval has passed rather than
     * being lost.
     */
    fun shouldPost(title: String, text: String): Boolean {
        val now = clock()
        val due = when {
            !hasPosted -> true
            title != lastTitle -> true
            text == lastText -> false
            now - lastPostAtMs >= minDistanceIntervalMs -> true
            else -> false
        }
        if (due) record(title, text, now) else skipped++
        return due
    }

    /**
     * Marks [title]/[text] as already on screen without going through the interval - used for the
     * placeholder notification posted by `startForeground`, which the system shows directly.
     */
    fun record(title: String, text: String) {
        record(title, text, clock())
    }

    private fun record(title: String, text: String, now: Long) {
        lastTitle = title
        lastText = text
        lastPostAtMs = now
        hasPosted = true
        posted++
    }

    companion object {
        /**
         * How stale the remaining-distance line may be. Five seconds is short enough that the number
         * still reads as live while cutting the posts of a one-fix-per-second session by about
         * eighty percent.
         */
        const val DEFAULT_DISTANCE_INTERVAL_MS: Long = 5_000L
    }
}

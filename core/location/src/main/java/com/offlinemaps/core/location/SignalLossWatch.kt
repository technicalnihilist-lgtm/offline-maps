package com.offlinemaps.core.location

/**
 * Decides when the position stream a session is following has been lost.
 *
 * `LocationSource.updates()` keeps a listener registered with the platform, and that registration
 * survives the provider being switched off and on again - so the session goes on running with no
 * crash, but it also goes on showing a position that stopped moving, with nothing to say why. Two
 * things mean "lost": every provider the stream was registered on has been disabled (the driver
 * turned the location toggle off, or the device lost the receiver), or no fix has arrived for
 * [staleAfterMillis] (a tunnel, a car park, a dead antenna). The first is known the moment the
 * platform reports it; the second is only knowable from the clock, which is why the session polls
 * [isLost] instead of waiting for an update that may never come.
 *
 * Loss is reported, never acted on: the last known position and the last navigation state stay
 * exactly as they were, because a frozen but honest screen is what a driver needs, and a session
 * that ended itself on a lost fix would be worse than one that waits.
 *
 * The clock used is the fix's own timestamp (`Location.getTime()`), so a fix re-emitted by
 * `onProviderDisabled` - which is the last *known* one, not a new one - cannot make a lost stream
 * look alive again.
 *
 * Pure Kotlin, no Android types, so it is unit-tested on the JVM.
 */
class SignalLossWatch(
    private val staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
) {

    /** Providers this stream is (or was) registered on, as the platform reported them. */
    private val providers = LinkedHashSet<String>()
    private val disabled = LinkedHashSet<String>()
    private var lastFixAtMillis: Long? = null

    /**
     * The platform reported [provider] enabled (a registration the platform accepted, or a provider
     * switched back on). A provider that is switched on again is remembered even if the original
     * request was refused, so the watch never mistakes "we never asked" for "it is off".
     */
    fun onProviderEnabled(provider: String) {
        providers.add(provider)
        disabled.remove(provider)
    }

    /** The platform reported [provider] disabled mid-session. */
    fun onProviderDisabled(provider: String) {
        providers.add(provider)
        disabled.add(provider)
    }

    /**
     * A fix arrived, stamped [fixTimestampMillis].
     *
     * Returns true when the fix is newer than the last one seen, i.e. when it is evidence that the
     * stream is alive again; a repeat of the last known fix at the same instant is not.
     */
    fun onFix(fixTimestampMillis: Long): Boolean {
        val previous = lastFixAtMillis
        lastFixAtMillis = if (previous == null) fixTimestampMillis else maxOf(previous, fixTimestampMillis)
        return previous == null || fixTimestampMillis > previous
    }

    /** True when the stream should be treated as lost at [nowMillis]. */
    fun isLost(nowMillis: Long): Boolean {
        if (everyProviderDisabled()) return true
        val last = lastFixAtMillis ?: return false
        return nowMillis - last >= staleAfterMillis
    }

    /** True when the platform has reported every provider we know of as disabled. */
    fun everyProviderDisabled(): Boolean =
        providers.isNotEmpty() && providers.all { it in disabled }

    /** Providers currently reported disabled, for logging. */
    val disabledProviders: Set<String> get() = disabled.toSet()

    /** Timestamp of the newest fix seen, or null before the first one. */
    val lastFixTimestampMillis: Long? get() = lastFixAtMillis

    /** Forgets everything, for a new session. */
    fun reset() {
        providers.clear()
        disabled.clear()
        lastFixAtMillis = null
    }

    companion object {
        /**
         * How long the stream may stay silent before it counts as lost. Long enough that a tunnel,
         * a car park or a red light under a tall building is not reported as a fault, short enough
         * that a driver notices before the next turn.
         */
        const val DEFAULT_STALE_AFTER_MILLIS = 20_000L
    }
}

package com.offlinemaps.core.routing

import com.offlinemaps.core.common.LocationFix

/**
 * Drops position updates that arrive out of order.
 *
 * A location stream is not guaranteed to be in time order: a fused provider can deliver a batched
 * fix behind one it already handed over, and a replay after a process or a session restart can hand
 * back a fix from before the one being followed. Every fix carries a timestamp, and the tracker has
 * always ignored it - so a late fix moved the matched segment, the off-route counter and the
 * arrival flag backwards, and could undo progress the driver had already made.
 *
 * The gate remembers the timestamp of the last accepted fix and refuses anything older. A fix with
 * the same timestamp is accepted: two updates inside one millisecond are not evidence of a stale
 * one, and refusing them would drop a real fix. The watermark only ever moves forward, so a
 * rejected fix cannot lower it and let a later one through that should not have been.
 *
 * Pure Kotlin with no Android dependency, so it is unit-tested on the JVM.
 */
class FixOrderGate {

    private var lastAcceptedMillis: Long? = null

    /** True when [fix] is not older than the last accepted fix, which then becomes the accepted one. */
    fun accepts(fix: LocationFix): Boolean {
        val last = lastAcceptedMillis
        if (last != null && fix.timestampMillis < last) return false
        lastAcceptedMillis = fix.timestampMillis
        return true
    }

    /** Timestamp of the last accepted fix, or null before the first one. */
    val lastAcceptedTimestampMillis: Long? get() = lastAcceptedMillis

    /** Forgets the watermark, so the next fix is accepted whatever its timestamp. */
    fun reset() {
        lastAcceptedMillis = null
    }
}

package com.offlinemaps.core.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cadence of the progress ticks: UI state at 250 ms, durable store write at most every 2 s. */
class ProgressPublishGateTest {

    @Test
    fun `the first tick publishes both`() {
        val gate = ProgressPublishGate()
        val decision = gate.decide(1_000L)
        assertTrue(decision.publishUi)
        assertTrue(decision.persist)
    }

    @Test
    fun `a tick inside both intervals publishes nothing`() {
        val gate = ProgressPublishGate()
        gate.decide(1_000L)
        assertEquals(ProgressPublish.NONE, gate.decide(1_100L))
    }

    @Test
    fun `the ui is republished before the store is written`() {
        val gate = ProgressPublishGate()
        gate.decide(0L)
        // 250 ms later: the progress bar moves, the store row does not.
        assertEquals(ProgressPublish(publishUi = true, persist = false), gate.decide(250L))
        assertEquals(ProgressPublish(publishUi = true, persist = false), gate.decide(1_000L))
        // 2 s after the first write: both again.
        assertEquals(ProgressPublish.BOTH, gate.decide(2_000L))
    }

    @Test
    fun `a thousand fast chunks cost a handful of store writes`() {
        val gate = ProgressPublishGate()
        var ui = 0
        var writes = 0
        // 1000 ticks, one every 10 ms - a fast transfer.
        for (i in 0 until 1_000) {
            val decision = gate.decide(i * 10L)
            if (decision.publishUi) ui++
            if (decision.persist) writes++
        }
        assertEquals(40, ui)
        assertEquals(5, writes)
        assertEquals(ui.toLong(), gate.uiPublishes)
        assertEquals(writes.toLong(), gate.storeWrites)
    }

    @Test
    fun `force publishes both whatever the interval says`() {
        val gate = ProgressPublishGate()
        gate.decide(5_000L)
        assertEquals(ProgressPublish.BOTH, gate.decide(5_001L, force = true))
    }

    @Test
    fun `force restarts both intervals`() {
        val gate = ProgressPublishGate()
        gate.decide(0L, force = true)
        assertEquals(ProgressPublish.NONE, gate.decide(100L))
        assertEquals(ProgressPublish(publishUi = true, persist = false), gate.decide(250L))
        assertEquals(ProgressPublish.BOTH, gate.decide(2_000L))
    }

    @Test
    fun `a clock that steps backwards does not starve progress`() {
        val gate = ProgressPublishGate()
        gate.decide(10_000L)
        val decision = gate.decide(9_000L)
        assertTrue(decision.publishUi)
        assertTrue(decision.persist)
        // ... and the gate recovers from the new, earlier baseline.
        assertEquals(ProgressPublish(publishUi = true, persist = false), gate.decide(9_250L))
    }

    @Test
    fun `custom intervals are honoured`() {
        val gate = ProgressPublishGate(uiIntervalMillis = 100L, storeIntervalMillis = 500L)
        gate.decide(0L)
        assertEquals(ProgressPublish(publishUi = false, persist = false), gate.decide(99L))
        assertEquals(ProgressPublish(publishUi = true, persist = false), gate.decide(100L))
        assertEquals(ProgressPublish(publishUi = true, persist = true), gate.decide(500L))
    }

    @Test
    fun `the store cadence is never faster than a couple of times a second`() {
        assertTrue(ProgressPublishGate.DEFAULT_STORE_INTERVAL_MILLIS >= 500L)
        assertEquals(250L, ProgressPublishGate.DEFAULT_UI_INTERVAL_MILLIS)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a zero ui interval`() {
        ProgressPublishGate(uiIntervalMillis = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a negative store interval`() {
        ProgressPublishGate(storeIntervalMillis = -1L)
    }

    @Test
    fun `a store write is never decided without a ui publish`() {
        val gate = ProgressPublishGate(uiIntervalMillis = 250L, storeIntervalMillis = 10_000L)
        var t = 0L
        repeat(200) {
            val decision = gate.decide(t)
            assertFalse(decision.persist && !decision.publishUi)
            t += 137L
        }
    }
}

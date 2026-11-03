package com.offlinemaps.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a camera animation is still in flight, and whether a callback belongs to it.
 *
 * The cases that matter are the ones MapLibre produces in practice: an animation that lands, one
 * the user cancels with a gesture, one replaced by a new move before it lands, and - the reason
 * this is token-based - the `onCancel` of the replaced animation arriving *after* its replacement
 * has started.
 */
class CameraAnimationTrackerTest {

    @Test
    fun `a fresh tracker has nothing in flight`() {
        val tracker = CameraAnimationTracker()
        assertFalse(tracker.isInFlight)
        assertEquals(0, tracker.started)
        assertEquals(0, tracker.finished)
        assertEquals(0, tracker.cancelled)
        assertEquals(0, tracker.interrupted)
    }

    @Test
    fun `start puts an animation in flight and finish takes it out`() {
        val tracker = CameraAnimationTracker()
        val token = tracker.start()
        assertTrue(tracker.isInFlight)
        tracker.finish(token)
        assertFalse(tracker.isInFlight)
        assertEquals(1, tracker.started)
        assertEquals(1, tracker.finished)
        assertEquals(0, tracker.cancelled)
    }

    @Test
    fun `a cancelled animation is counted separately from one that landed`() {
        val tracker = CameraAnimationTracker()
        val token = tracker.start()
        tracker.cancel(token)
        assertFalse(tracker.isInFlight)
        assertEquals(0, tracker.finished)
        assertEquals(1, tracker.cancelled)
    }

    @Test
    fun `a second start while one is in flight counts as interrupted and returns a new token`() {
        val tracker = CameraAnimationTracker()
        val first = tracker.start()
        val second = tracker.start()
        assertNotEquals(first, second)
        assertEquals(2, tracker.started)
        assertEquals(1, tracker.interrupted)
        assertTrue(tracker.isInFlight)
    }

    @Test
    fun `the late cancel of a replaced animation does not end the new one`() {
        val tracker = CameraAnimationTracker()
        val first = tracker.start()
        tracker.start() // replaces it, as a new move issued mid-flight does
        // MapLibre posts this to the main looper, so it lands after the replacement started.
        tracker.cancel(first)
        assertTrue(tracker.isInFlight)
        assertEquals(0, tracker.cancelled)
    }

    @Test
    fun `the late finish of a replaced animation does not end the new one either`() {
        val tracker = CameraAnimationTracker()
        val first = tracker.start()
        val second = tracker.start()
        tracker.finish(first)
        assertTrue(tracker.isInFlight)
        assertEquals(0, tracker.finished)
        tracker.finish(second)
        assertFalse(tracker.isInFlight)
        assertEquals(1, tracker.finished)
    }

    @Test
    fun `a repeated callback for the same animation is ignored`() {
        val tracker = CameraAnimationTracker()
        val token = tracker.start()
        tracker.finish(token)
        tracker.cancel(token)
        assertEquals(1, tracker.finished)
        assertEquals(0, tracker.cancelled)
        assertFalse(tracker.isInFlight)
    }

    @Test
    fun `reset clears the flight and makes its pending callback superseded`() {
        val tracker = CameraAnimationTracker()
        val token = tracker.start()
        tracker.reset()
        assertFalse(tracker.isInFlight)
        tracker.cancel(token)
        assertEquals(0, tracker.cancelled)
        assertEquals(0, tracker.finished)
    }

    @Test
    fun `a move started after reset is tracked normally`() {
        val tracker = CameraAnimationTracker()
        tracker.reset()
        val token = tracker.start()
        assertTrue(tracker.isInFlight)
        assertEquals(token, tracker.currentToken)
        tracker.finish(token)
        assertFalse(tracker.isInFlight)
        assertEquals(1, tracker.finished)
    }

    @Test
    fun `rapid starts never leave the tracker believing an old animation is in flight`() {
        val tracker = CameraAnimationTracker()
        var token = tracker.start()
        repeat(4) {
            val next = tracker.start() // a new move issued while the previous one is still flying
            tracker.cancel(token) // the replaced animation's callback, arriving after the start
            token = next
        }
        assertTrue(tracker.isInFlight)
        assertEquals(5, tracker.started)
        assertEquals(4, tracker.interrupted)
        assertEquals(0, tracker.cancelled)
        tracker.finish(token)
        assertFalse(tracker.isInFlight)
        assertEquals(1, tracker.finished)
    }
}

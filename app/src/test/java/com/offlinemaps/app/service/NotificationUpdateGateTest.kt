package com.offlinemaps.app.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The notification is fed from every accepted GPS fix. These tests pin down that a changed
 * instruction always goes out at once, that a change of remaining distance alone is rate-limited
 * rather than posted once a second, and that a rate-limited change is delayed rather than lost.
 */
class NotificationUpdateGateTest {

    private var now = 1_000L

    private fun gate(intervalMs: Long = 5_000L) = NotificationUpdateGate(intervalMs) { now }

    @Test
    fun firstUpdateIsAlwaysPosted() {
        val gate = gate()

        assertThat(gate.shouldPost("Turn left", "350 m")).isTrue()
        assertThat(gate.posted).isEqualTo(1)
        assertThat(gate.skipped).isEqualTo(0)
    }

    @Test
    fun identicalUpdateIsNotReposted() {
        val gate = gate()
        gate.shouldPost("Turn left", "350 m")

        now += 1_000L
        assertThat(gate.shouldPost("Turn left", "350 m")).isFalse()
        assertThat(gate.shouldPost("Turn left", "350 m")).isFalse()

        assertThat(gate.posted).isEqualTo(1)
        assertThat(gate.skipped).isEqualTo(2)
    }

    @Test
    fun changedInstructionIsPostedImmediately() {
        val gate = gate()
        gate.shouldPost("Turn left", "350 m")

        now += 1_000L
        assertThat(gate.shouldPost("Turn right", "300 m")).isTrue()
        now += 1_000L
        assertThat(gate.shouldPost("Arrive at destination", "20 m")).isTrue()

        assertThat(gate.posted).isEqualTo(3)
        assertThat(gate.skipped).isEqualTo(0)
    }

    @Test
    fun distanceOnlyChangeInsideTheIntervalIsSkipped() {
        val gate = gate(intervalMs = 5_000L)
        gate.shouldPost("Turn left", "350 m")

        // One fix a second for the next four seconds: four new distances, none due yet.
        now += 1_000L
        assertThat(gate.shouldPost("Turn left", "340 m")).isFalse()
        now += 1_000L
        assertThat(gate.shouldPost("Turn left", "330 m")).isFalse()
        now += 1_000L
        assertThat(gate.shouldPost("Turn left", "320 m")).isFalse()
        now += 1_000L
        assertThat(gate.shouldPost("Turn left", "310 m")).isFalse()

        assertThat(gate.posted).isEqualTo(1)
        assertThat(gate.skipped).isEqualTo(4)

        // Five seconds after the last post the newest distance goes out.
        now += 1_000L
        assertThat(gate.shouldPost("Turn left", "300 m")).isTrue()
        assertThat(gate.posted).isEqualTo(2)
    }

    @Test
    fun aSkippedDistanceChangeIsDelayedNotLost() {
        val gate = gate(intervalMs = 5_000L)
        gate.shouldPost("Turn left", "350 m")

        now += 1_000L
        assertThat(gate.shouldPost("Turn left", "340 m")).isFalse()

        // The state has not moved on since: the pending distance is still posted once it is due.
        now += 4_000L
        assertThat(gate.shouldPost("Turn left", "340 m")).isTrue()
    }

    @Test
    fun theIntervalIsMeasuredFromTheLastPostNotTheLastUpdate() {
        val gate = gate(intervalMs = 5_000L)
        gate.shouldPost("Turn left", "350 m")

        now += 1_000L
        gate.shouldPost("Turn left", "340 m")
        now += 1_000L
        gate.shouldPost("Turn left", "330 m")
        now += 1_000L
        gate.shouldPost("Turn left", "320 m")
        now += 1_000L
        gate.shouldPost("Turn left", "310 m")
        now += 1_000L
        gate.shouldPost("Turn left", "300 m")

        // 5 s after the first post: posted, and the clock restarts from here.
        assertThat(gate.posted).isEqualTo(2)
        now += 1_000L
        assertThat(gate.shouldPost("Turn left", "290 m")).isFalse()
    }

    @Test
    fun recordedPlaceholderCountsAsPosted() {
        val gate = gate()
        gate.record("Navigating", "")

        now += 1_000L
        assertThat(gate.shouldPost("Navigating", "")).isFalse()
        assertThat(gate.posted).isEqualTo(1)

        // The first real instruction replaces the placeholder at once.
        now += 1_000L
        assertThat(gate.shouldPost("Turn left", "350 m")).isTrue()
        assertThat(gate.posted).isEqualTo(2)
    }
}

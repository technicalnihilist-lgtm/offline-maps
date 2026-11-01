package com.offlinemaps.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The cold-start timing used to measure the deferred map start-up (OPTIMIZATION.md A-03).
 *
 * The arithmetic is the whole point of the trace, so it is pinned here with a fake clock: phases
 * are reported relative to `Application.onCreate`, MapLibre's own cost is measured on its own, and
 * the line is emitted exactly once per cold start.
 */
class StartupTraceTest {

    private var now = 1_000L
    private val lines = mutableListOf<String>()
    private val trace = StartupTrace(clock = { now }, logger = { lines += it })

    @Test
    fun `nothing is reported before begin`() {
        trace.mapLibreInitStarted()
        trace.mapLibreInitFinished()
        trace.firstStyleApplied()

        assertThat(trace.summary()).isNull()
        assertThat(lines).isEmpty()
    }

    @Test
    fun `maplibre init cost is measured on its own`() {
        trace.begin()
        now = 1_000L + 120L
        trace.mapLibreInitStarted()
        now = 1_000L + 165L
        trace.mapLibreInitFinished()
        now = 1_000L + 480L
        trace.firstStyleApplied()

        assertThat(trace.summary())
            .isEqualTo("cold start: MapLibre init 45 ms, first style applied 480 ms after Application.onCreate")
    }

    @Test
    fun `the breakdown is logged once per cold start`() {
        trace.begin()
        now = 1_000L + 300L
        trace.firstStyleApplied()
        now = 1_000L + 900L
        trace.firstStyleApplied()

        assertThat(lines).hasSize(1)
        assertThat(lines.first()).contains("first style applied 300 ms")
    }

    @Test
    fun `an uninitialised maplibre is reported as such`() {
        trace.begin()
        now = 1_000L + 250L
        trace.firstStyleApplied()

        assertThat(trace.summary()).contains("MapLibre init not initialised")
        assertThat(trace.summary()).contains("first style applied 250 ms")
    }

    @Test
    fun `begin restarts the measurement`() {
        trace.begin()
        now = 1_000L + 700L
        trace.firstStyleApplied()

        now = 5_000L
        trace.begin()
        now = 5_000L + 200L
        trace.firstStyleApplied()

        assertThat(lines).hasSize(2)
        assertThat(lines.last()).contains("first style applied 200 ms")
    }
}

package com.offlinemaps.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The Trace/Timber-style phase wrapper around cold start (OPTIMIZATION.md A-07).
 *
 * A-07 asked for the timing to be a reusable wrapper rather than two hand-wired measurements, so
 * these tests pin the wrapper itself: each phase is timed on its own, a throwing block still closes
 * its phase, phases appear in the breakdown in the order they finished, and the breakdown line is
 * emitted exactly once per cold start.
 */
class StartupTracePhasesTest {

    private var now = 1_000L
    private val lines = mutableListOf<String>()
    private val trace = StartupTrace(clock = { now }, logger = { lines += it })

    @Test
    fun `a traced block is timed on its own`() {
        trace.begin()

        trace.trace("first") {
            now += 40L
        }
        now += 100L
        trace.trace("second") {
            now += 15L
        }

        assertThat(trace.phaseMillis("first")).isEqualTo(40L)
        assertThat(trace.phaseMillis("second")).isEqualTo(15L)
    }

    @Test
    fun `a traced block that throws is still closed and the exception propagates`() {
        trace.begin()

        val thrown = runCatching {
            trace.trace("failing") {
                now += 25L
                error("boom")
            }
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(trace.phaseMillis("failing")).isEqualTo(25L)
    }

    @Test
    fun `the traced block returns its own value`() {
        trace.begin()

        val result = trace.trace("value") { 7 }

        assertThat(result).isEqualTo(7)
    }

    @Test
    fun `phases are ignored before begin`() {
        trace.beginPhase("early")
        now += 50L
        trace.endPhase("early")
        trace.firstFrameReached()
        trace.finishStartup()

        assertThat(trace.phaseMillis("early")).isNull()
        assertThat(trace.breakdown()).isNull()
        assertThat(lines).isEmpty()
    }

    @Test
    fun `an end without a begin is a no-op`() {
        trace.begin()
        trace.endPhase("never opened")

        assertThat(trace.phaseMillis("never opened")).isNull()
    }

    @Test
    fun `the breakdown lists phases in the order they finished, with the total`() {
        trace.begin()
        trace.trace("first") { now += 10L }
        trace.trace("second") { now += 20L }
        now = 1_000L + 500L
        trace.firstStyleApplied()

        val line = trace.breakdown()!!

        assertThat(line).contains("first 10 ms")
        assertThat(line).contains("second 20 ms")
        assertThat(line.indexOf("first 10 ms")).isLessThan(line.indexOf("second 20 ms"))
        assertThat(line).contains("first style at 500 ms")
        assertThat(line).endsWith("total 500 ms")
    }

    @Test
    fun `the first frame mark is recorded once`() {
        trace.begin()
        now = 1_000L + 180L
        trace.firstFrameReached()
        now = 1_000L + 400L
        trace.firstFrameReached()

        assertThat(trace.breakdown()).contains("first frame at 180 ms")
    }

    @Test
    fun `the maplibre phase shows up in the breakdown with its own cost`() {
        trace.begin()
        now = 1_000L + 120L
        trace.mapLibreInitStarted()
        now = 1_000L + 170L
        trace.mapLibreInitFinished()

        assertThat(trace.breakdown()).contains("MapLibre init 50 ms")
        assertThat(trace.summary()).contains("MapLibre init 50 ms")
    }

    @Test
    fun `finishStartup logs the breakdown once per cold start`() {
        trace.begin()
        trace.trace("first") { now += 30L }

        trace.finishStartup()
        trace.finishStartup()

        assertThat(lines).hasSize(1)
        assertThat(lines.single()).contains("cold start breakdown:")
        assertThat(lines.single()).contains("first 30 ms")
    }

    @Test
    fun `begin restarts the measurement and re-arms the breakdown`() {
        trace.begin()
        trace.trace("old") { now += 30L }
        trace.finishStartup()

        now = 9_000L
        trace.begin()
        trace.trace("new") { now += 12L }
        trace.finishStartup()

        assertThat(lines).hasSize(2)
        assertThat(lines.last()).contains("new 12 ms")
        assertThat(lines.last()).doesNotContain("old 30 ms")
        assertThat(trace.phaseMillis("old")).isNull()
    }
}

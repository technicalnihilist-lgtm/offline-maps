package com.offlinemaps.core.location

import com.google.common.truth.Truth.assertThat
import com.offlinemaps.core.location.ExploreStreamLifetime.Action
import org.junit.Test

/**
 * The explore stream used to stay registered after the screen showing it had gone: a single tap on
 * "my location" kept both providers running with the phone in a pocket, which is what the item is
 * about even though the app holds no wake lock of its own (OPTIMIZATION.md G-04). These tests pin
 * the rules - nothing is held before the user asks, the registration ends with the screen, it
 * returns with it, and no repeated event can register or release it twice.
 */
class ExploreStreamLifetimeTest {

    @Test
    fun `nothing is held before the user asks`() {
        val lifetime = ExploreStreamLifetime()

        assertThat(lifetime.onVisibilityChanged(visible = false)).isEqualTo(Action.NONE)
        assertThat(lifetime.onVisibilityChanged(visible = true)).isEqualTo(Action.NONE)
        assertThat(lifetime.isRunning()).isFalse()
    }

    @Test
    fun `asking for the stream holds it`() {
        val lifetime = ExploreStreamLifetime()
        lifetime.onRequested()

        assertThat(lifetime.isRunning()).isTrue()
        assertThat(lifetime.onVisibilityChanged(visible = true)).isEqualTo(Action.NONE)
    }

    @Test
    fun `a held stream is released when the screen stops`() {
        val lifetime = ExploreStreamLifetime()
        lifetime.onRequested()

        assertThat(lifetime.onVisibilityChanged(visible = false)).isEqualTo(Action.STOP)
        assertThat(lifetime.isRunning()).isFalse()
    }

    @Test
    fun `a stream released with the screen comes back with it`() {
        val lifetime = ExploreStreamLifetime()
        lifetime.onRequested()
        lifetime.onVisibilityChanged(visible = false)

        assertThat(lifetime.onVisibilityChanged(visible = true)).isEqualTo(Action.START)
        assertThat(lifetime.isRunning()).isTrue()
    }

    @Test
    fun `a repeated visibility event does nothing`() {
        val lifetime = ExploreStreamLifetime()
        lifetime.onRequested()
        lifetime.onVisibilityChanged(visible = false)

        assertThat(lifetime.onVisibilityChanged(visible = false)).isEqualTo(Action.NONE)
        assertThat(lifetime.onVisibilityChanged(visible = true)).isEqualTo(Action.START)
        assertThat(lifetime.onVisibilityChanged(visible = true)).isEqualTo(Action.NONE)
    }

    @Test
    fun `asking twice does not release or restart anything`() {
        val lifetime = ExploreStreamLifetime()
        lifetime.onRequested()
        lifetime.onRequested()

        assertThat(lifetime.onVisibilityChanged(visible = false)).isEqualTo(Action.STOP)
        assertThat(lifetime.onVisibilityChanged(visible = true)).isEqualTo(Action.START)
    }

    @Test
    fun `a released stream is not brought back by the screen`() {
        val lifetime = ExploreStreamLifetime()
        lifetime.onRequested()
        lifetime.onVisibilityChanged(visible = false)
        lifetime.onReleased()

        assertThat(lifetime.onVisibilityChanged(visible = true)).isEqualTo(Action.NONE)
        assertThat(lifetime.isRunning()).isFalse()
    }

    @Test
    fun `asking again after a release holds the stream again`() {
        val lifetime = ExploreStreamLifetime()
        lifetime.onRequested()
        lifetime.onReleased()

        lifetime.onRequested()

        assertThat(lifetime.isRunning()).isTrue()
        assertThat(lifetime.onVisibilityChanged(visible = false)).isEqualTo(Action.STOP)
    }

    @Test
    fun `the stream survives repeated screen cycles`() {
        val lifetime = ExploreStreamLifetime()
        lifetime.onRequested()

        repeat(3) {
            assertThat(lifetime.onVisibilityChanged(visible = false)).isEqualTo(Action.STOP)
            assertThat(lifetime.onVisibilityChanged(visible = true)).isEqualTo(Action.START)
        }
        assertThat(lifetime.isRunning()).isTrue()
    }
}

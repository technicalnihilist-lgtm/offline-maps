package com.offlinemaps.core.location

import android.location.LocationManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A navigation session used to have no way of knowing its position stream had stopped: switching
 * the location toggle off mid-drive left the panel showing a position that never moved, with
 * nothing to say why, and a receiver that simply went quiet looked exactly the same
 * (OPTIMIZATION.md G-03). These tests pin what counts as lost - every provider disabled, or a fix
 * that is older than the stale window - and that a re-emitted last known fix does not clear it.
 */
class SignalLossWatchTest {

    private val gps = LocationManager.GPS_PROVIDER
    private val network = LocationManager.NETWORK_PROVIDER

    private fun watch(staleAfterMillis: Long = 20_000L) = SignalLossWatch(staleAfterMillis)

    @Test
    fun `a stream with a fresh fix is not lost`() {
        val watch = watch()
        watch.onProviderEnabled(gps)
        watch.onFix(1_000L)

        assertThat(watch.isLost(nowMillis = 1_000L)).isFalse()
        assertThat(watch.isLost(nowMillis = 20_999L)).isFalse()
    }

    @Test
    fun `nothing is lost before the first fix`() {
        val watch = watch()
        watch.onProviderEnabled(gps)

        assertThat(watch.isLost(nowMillis = 10_000_000L)).isFalse()
    }

    @Test
    fun `silence for the stale window is lost`() {
        val watch = watch(staleAfterMillis = 20_000L)
        watch.onProviderEnabled(gps)
        watch.onFix(1_000L)

        assertThat(watch.isLost(nowMillis = 20_999L)).isFalse()
        assertThat(watch.isLost(nowMillis = 21_000L)).isTrue()
    }

    @Test
    fun `one provider disabled while the other is live is not lost`() {
        val watch = watch()
        watch.onProviderEnabled(gps)
        watch.onProviderEnabled(network)
        watch.onFix(1_000L)

        watch.onProviderDisabled(gps)

        assertThat(watch.everyProviderDisabled()).isFalse()
        assertThat(watch.isLost(nowMillis = 2_000L)).isFalse()
        assertThat(watch.disabledProviders).containsExactly(gps)
    }

    @Test
    fun `every provider disabled is lost at once`() {
        val watch = watch()
        watch.onProviderEnabled(gps)
        watch.onProviderEnabled(network)
        watch.onFix(1_000L)

        watch.onProviderDisabled(gps)
        watch.onProviderDisabled(network)

        // No waiting for the stale window: the platform already told us.
        assertThat(watch.everyProviderDisabled()).isTrue()
        assertThat(watch.isLost(nowMillis = 1_000L)).isTrue()
    }

    @Test
    fun `a provider switched back on without a fix is still lost`() {
        val watch = watch(staleAfterMillis = 20_000L)
        watch.onProviderEnabled(gps)
        watch.onFix(1_000L)
        watch.onProviderDisabled(gps)

        watch.onProviderEnabled(gps)

        assertThat(watch.everyProviderDisabled()).isFalse()
        // The stream has not delivered anything new, so it is still the clock that decides.
        assertThat(watch.isLost(nowMillis = 21_000L)).isTrue()
        assertThat(watch.isLost(nowMillis = 5_000L)).isFalse()
    }

    @Test
    fun `a re-emitted last known fix does not count as a fresh one`() {
        val watch = watch()
        watch.onProviderEnabled(gps)
        assertThat(watch.onFix(1_000L)).isTrue()

        // What onProviderDisabled re-emits: the same fix, same instant.
        assertThat(watch.onFix(1_000L)).isFalse()
        assertThat(watch.lastFixTimestampMillis).isEqualTo(1_000L)

        // A fix from before the one already followed does not move the watermark either.
        assertThat(watch.onFix(900L)).isFalse()
        assertThat(watch.lastFixTimestampMillis).isEqualTo(1_000L)
    }

    @Test
    fun `reset forgets the stream`() {
        val watch = watch()
        watch.onProviderEnabled(gps)
        watch.onFix(1_000L)
        watch.onProviderDisabled(gps)
        assertThat(watch.isLost(nowMillis = 2_000L)).isTrue()

        watch.reset()

        assertThat(watch.isLost(nowMillis = 10_000_000L)).isFalse()
        assertThat(watch.lastFixTimestampMillis).isNull()
        assertThat(watch.disabledProviders).isEmpty()
    }
}

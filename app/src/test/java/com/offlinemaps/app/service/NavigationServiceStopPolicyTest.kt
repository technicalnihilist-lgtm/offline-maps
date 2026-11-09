package com.offlinemaps.app.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The service is only ever kept alive by an active navigation session. These tests pin the two
 * moments it must stop itself - the session going away, and the task being removed - and that a
 * live session in a live task keeps it running, which is what screen-off guidance depends on.
 */
class NavigationServiceStopPolicyTest {

    private val policy = NavigationServiceStopPolicy()

    @Test
    fun endsWithTheSession() {
        assertThat(policy.shouldStop(hasSession = false, taskRemoved = false)).isTrue()
    }

    @Test
    fun keepsRunningWhileASessionIsLive() {
        assertThat(policy.shouldStop(hasSession = true, taskRemoved = false)).isFalse()
    }

    @Test
    fun endsWithTheTaskEvenWhileASessionIsLive() {
        assertThat(policy.shouldStop(hasSession = true, taskRemoved = true)).isTrue()
    }

    @Test
    fun endsWhenBothAreGone() {
        assertThat(policy.shouldStop(hasSession = false, taskRemoved = true)).isTrue()
    }
}

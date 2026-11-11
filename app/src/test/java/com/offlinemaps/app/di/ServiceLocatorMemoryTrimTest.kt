package com.offlinemaps.app.di

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.offlinemaps.core.common.MapTheme
import com.offlinemaps.core.map.MapStyleFactory
import com.offlinemaps.core.map.StyleOptions
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A memory trim reaches the caches the app actually holds (OPTIMIZATION.md H-02).
 *
 * [ServiceLocator.onTrimMemory] is the single funnel from the system callback to the caches, so the
 * wiring is checked here rather than only at the policy: a pressure level must drop the generated
 * style documents, and a level that only says "the app is in the background" must leave them alone,
 * because rebuilding one is main-thread work a return to the app would pay straight away.
 *
 * The cache internals are `internal` to `core:map`, so this test reads the only thing the app can
 * see - whether the same options still return the identical document instance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ServiceLocatorMemoryTrimTest {

    private val options = StyleOptions(theme = MapTheme.LIGHT)

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a pressure trim drops the cached style document`() {
        val locator = ServiceLocator(context())
        val cached = MapStyleFactory.buildJson(options)
        assertThat(MapStyleFactory.buildJson(options)).isSameInstanceAs(cached)

        locator.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        // A rebuild: equal content, different instance, so nothing stale is served after the trim.
        assertThat(MapStyleFactory.buildJson(options)).isNotSameInstanceAs(cached)
    }

    @Test
    fun `a background trim keeps the cached style document`() {
        val locator = ServiceLocator(context())
        val cached = MapStyleFactory.buildJson(options)

        locator.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)

        assertThat(MapStyleFactory.buildJson(options)).isSameInstanceAs(cached)
    }
}

package com.offlinemaps.app.map

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MapLibre is initialised when the map view is attached, not at process start (OPTIMIZATION.md
 * A-03).
 *
 * The native initialisation is injected, so these tests assert the contract the map view relies on:
 * nothing runs until the first `ensureInitialised`, it runs exactly once per process, and it
 * receives an application context (a retained Activity context would leak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MapLibreBootstrapTest {

    private lateinit var context: Context
    private val initialisedWith = mutableListOf<Context>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MapLibreBootstrap.resetForTest()
    }

    @After
    fun tearDown() {
        MapLibreBootstrap.resetForTest()
    }

    @Test
    fun `nothing is initialised before the map view asks for it`() {
        assertThat(MapLibreBootstrap.isInitialised).isFalse()
        assertThat(initialisedWith).isEmpty()
    }

    @Test
    fun `the first call initialises exactly once`() {
        MapLibreBootstrap.ensureInitialised(context) { initialisedWith += it }
        MapLibreBootstrap.ensureInitialised(context) { initialisedWith += it }
        MapLibreBootstrap.ensureInitialised(context) { initialisedWith += it }

        assertThat(MapLibreBootstrap.isInitialised).isTrue()
        assertThat(initialisedWith).hasSize(1)
    }

    @Test
    fun `an activity context is reduced to the application context`() {
        MapLibreBootstrap.ensureInitialised(context) { initialisedWith += it }

        assertThat(initialisedWith.single()).isSameInstanceAs(context.applicationContext)
    }

    @Test
    fun `reset allows a fresh process to initialise again`() {
        MapLibreBootstrap.ensureInitialised(context) { initialisedWith += it }
        MapLibreBootstrap.resetForTest()
        MapLibreBootstrap.ensureInitialised(context) { initialisedWith += it }

        assertThat(initialisedWith).hasSize(2)
    }
}

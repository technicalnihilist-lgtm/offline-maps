package com.offlinemaps.app.di

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cold start must not pay for services the first screen never asks for (OPTIMIZATION.md A-01).
 *
 * The locator is the single place where the app builds its services, so the guarantee is checked
 * here: creating it may not initialise a single lazy member, create a file or open a database.
 * A plain [Application] is used so Robolectric does not run the real one, which would initialise
 * MapLibre and defeat the point of the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ServiceLocatorLazinessTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `constructing the locator initialises no lazy service`() {
        val locator = ServiceLocator(context)

        val initialised = lazyMembers(locator).filter { it.isInitialized() }

        assertThat(initialised).isEmpty()
    }

    @Test
    fun `constructing the locator touches no disk`() {
        ServiceLocator(context)

        // Room only creates this file when a query first runs, and the packages directory is
        // created by the `packagesDir` lazy — neither may happen while the app is starting.
        assertThat(context.getDatabasePath("offline_maps.db").exists()).isFalse()
        val packages = File(context.getExternalFilesDir(null) ?: context.filesDir, "packages")
        assertThat(packages.exists()).isFalse()
    }

    /** Every `by lazy` member of [target], read through its backing delegate. */
    private fun lazyMembers(target: Any): List<Lazy<*>> =
        target.javaClass.declaredFields
            .filter { Lazy::class.java.isAssignableFrom(it.type) }
            .map { field ->
                field.isAccessible = true
                field.get(target) as Lazy<*>
            }
}

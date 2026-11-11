package com.offlinemaps.app.di

import android.app.Activity
import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Field
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Nothing the locator builds may keep the screen that asked for it (OPTIMIZATION.md H-01).
 *
 * Every service here lives as long as the process, so a context captured from an Activity is a
 * leak that survives configuration changes, tab switches and a finished screen. The locator is the
 * single place these services are constructed, so it coerces the context to the application context
 * and this test holds it to that: build a locator from a real Activity, force the context-holding
 * singletons to initialise, then look at what they actually retained.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ServiceLocatorContextRetentionTest {

    @Test
    fun `a locator handed an activity keeps the application context`() {
        val activity = activity()
        val locator = ServiceLocator(activity)

        assertThat(locator.appContext).isSameInstanceAs(activity.applicationContext)
        assertThat(locator.appContext).isNotSameInstanceAs(activity)
    }

    @Test
    fun `initialised singletons retain the application context, never the activity`() {
        val activity = activity()
        val locator = ServiceLocator(activity)

        // The services that store a context, and so the ones a screen could be pinned through.
        locator.settings
        locator.searchIndex
        locator.locationSource
        locator.offlineRegions
        locator.packagesDir
        locator.savedPlaces
        locator.recentSearches
        locator.downloadRecords
        locator.installedRegions

        val retained = retainedContexts(locator)

        assertThat(retained).isNotEmpty()
        assertThat(retained.filter { it === activity }).isEmpty()
        retained.forEach { assertThat(it).isSameInstanceAs(activity.applicationContext) }
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    /** Every [Context] held by an already-initialised lazy member of [locator]. */
    private fun retainedContexts(locator: ServiceLocator): List<Context> {
        val retained = mutableListOf<Context>()
        lazyFields(locator)
            .map { it.get(locator) as Lazy<*> }
            .filter { it.isInitialized() }
            .forEach { lazyMember ->
                val instance = lazyMember.value ?: return@forEach
                contextFields(instance).mapNotNullTo(retained) { it.get(instance) as? Context }
            }
        return retained
    }

    private fun lazyFields(target: Any): List<Field> =
        fieldsOf(target).filter { Lazy::class.java.isAssignableFrom(it.type) }

    /** The fields of [target]'s class and its superclasses, so a context kept by a base class counts. */
    private fun fieldsOf(target: Any): List<Field> {
        val fields = mutableListOf<Field>()
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields
                .filterNot { it.isSynthetic }
                .forEach { field ->
                    field.isAccessible = true
                    fields += field
                }
            type = type.superclass
        }
        return fields
    }

    private fun contextFields(target: Any): List<Field> =
        fieldsOf(target).filter { Context::class.java.isAssignableFrom(it.type) }
}

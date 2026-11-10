package com.offlinemaps.core.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.offlinemaps.core.common.LocationFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `updates()` registers location updates on the platform and has to take them back when the
 * collector leaves - the map screen and a navigation session can stream at the same time, so "the
 * last collector leaves" is the case that matters (OPTIMIZATION.md G-01). These tests pin that
 * contract against a Robolectric `LocationManager`: what is registered while a collector is live,
 * what is left behind once it is gone, and that a provider which cannot be requested does not
 * swallow the other one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LocationSourceTest {

    private lateinit var app: Application
    private lateinit var manager: LocationManager
    private lateinit var source: LocationSource
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setLocationEnabled(true)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        source = LocationSource(app)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Suppress("DEPRECATION")
    private fun registered(): List<LocationListener> = shadowOf(manager).getLocationUpdateListeners()

    @Suppress("DEPRECATION")
    private fun registered(provider: String): List<LocationListener> =
        shadowOf(manager).getLocationUpdateListeners(provider)

    /** Starts a collector; the scope is unconfined, so registration has happened on return. */
    private fun collect(updates: Flow<LocationFix> = source.updates()): Job =
        scope.launch { updates.collect { } }

    @Test
    fun aLiveCollectorIsRegisteredOnBothEnabledProviders() {
        val job = collect()

        assertThat(registered()).hasSize(1)
        assertThat(registered(LocationManager.GPS_PROVIDER)).hasSize(1)
        assertThat(registered(LocationManager.NETWORK_PROVIDER)).hasSize(1)

        job.cancel()
        assertThat(registered()).isEmpty()
    }

    @Test
    fun theLastCollectorLeavingRemovesEveryRegistration() {
        val map = collect(source.updates(minTimeMs = 4_000L, minDistanceMeters = 8f))
        val navigation = collect(source.updates(minTimeMs = 1_000L, minDistanceMeters = 2f))
        assertThat(registered()).hasSize(2)

        // The map screen goes away mid-drive: only its own listener may be removed.
        map.cancel()
        assertThat(registered()).hasSize(1)
        assertThat(registered(LocationManager.GPS_PROVIDER)).hasSize(1)

        navigation.cancel()
        assertThat(registered()).isEmpty()
        assertThat(registered(LocationManager.NETWORK_PROVIDER)).isEmpty()
    }

    @Test
    fun leavingAndComingBackDoesNotAccumulateRegistrations() {
        collect().cancel()
        val second = collect()

        assertThat(registered()).hasSize(1)

        second.cancel()
        assertThat(registered()).isEmpty()
    }

    @Test
    fun aDisabledProviderIsNotRequestedAndDoesNotStopTheOther() {
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)

        val job = collect()

        assertThat(registered(LocationManager.NETWORK_PROVIDER)).isEmpty()
        assertThat(registered(LocationManager.GPS_PROVIDER)).hasSize(1)

        job.cancel()
        assertThat(registered()).isEmpty()
    }

    @Test
    fun withoutPermissionNothingIsRegistered() {
        shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        val outcome = runBlocking { runCatching { source.updates().collect { } } }

        assertThat(outcome.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(registered()).isEmpty()
    }

    @Test
    fun `the provider state callback reports what was registered and what went away`() {
        val events = mutableListOf<Pair<String, Boolean>>()
        val job = scope.launch {
            source.updates(onProviderState = { provider, enabled -> events.add(provider to enabled) })
                .collect { }
        }

        assertThat(events).containsExactly(
            LocationManager.GPS_PROVIDER to true,
            LocationManager.NETWORK_PROVIDER to true,
        )

        // The platform switches the provider off mid-drive and calls the listener it registered.
        registered(LocationManager.GPS_PROVIDER).single()
            .onProviderDisabled(LocationManager.GPS_PROVIDER)
        assertThat(events.last()).isEqualTo(LocationManager.GPS_PROVIDER to false)

        registered(LocationManager.GPS_PROVIDER).single()
            .onProviderEnabled(LocationManager.GPS_PROVIDER)
        assertThat(events.last()).isEqualTo(LocationManager.GPS_PROVIDER to true)

        job.cancel()
        assertThat(registered()).isEmpty()
    }

    @Test
    fun `a provider that was disabled at registration is never reported as live`() {
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        val events = mutableListOf<Pair<String, Boolean>>()

        val job = scope.launch {
            source.updates(onProviderState = { provider, enabled -> events.add(provider to enabled) })
                .collect { }
        }

        assertThat(events).containsExactly(LocationManager.GPS_PROVIDER to true)
        job.cancel()
    }
}

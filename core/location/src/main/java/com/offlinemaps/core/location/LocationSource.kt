package com.offlinemaps.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.LocationFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * GPS position source built on the platform [LocationManager].
 *
 * Deliberately not tied to Google Play Services: the app has to work on de-Googled and budget
 * devices, and offline navigation must not depend on a network-backed location provider. Updates
 * are throttled by time *and* distance so a stationary phone does not burn battery.
 */
class LocationSource(private val context: Context) {

    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isGpsEnabled(): Boolean = runCatching {
        manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    }.getOrDefault(false)

    fun isAnyProviderEnabled(): Boolean = runCatching {
        manager?.let { m ->
            m.isProviderEnabled(LocationManager.GPS_PROVIDER) || m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } == true
    }.getOrDefault(false)

    /** Last known fix, useful to centre the map instantly without waiting for a cold GPS lock. */
    fun lastKnown(): LocationFix? {
        if (!hasPermission()) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return runCatching {
            providers.mapNotNull { p -> manager?.getLastKnownLocation(p) }
                .maxByOrNull { it.time }
                ?.toFix()
        }.getOrNull()
    }

    /**
     * Continuous position updates. [minTimeMs] and [minDistanceMeters] throttle delivery - while
     * navigating we use ~1 s / 2 m, while simply exploring the map we use ~4 s / 8 m.
     *
     * Every collector owns its own registration and takes it back when it leaves, so the map screen
     * and a navigation session can stream at their own cadence at the same time and neither one
     * inherits the other's request (OPTIMIZATION.md G-01).
     */
    fun updates(
        minTimeMs: Long = 2_000L,
        minDistanceMeters: Float = 5f,
    ): Flow<LocationFix> = callbackFlow {
        val lm = manager
        if (lm == null || !hasPermission()) {
            close(IllegalStateException("Location permission not granted"))
            return@callbackFlow
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toFix())
            }

            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                // Re-emit the last known position so the UI can keep showing it while GPS recovers.
                val last = lastKnown() ?: return
                trySend(last)
            }
        }

        val registration = Registration(lm, listener)
        try {
            registration.request(LocationManager.GPS_PROVIDER, minTimeMs, minDistanceMeters)
            registration.request(
                LocationManager.NETWORK_PROVIDER, minTimeMs * 3, minDistanceMeters * 4,
            )
            awaitClose { registration.release() }
        } finally {
            // Also covers an exit that never reached awaitClose, so a collector that leaves cannot
            // leave the radio running behind it.
            registration.release()
        }
    }.conflate()
}

/**
 * The updates one collector asked [LocationManager] for, and the single place they are taken back.
 *
 * What has to be reliable is the removal: a listener left behind keeps the provider and its
 * main-looper callback alive long after the screen that wanted the fix is gone. Two things were
 * making that fragile. The two provider requests shared one `runCatching`, so a provider that
 * refused the request - a coarse-only permission asking for the GPS provider throws on older
 * releases, and an OEM build can throw for a provider it does not have - aborted the block and took
 * the *other* provider's request with it, leaving the collector either silent or registered with no
 * record of what it had asked for. And removal hung on `awaitClose` alone, with nothing covering an
 * exit that never got there. So each provider is now requested on its own and counted, and
 * [release] is idempotent: the close handler and the `finally` can both call it without removing
 * anything twice or leaving a registration behind.
 */
private class Registration(
    private val manager: LocationManager,
    private val listener: LocationListener,
) {
    private var requested = 0
    private var released = false

    /** Registers [provider], unless it is disabled or the platform refuses the request. */
    fun request(provider: String, minTimeMs: Long, minDistanceMeters: Float) {
        if (released) return
        val enabled = runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        if (!enabled) return
        val registered = runCatching {
            manager.requestLocationUpdates(
                provider, minTimeMs, minDistanceMeters, listener, Looper.getMainLooper(),
            )
        }.isSuccess
        if (registered) requested++
    }

    /** Removes this collector's updates; later calls do nothing. */
    @Synchronized
    fun release() {
        if (released) return
        released = true
        if (requested == 0) return
        requested = 0
        runCatching { manager.removeUpdates(listener) }
    }
}

private fun Location.toFix(): LocationFix = LocationFix(
    point = GeoPoint(latitude, longitude, if (hasAltitude()) altitude else null),
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    bearingDegrees = if (hasBearing()) bearing else null,
    speedMetersPerSecond = if (hasSpeed()) speed else null,
    timestampMillis = time,
)

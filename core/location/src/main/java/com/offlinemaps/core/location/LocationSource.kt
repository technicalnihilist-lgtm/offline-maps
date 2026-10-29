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

        runCatching {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, minTimeMs, minDistanceMeters, listener, Looper.getMainLooper(),
                )
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, minTimeMs * 3, minDistanceMeters * 4, listener, Looper.getMainLooper(),
                )
            }
        }

        awaitClose { runCatching { lm.removeUpdates(listener) } }
    }.conflate()
}

private fun Location.toFix(): LocationFix = LocationFix(
    point = GeoPoint(latitude, longitude, if (hasAltitude()) altitude else null),
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    bearingDegrees = if (hasBearing()) bearing else null,
    speedMetersPerSecond = if (hasSpeed()) speed else null,
    timestampMillis = time,
)

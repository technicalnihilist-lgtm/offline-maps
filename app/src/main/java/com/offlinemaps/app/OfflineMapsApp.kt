package com.offlinemaps.app

import android.app.Application
import com.offlinemaps.app.di.ServiceLocator
import org.maplibre.android.MapLibre

class OfflineMapsApp : Application() {

    lateinit var locator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        // MapLibre needs a single process-wide initialisation before any MapView is created.
        MapLibre.getInstance(this)
        locator = ServiceLocator(this)
        locator.restorePendingDownloads()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Release the routing graph and cached search state; both are rebuilt on demand.
        locator.onLowMemory()
    }
}

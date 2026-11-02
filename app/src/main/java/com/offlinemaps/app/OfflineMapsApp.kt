package com.offlinemaps.app

import android.app.Application
import com.offlinemaps.app.di.ServiceLocator

class OfflineMapsApp : Application() {

    lateinit var locator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        // Cold-start timings are only logged in debuggable builds, as one line per phase plus a
        // final breakdown (see StartupTrace).
        StartupTrace.install(this)
        val trace = StartupTrace.process
        trace.begin()
        trace.trace(StartupTrace.PHASE_APPLICATION_ONCREATE) {
            // MapLibre is deliberately NOT initialised here: it has to happen before the first
            // MapView exists, and not one moment earlier, so it now runs from the map view's factory
            // (MapLibreBootstrap, called by MapCanvas).
            // Debug builds watch the main thread for disk I/O while the first frame is produced, so
            // a regression that puts SQLite or file access back on it is reported instead of
            // guessed at.
            StartupIoAudit.install(this)
            // Nothing else happens here on purpose: the locator is lazy, and the restore of
            // interrupted downloads is deferred to the first frame (see MainActivity) so cold start
            // does not build the download engine or query the download table.
            locator = ServiceLocator(this)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Release the routing graph and cached search state; both are rebuilt on demand.
        locator.onLowMemory()
    }
}

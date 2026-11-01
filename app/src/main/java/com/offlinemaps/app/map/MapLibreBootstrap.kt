package com.offlinemaps.app.map

import android.content.Context
import com.offlinemaps.app.StartupTrace
import org.maplibre.android.MapLibre

/**
 * Deferred, process-wide MapLibre initialisation (OPTIMIZATION.md A-03).
 *
 * MapLibre must be initialised before the first `MapView` is constructed, but nothing before that
 * needs it - the first frame does not, and neither does any screen that is not the map. It used to
 * be called from `Application.onCreate`, which put the native setup (and the disk it touches) in
 * front of everything else. It is now called from the AndroidView factory in `MapCanvas`, i.e. the
 * moment the map view is actually attached, and never earlier.
 *
 * The call is idempotent: the factory can run again (configuration change, tab switch) without
 * re-initialising, and [MapLibre.getInstance] is only ever called once per process.
 */
object MapLibreBootstrap {

    @Volatile
    private var initialised = false

    /** True once [ensureInitialised] has run for this process. */
    val isInitialised: Boolean get() = initialised

    /**
     * Initialises MapLibre once. [initialise] is injectable so a JVM test can assert the call count
     * without loading the native library.
     */
    @Synchronized
    fun ensureInitialised(
        context: Context,
        initialise: (Context) -> Unit = { MapLibre.getInstance(it) },
    ) {
        if (initialised) return
        val trace = StartupTrace.process
        trace.mapLibreInitStarted()
        initialise(context.applicationContext)
        trace.mapLibreInitFinished()
        initialised = true
    }

    /** Drops the flag so a test can drive the bootstrap more than once. */
    @Synchronized
    fun resetForTest() {
        initialised = false
    }
}

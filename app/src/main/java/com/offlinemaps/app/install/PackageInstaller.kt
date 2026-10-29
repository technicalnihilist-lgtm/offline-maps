package com.offlinemaps.app.install

import android.content.Context
import android.util.Log
import com.offlinemaps.app.di.ServiceLocator
import com.offlinemaps.core.common.MapPackageType
import com.offlinemaps.core.common.TravelProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns a downloaded file into something the app can use offline.
 *
 * Each package type has its own install step:
 *  - ROUTING_OSM  -> GraphHopper imports the extract into an on-device routing graph
 *  - PLACES_PACK  -> rows go into the SQLite FTS search index
 *  - MBTILES      -> merged into MapLibre's offline tile store
 * Tile regions are installed by MapLibre itself and never reach this class.
 */
class PackageInstaller(
    private val context: Context,
    private val locator: ServiceLocator,
) {

    sealed interface InstallResult {
        data class Success(val message: String) : InstallResult
        data class Failure(val message: String) : InstallResult
    }

    suspend fun install(
        regionId: String,
        displayName: String,
        type: MapPackageType,
        file: File,
        onProgress: (String) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        try {
            when (type) {
                MapPackageType.ROUTING_OSM -> installRouting(regionId, file, onProgress)
                MapPackageType.PLACES_PACK -> installPlaces(regionId, file, onProgress)
                MapPackageType.MBTILES -> installMbtiles(file, onProgress)
                MapPackageType.TILE_REGION -> InstallResult.Success("$displayName is ready offline.")
            }
        } catch (e: OutOfMemoryError) {
            InstallResult.Failure(
                "Not enough memory to build the routing graph for $displayName. " +
                    "Try a smaller region - very large extracts (a whole country) need a desktop tool.",
            )
        } catch (e: Throwable) {
            // Catch Throwable, not Exception: a missing desktop-only class on Android surfaces as
            // NoClassDefFoundError, and letting an Error escape here kills the whole app.
            Log.e(TAG, "install failed for $regionId", e)
            InstallResult.Failure(describe(e, displayName))
        }
    }

    private suspend fun installRouting(
        regionId: String,
        file: File,
        onProgress: (String) -> Unit,
    ): InstallResult {
        onProgress("Building routing graph (this can take a minute)")
        locator.routingEngine.importOrLoad(file, TravelProfile.entries) { phase -> onProgress(phase) }
        locator.installedRegions.setRoutingReady(regionId, true)
        // The extract has done its job; the graph is what we keep.
        runCatching { file.delete() }
        return InstallResult.Success("Routing data for this region is ready offline.")
    }

    private suspend fun installPlaces(
        regionId: String,
        file: File,
        onProgress: (String) -> Unit,
    ): InstallResult {
        onProgress("Indexing places for offline search")
        locator.search.importPack(regionId, file) { count -> onProgress("Indexed $count places") }
        locator.installedRegions.setSearchReady(regionId, true)
        return InstallResult.Success("Offline search is ready for this region.")
    }

    private suspend fun installMbtiles(file: File, onProgress: (String) -> Unit): InstallResult {
        onProgress("Merging MBTiles into offline storage")
        return locator.offlineRegions.mergeMbtiles(file.absolutePath).fold(
            onSuccess = { InstallResult.Success("Offline tiles imported.") },
            onFailure = { InstallResult.Failure(it.message ?: "MBTiles import failed.") },
        )
    }

    /**
     * Installs the region bundled with the app so a brand-new device can demonstrate real offline
     * routing and search with no network at all. The bundled extract is a small OpenStreetMap
     * sample (Andorra) and is clearly labelled as such in the UI.
     */
    suspend fun installBundledSample(onProgress: (String) -> Unit = {}): InstallResult =
        withContext(Dispatchers.IO) {
            try {
                onProgress("Copying bundled sample region")
                val osmFile = File(locator.packagesDir, SAMPLE_OSM)
                if (!osmFile.exists() || osmFile.length() == 0L) {
                    context.assets.open(SAMPLE_OSM).use { input ->
                        osmFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                val routing = installRouting(BUNDLED_REGION_ID, osmFile, onProgress)
                if (routing is InstallResult.Failure) return@withContext routing

                onProgress("Indexing bundled places")
                val placesFile = File(locator.packagesDir, SAMPLE_PLACES)
                if (!placesFile.exists() || placesFile.length() == 0L) {
                    context.assets.open(SAMPLE_PLACES).use { input ->
                        placesFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                val places = installPlaces(BUNDLED_REGION_ID, placesFile, onProgress)
                if (places is InstallResult.Failure) return@withContext places

                InstallResult.Success("Sample region installed: routing and offline search are ready.")
            } catch (e: OutOfMemoryError) {
                InstallResult.Failure(
                    "Not enough memory to build the routing graph for the sample region. " +
                        "Close other apps and try again.",
                )
            } catch (e: Throwable) {
                Log.e(TAG, "bundled sample install failed", e)
                InstallResult.Failure(describe(e, "the sample region"))
            }
        }

    /** Turns any throwable into something a user can act on, without leaking internals. */
    private fun describe(error: Throwable, subject: String): String = when (error) {
        is NoClassDefFoundError, is NoSuchMethodError ->
            "The routing engine needs a component that is missing on this device " +
                "(${error.message}). This is a packaging problem in the app, not something you did - " +
                "please report it."
        is OutOfMemoryError ->
            "Not enough memory to prepare $subject. Close other apps and try again."
        is SecurityException ->
            "Storage permission was refused, so $subject could not be installed."
        else -> error.message ?: "Installing $subject failed (${error.javaClass.simpleName})."
    }

    companion object {
        private const val TAG = "PackageInstaller"
        const val BUNDLED_REGION_ID = "sample-andorra"
        const val SAMPLE_OSM = "sample_andorra.osm.pbf"
        const val SAMPLE_PLACES = "places_andorra.jsonl.gz"
    }
}

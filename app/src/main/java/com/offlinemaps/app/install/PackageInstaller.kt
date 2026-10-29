package com.offlinemaps.app.install

import android.content.Context
import android.util.Log
import com.offlinemaps.app.di.ServiceLocator
import com.offlinemaps.core.common.MapPackageType
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

    /**
     * Installs a pre-built routing graph package (a zip of a GraphHopper graph directory).
     *
     * A raw `.osm.pbf` cannot be turned into a graph on the phone: GraphHopper's OSM reader needs
     * `javax.xml.stream`, which Android does not implement, so the class cannot be loaded on a
     * device. Graphs are built with `tools/build_routing_graph` and shipped as a package.
     */
    private suspend fun installRouting(
        regionId: String,
        file: File,
        onProgress: (String) -> Unit,
    ): InstallResult {
        if (file.name.contains(".osm.pbf", ignoreCase = true) || file.extension.equals("pbf", true)) {
            return InstallResult.Failure(
                "This package is a raw OpenStreetMap extract. Building a routing graph from it needs " +
                    "desktop-class libraries that Android does not provide, so it cannot be imported on " +
                    "the phone. Use a pre-built routing package instead - see tools/build_routing_graph.",
            )
        }
        onProgress("Unpacking routing graph")
        val graphDir = locator.routingEngine.graphDirectory()
        graphDir.deleteRecursively()
        graphDir.mkdirs()
        try {
            unzip(file, graphDir)
        } catch (e: Throwable) {
            Log.e(TAG, "routing package unzip failed", e)
            return InstallResult.Failure("The routing package is not a valid graph archive.")
        }
        onProgress("Loading routing graph")
        return locator.routingEngine.load { phase -> onProgress(phase) }.fold(
            onSuccess = {
                locator.installedRegions.setRoutingReady(regionId, true)
                InstallResult.Success("Routing data for this region is ready offline.")
            },
            onFailure = { InstallResult.Failure(it.message ?: "The routing graph could not be loaded.") },
        )
    }

    /** Extracts a zip, refusing entries that would escape the destination directory. */
    private fun unzip(zip: File, destination: File) {
        java.util.zip.ZipFile(zip).use { archive ->
            archive.entries().asSequence().forEach { entry ->
                val target = File(destination, entry.name)
                val root = destination.canonicalPath + File.separator
                if (!target.canonicalPath.startsWith(root)) {
                    throw SecurityException("Unsafe path in routing package: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
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
                onProgress("Installing bundled routing graph")
                val graphZip = File(locator.packagesDir, SAMPLE_GRAPH)
                if (!graphZip.exists() || graphZip.length() == 0L) {
                    context.assets.open(SAMPLE_GRAPH).use { input ->
                        graphZip.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                val routing = installRouting(BUNDLED_REGION_ID, graphZip, onProgress)
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
        /** Pre-built GraphHopper graph for the bundled sample region. */
        const val SAMPLE_GRAPH = "routing_andorra.zip"
        const val SAMPLE_PLACES = "places_andorra.jsonl.gz"
    }
}

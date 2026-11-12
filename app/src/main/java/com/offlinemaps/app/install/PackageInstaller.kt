package com.offlinemaps.app.install

import android.content.Context
import android.util.Log
import com.offlinemaps.app.di.ServiceLocator
import com.offlinemaps.core.common.MapPackageType
import com.offlinemaps.core.offline.ZipExtractor
import kotlinx.coroutines.CancellationException
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
                MapPackageType.PLACES_PACK -> installPlaces(regionId, displayName, file, onProgress).result
                MapPackageType.MBTILES -> installMbtiles(file, onProgress)
                MapPackageType.TILE_REGION -> InstallResult.Success("$displayName is ready offline.")
            }
        } catch (e: OutOfMemoryError) {
            InstallResult.Failure(
                "Not enough memory to build the routing graph for $displayName. " +
                    "Try a smaller region - very large extracts (a whole country) need a desktop tool.",
            )
        } catch (e: CancellationException) {
            // Cancellation is not a failure: the caller's scope is gone, so nothing should be
            // reported. Swallowing it here used to turn a cancelled install into a bogus error.
            throw e
        } catch (e: Throwable) {
            // Catch Throwable, not Exception: a missing desktop-only class on Android surfaces as
            // NoClassDefFoundError, and letting an Error escape here kills the whole app.
            Log.e(TAG, "install failed for $regionId", e)
            InstallResult.Failure(InstallFailures.describe(e, displayName))
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
            // Streamed through one reused 64 KiB buffer: a graph package is hundreds of files and
            // hundreds of megabytes, and nothing here may be sized from the archive.
            ZipExtractor.extract(file, graphDir)
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

    /** The result of indexing a places pack, plus whether part of it could not be read. */
    private data class PlacesIndexOutcome(val result: InstallResult, val damaged: Boolean)

    /**
     * Indexes a places pack.
     *
     * A pack that is damaged part-way (a download that stopped early, a line that is not JSON) is
     * not thrown away: the reader keeps every record that decodes, so the region still gets offline
     * search over what survived, and only the sentence shown afterwards says the pack was incomplete
     * (OPTIMIZATION.md I-04). A pack that yields nothing at all is a failed install, because there
     * is no index to mark ready.
     */
    private suspend fun installPlaces(
        regionId: String,
        displayName: String,
        file: File,
        onProgress: (String) -> Unit,
    ): PlacesIndexOutcome {
        onProgress("Indexing places for offline search")
        val stats = locator.search.importPack(regionId, file) { count -> onProgress("Indexed $count places") }
        if (stats.records == 0) {
            return PlacesIndexOutcome(
                InstallResult.Failure(
                    "The places pack for $displayName is damaged or empty, so no places could be read " +
                        "from it. Delete it and download it again.",
                ),
                damaged = true,
            )
        }
        locator.installedRegions.setSearchReady(regionId, true)
        return if (stats.damaged) {
            PlacesIndexOutcome(
                InstallResult.Success(
                    "Offline search is partly ready for $displayName: ${stats.records} places were " +
                        "indexed, but the pack is damaged, so some places are missing. Download it " +
                        "again to complete the index.",
                ),
                damaged = true,
            )
        } else {
            PlacesIndexOutcome(
                InstallResult.Success("Offline search is ready for this region."),
                damaged = false,
            )
        }
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
                val placesFile = File(locator.packagesDir, PLACES_TARGET)
                if (!placesFile.exists() || placesFile.length() == 0L) {
                    // The packaging step may store the pack either gzipped or decompressed (AGP
                    // rewrites ".gz" assets), so accept whichever name actually ships. The reader
                    // sniffs the gzip magic and handles both.
                    val copied = copyFirstAsset(placesFile, SAMPLE_PLACES, SAMPLE_PLACES_PLAIN)
                    if (!copied) {
                        return@withContext InstallResult.Failure(
                            "The bundled places pack is missing from this build.",
                        )
                    }
                }
                val places = installPlaces(BUNDLED_REGION_ID, "the sample region", placesFile, onProgress)
                if (places.result is InstallResult.Failure) return@withContext places.result
                // A damaged bundled pack gets its own sentence rather than the cheerful "sample
                // installed" one, so the missing places are not silently hidden.
                if (places.damaged) return@withContext places.result

                InstallResult.Success("Sample region installed: routing and offline search are ready.")
            } catch (e: OutOfMemoryError) {
                InstallResult.Failure(
                    "Not enough memory to build the routing graph for the sample region. " +
                        "Close other apps and try again.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "bundled sample install failed", e)
                InstallResult.Failure(InstallFailures.describe(e, "the sample region"))
            }
        }

    /** Copies the first asset name that exists, so packaging differences cannot break install. */
    private fun copyFirstAsset(target: File, vararg assetNames: String): Boolean {
        for (name in assetNames) {
            val copied = runCatching {
                context.assets.open(name).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                true
            }.getOrDefault(false)
            if (copied) return true
        }
        return false
    }

    companion object {
        private const val TAG = "PackageInstaller"
        const val BUNDLED_REGION_ID = "sample-andorra"
        /** Pre-built GraphHopper graph for the bundled sample region. */
        const val SAMPLE_GRAPH = "routing_andorra.zip"
        const val SAMPLE_PLACES = "places_andorra.jsonl.gz"

        /** Same pack if the build stores it decompressed. */
        const val SAMPLE_PLACES_PLAIN = "places_andorra.jsonl"

        /** Filename used once copied into app storage. */
        const val PLACES_TARGET = "places_andorra.jsonl"
    }
}

package com.offlinemaps.app.di

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.offlinemaps.core.common.DownloadStatus
import com.offlinemaps.core.common.MapPackageType
import com.offlinemaps.core.data.AppDatabase
import com.offlinemaps.core.data.DownloadRecord
import com.offlinemaps.core.data.DownloadRepository
import com.offlinemaps.core.data.InstalledRegionRepository
import com.offlinemaps.core.data.RecentSearchRepository
import com.offlinemaps.core.data.SavedPlacesRepository
import com.offlinemaps.core.data.SettingsStore
import com.offlinemaps.core.location.LocationSource
import com.offlinemaps.core.map.MapConfig
import com.offlinemaps.core.map.MapStyleFactory
import com.offlinemaps.core.map.OfflineRegionController
import com.offlinemaps.core.map.StyleCacheMemoryPolicy
import com.offlinemaps.core.offline.AndroidNetworkStateProvider
import com.offlinemaps.core.offline.AndroidStorageProbe
import com.offlinemaps.core.offline.DownloadEntry
import com.offlinemaps.core.offline.DownloadManager
import com.offlinemaps.core.offline.DownloadRequest
import com.offlinemaps.core.offline.DownloadStore
import com.offlinemaps.core.offline.PartialFileSweeper
import com.offlinemaps.core.offline.SharedHttpClient
import com.offlinemaps.core.routing.GraphInstallTransaction
import com.offlinemaps.core.routing.RoutingEngine
import com.offlinemaps.core.routing.RoutingMemoryPolicy
import com.offlinemaps.core.search.SearchIndex
import com.offlinemaps.core.search.SearchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hand-rolled dependency wiring.
 *
 * A DI framework would add build time and a reflection layer for very little benefit at this size,
 * so everything is constructed once here and handed to view models. It also makes the dependency
 * direction obvious: UI depends on core, never the other way round.
 *
 * Every member is lazy: constructing the locator itself costs nothing and touches no disk, so
 * cold start only pays for the services the first screen actually asks for. Keep it that way —
 * nothing here may be initialised eagerly from `Application.onCreate`.
 */
class ServiceLocator(anyContext: Context) {

    /**
     * The only context this locator - and so every singleton built from it - may keep.
     *
     * Coerced to the application context on the way in (OPTIMIZATION.md H-01). The locator lives as
     * long as the process, so a caller that handed it an Activity would otherwise pin that screen,
     * its view tree and its window for the life of the app; the services below (search index,
     * offline regions, location source, settings) all store what they are given, so the coercion has
     * to happen here, once, at the single place they are built.
     *
     * The parameter is deliberately not called `context`: a constructor parameter is in scope inside
     * the `by lazy` initialisers below and would shadow the property of that name, handing every
     * service the raw context this coercion exists to replace. That is exactly the leak the
     * retention test in `app/src/test` now catches.
     */
    val appContext: Context = anyContext.applicationContext ?: anyContext

    private val context: Context get() = appContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Guards [restorePendingDownloads] so the restore happens at most once per process. */
    private val restoreRequested = AtomicBoolean(false)

    /**
     * Tile servers, attribution and the zoom caps the generated style is built from. A plain value:
     * it touches no disk and costs nothing to construct.
     */
    val mapConfig: MapConfig = MapConfig.DEFAULT

    val database: AppDatabase by lazy { AppDatabase.get(context) }
    val settings: SettingsStore by lazy { SettingsStore(context) }

    val savedPlaces by lazy { SavedPlacesRepository(database.savedPlaceDao()) }
    val recentSearches by lazy { RecentSearchRepository(database.recentSearchDao()) }
    val downloadRecords by lazy { DownloadRepository(database.downloadDao()) }
    val installedRegions by lazy { InstalledRegionRepository(database.installedRegionDao()) }

    val searchIndex: SearchIndex by lazy { SearchIndex(context) }
    val search: SearchRepository by lazy { SearchRepository(searchIndex, logger = ::logSearchTiming) }

    /** Debug builds only: the search warm-up and first-query timings go to logcat. */
    private val debugBuild: Boolean by lazy {
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun logSearchTiming(line: String) {
        if (debugBuild) Log.d(SEARCH_TAG, line)
    }

    /**
     * Opens the offline search index once per process, in the background, so the first search does
     * not pay for opening SQLite (OPTIMIZATION.md A-06).
     *
     * Called once the first frame is out (see `MainActivity`) and never from `Application.onCreate`,
     * so cold start does not wait for it. It changes no data and shows nothing; the warm-up cost and
     * the first-query cost are logged in debuggable builds only. Repeated calls are no-ops.
     */
    fun prewarmSearch() {
        applicationScope.launch { search.prewarmIndex() }
    }

    val locationSource by lazy { LocationSource(context) }
    val network by lazy { AndroidNetworkStateProvider(context) }
    val offlineRegions by lazy { OfflineRegionController(context) }

    /**
     * The app's single HTTP client (OPTIMIZATION.md C-04). A client owns its connection pool and
     * its dispatcher, so the size probe and the download of the same package have to go through the
     * same instance for the second one to reuse the first one's connection; construction lives in
     * [SharedHttpClient] so nothing in the app can build a second one by accident.
     */
    val httpClient: OkHttpClient get() = SharedHttpClient.client

    /** Root for downloaded packages. Uses app-specific external storage when available. */
    val packagesDir: File by lazy { packagesRoot().apply { mkdirs() } }

    /** The packages root as a path only - reading it must not create anything. */
    private fun packagesRoot(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "packages")
    }

    val routingEngine: RoutingEngine by lazy { RoutingEngine(File(packagesDir, ROUTING_GRAPH_DIRECTORY)) }

    val navigationManager: com.offlinemaps.app.nav.NavigationManager by lazy {
        com.offlinemaps.app.nav.NavigationManager(this)
    }

    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            client = httpClient,
            store = RoomDownloadStore(downloadRecords),
            storage = AndroidStorageProbe(),
            network = network,
            scope = applicationScope,
        )
    }

    fun downloadRequestFor(record: DownloadRecord): DownloadRequest = DownloadRequest(
        packageKey = record.packageKey,
        regionId = record.regionId,
        displayName = record.name,
        type = record.type,
        url = record.url,
        expectedBytes = record.bytesTotal,
        sha256 = record.sha256,
        md5 = record.md5,
        fileName = fileNameFor(record),
        targetDirectory = packagesDir,
    )

    /** The on-disk name of a package, and so the base name of its `.part` / `.meta` siblings. */
    private fun fileNameFor(record: DownloadRecord): String =
        "${record.packageKey}.${extensionFor(record.type)}"

    private fun extensionFor(type: MapPackageType): String = when (type) {
        MapPackageType.ROUTING_OSM -> "osm.pbf"
        MapPackageType.PLACES_PACK -> "jsonl.gz"
        MapPackageType.MBTILES -> "mbtiles"
        MapPackageType.TILE_REGION -> "tiles"
    }

    /**
     * Re-queues downloads that were interrupted by a process death or reboot.
     *
     * Deliberately not called from [android.app.Application.onCreate]: the restore is worth nothing
     * to the first frame, so it runs once the UI is up (see `MainActivity`). Even then it stays
     * lazy — the download table is the only thing read when there is nothing to resume, and
     * [downloadManager] (with its HTTP client and storage probe) is only built once a record is
     * actually found. Repeated calls are no-ops, so a recreated activity cannot queue a download
     * twice.
     */
    fun restorePendingDownloads() {
        if (!restoreRequested.compareAndSet(false, true)) return
        applicationScope.launch {
            sweepOrphanPartials()
            val resumable = downloadRecords.pending().filter { it.status != DownloadStatus.PAUSED }
            if (resumable.isEmpty()) return@launch
            val wifiOnly = settings.current().wifiOnlyDownloads
            resumable.forEach { record -> downloadManager.start(downloadRequestFor(record), wifiOnly) }
        }
    }

    /**
     * Removes `<name>.part` / `<name>.meta` files that no download record claims (OPTIMIZATION.md
     * C-06). A cancel deletes its partial itself once the transfer has stopped; this covers the
     * case where the process died between the two, which would otherwise leave the bytes on disk
     * with nothing left to resume them. Runs off the main thread, after the first frame, and asks
     * the download table only about files it actually found - normally none.
     */
    private suspend fun sweepOrphanPartials() {
        // Resolved without [packagesDir] so a device that has never downloaded anything does not
        // have the directory created for it just to look inside it.
        val directory = packagesRoot()
        withContext(Dispatchers.IO) {
            val recovery = settleInterruptedRoutingInstall()
            if (recovery.changed) Log.i(TAG, "settled an interrupted routing install: $recovery")
        }
        if (!directory.isDirectory) return
        PartialFileSweeper.sweep(directory) { baseName ->
            val key = packageKeyForFileName(baseName)
            // A name that is not one of ours is left alone rather than deleted on a guess.
            key == null || downloadRecords.find(key) != null
        }
    }

    /**
     * Settles the debris of a routing install the process did not survive (OPTIMIZATION.md I-07).
     *
     * An install unpacks beside the live graph and swaps the two with renames, so a kill can leave
     * at most a stale `<name>.staging` directory or a graph parked in `<name>.previous`; the package
     * that was live is renamed back if the swap was interrupted between its two renames. This runs
     * on the same background pass as the partial-file sweep, after the first frame and before any
     * install can be started from the downloads screen, and on a healthy device it finds nothing.
     * It reads and renames directories only - it never unpacks, loads or validates a graph.
     */
    internal fun settleInterruptedRoutingInstall(): GraphInstallTransaction.Recovery =
        GraphInstallTransaction.recover(File(packagesRoot(), ROUTING_GRAPH_DIRECTORY))

    /** The package key a package file name was built from, or null when it is not one of ours. */
    private fun packageKeyForFileName(fileName: String): String? {
        val extension = MapPackageType.entries
            .map { extensionFor(it) }
            .firstOrNull { fileName.endsWith(".$it") } ?: return null
        return fileName.removeSuffix(".$extension")
    }

    /**
     * True while a guidance session is running. A trim must not close the graph under it: the
     * session needs it for its next reroute, so closing would only cost a reload a few seconds
     * later (OPTIMIZATION.md E-01). Set by `NavigationManager`.
     */
    @Volatile
    private var guidanceActive = false

    fun setGuidanceActive(active: Boolean) {
        guidanceActive = active
    }

    /** System-wide low memory: release the graph unconditionally unless it is in use. */
    fun onLowMemory() {
        releaseRoutingGraph()
    }

    /**
     * Per-process memory trim. [level] is one of the `ComponentCallbacks2.TRIM_MEMORY_*` values and
     * is judged by [RoutingMemoryPolicy], so only the levels that mean real pressure (or "no longer
     * visible") free the graph. It is reloaded lazily by the engine, so this is safe to do at any
     * time - the next route reads it back from disk.
     *
     * The same signal releases the generated style documents (OPTIMIZATION.md H-02), judged
     * separately by [StyleCacheMemoryPolicy] because they are rebuilt on the main thread and so
     * are not worth dropping while the app is merely in the background. Everything else this
     * locator holds is either cheap or not ours to free: the search index's cursors are all
     * `.use`-scoped inside [SearchIndex], so no cursor outlives its query, and the tile bitmaps
     * belong to MapLibre, which trims its own caches from the same callbacks.
     */
    fun onTrimMemory(level: Int) {
        if (RoutingMemoryPolicy.shouldRelease(level)) releaseRoutingGraph()
        if (StyleCacheMemoryPolicy.shouldRelease(level)) MapStyleFactory.releaseCacheForMemoryTrim()
    }

    private fun releaseRoutingGraph() {
        if (guidanceActive) return
        routingEngine.close()
    }

    companion object {
        /** Log tag for the search warm-up timings (OPTIMIZATION.md A-06). */
        private const val SEARCH_TAG = "SearchPrewarm"

        private const val TAG = "ServiceLocator"

        /** Path, under the packages root, of the live routing graph directory. */
        private const val ROUTING_GRAPH_DIRECTORY = "routing/current"
    }
}

/** Adapts the Room-backed download table to the engine's storage interface. */
private class RoomDownloadStore(private val repository: DownloadRepository) : DownloadStore {
    override suspend fun find(key: String): DownloadEntry? = repository.find(key)?.toEntry()
    override suspend fun save(entry: DownloadEntry) { repository.upsert(entry.toRecord()) }
    override suspend fun update(key: String, status: DownloadStatus, bytes: Long, error: String?) {
        repository.updateProgress(key, status, bytes, error)
    }
    override suspend fun delete(key: String) = repository.delete(key)
    override suspend fun pending(): List<DownloadEntry> = repository.pending().map { it.toEntry() }
}

private fun DownloadRecord.toEntry() = DownloadEntry(
    packageKey = packageKey, regionId = regionId, name = name, type = type, url = url,
    status = status, bytesTotal = bytesTotal, bytesDownloaded = bytesDownloaded,
    localPath = localPath, sha256 = sha256, md5 = md5, errorMessage = errorMessage,
)

private fun DownloadEntry.toRecord() = DownloadRecord(
    packageKey = packageKey, regionId = regionId, name = name, type = type, url = url,
    status = status, bytesTotal = bytesTotal, bytesDownloaded = bytesDownloaded,
    localPath = localPath, sha256 = sha256, md5 = md5, errorMessage = errorMessage,
    updatedAt = System.currentTimeMillis(),
)

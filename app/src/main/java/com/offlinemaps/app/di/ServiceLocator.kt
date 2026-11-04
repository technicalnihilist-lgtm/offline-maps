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
import com.offlinemaps.core.map.OfflineRegionController
import com.offlinemaps.core.offline.AndroidNetworkStateProvider
import com.offlinemaps.core.offline.AndroidStorageProbe
import com.offlinemaps.core.offline.DownloadEntry
import com.offlinemaps.core.offline.DownloadManager
import com.offlinemaps.core.offline.DownloadRequest
import com.offlinemaps.core.offline.DownloadStore
import com.offlinemaps.core.routing.RoutingEngine
import com.offlinemaps.core.search.SearchIndex
import com.offlinemaps.core.search.SearchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
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
class ServiceLocator(val appContext: Context) {

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

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Root for downloaded packages. Uses app-specific external storage when available. */
    val packagesDir: File by lazy {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, "packages").apply { mkdirs() }
    }

    val routingEngine: RoutingEngine by lazy { RoutingEngine(File(packagesDir, "routing/current")) }

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
        fileName = "${record.packageKey}.${extensionFor(record.type)}",
        targetDirectory = packagesDir,
    )

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
            val resumable = downloadRecords.pending().filter { it.status != DownloadStatus.PAUSED }
            if (resumable.isEmpty()) return@launch
            val wifiOnly = settings.current().wifiOnlyDownloads
            resumable.forEach { record -> downloadManager.start(downloadRequestFor(record), wifiOnly) }
        }
    }

    fun onLowMemory() {
        routingEngine.close()
    }

    companion object {
        /** Log tag for the search warm-up timings (OPTIMIZATION.md A-06). */
        private const val SEARCH_TAG = "SearchPrewarm"
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

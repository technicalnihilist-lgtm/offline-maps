package com.offlinemaps.app.di

import android.content.Context
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

/**
 * Hand-rolled dependency wiring.
 *
 * A DI framework would add build time and a reflection layer for very little benefit at this size,
 * so everything is constructed once here and handed to view models. It also makes the dependency
 * direction obvious: UI depends on core, never the other way round.
 */
class ServiceLocator(val appContext: Context) {

    private val context: Context get() = appContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.get(context) }
    val settings: SettingsStore by lazy { SettingsStore(context) }

    val savedPlaces by lazy { SavedPlacesRepository(database.savedPlaceDao()) }
    val recentSearches by lazy { RecentSearchRepository(database.recentSearchDao()) }
    val downloadRecords by lazy { DownloadRepository(database.downloadDao()) }
    val installedRegions by lazy { InstalledRegionRepository(database.installedRegionDao()) }

    val searchIndex: SearchIndex by lazy { SearchIndex(context) }
    val search: SearchRepository by lazy { SearchRepository(searchIndex) }

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

    /** Re-queues downloads that were interrupted by a process death or reboot. */
    fun restorePendingDownloads() {
        applicationScope.launch {
            downloadRecords.pending().forEach { record ->
                if (record.status == DownloadStatus.PAUSED) return@forEach
                downloadManager.start(downloadRequestFor(record), settings.current().wifiOnlyDownloads)
            }
        }
    }

    fun onLowMemory() {
        routingEngine.close()
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

package com.offlinemaps.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlinemaps.app.di.ServiceLocator
import com.offlinemaps.app.install.PackageInstaller
import com.offlinemaps.core.common.DownloadStatus
import com.offlinemaps.core.common.Formatters
import com.offlinemaps.core.common.MapPackageType
import com.offlinemaps.core.common.RegionCatalogEntry
import com.offlinemaps.core.common.RegionPackage
import com.offlinemaps.core.data.DownloadRecord
import com.offlinemaps.core.data.InstalledRegion
import com.offlinemaps.core.offline.AndroidStorageProbe
import com.offlinemaps.core.offline.DownloadProgress
import com.offlinemaps.core.offline.DownloadRequest
import com.offlinemaps.core.offline.RegionCatalogParser
import com.offlinemaps.core.offline.StorageGuard
import com.offlinemaps.core.offline.TileRegionEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DownloadsUiState(
    val regions: List<RegionCatalogEntry> = emptyList(),
    val downloads: List<DownloadRecord> = emptyList(),
    val installed: List<InstalledRegion> = emptyList(),
    val progress: Map<String, DownloadProgress> = emptyMap(),
    val messages: Map<String, String> = emptyMap(),
    val resolvedSizes: Map<String, Long> = emptyMap(),
    val availableBytes: Long = 0L,
    val storageLimitBytes: Long = 0L,
    val wifiOnly: Boolean = true,
    val loading: Boolean = true,
    val banner: String? = null,
)

/**
 * Drives the download manager: catalogue, size probing, start/pause/resume/cancel/delete and the
 * post-download install step. Nothing here fakes progress - every number comes from the engine.
 */
class DownloadsViewModel(private val locator: ServiceLocator) : ViewModel() {

    private val _state = MutableStateFlow(DownloadsUiState())
    val state: StateFlow<DownloadsUiState> = _state.asStateFlow()

    init {
        loadCatalog()
        viewModelScope.launch {
            locator.downloadRecords.observeAll().collect { records ->
                _state.update { it.copy(downloads = records) }
                records.filter { it.status == DownloadStatus.COMPLETED && it.localPath != null }
                    .forEach { record -> installIfNeeded(record) }
            }
        }
        viewModelScope.launch {
            locator.installedRegions.observe().collect { installed -> _state.update { it.copy(installed = installed) } }
        }
        viewModelScope.launch {
            locator.downloadManager.states.collect { states ->
                _state.update { current ->
                    current.copy(
                        progress = states.mapNotNullValues { it.value.progress },
                        messages = states.mapNotNullValues { it.value.error },
                    )
                }
            }
        }
        refreshStorage()
    }

    fun refreshStorage() {
        val available = locator.appContext.getExternalFilesDir(null)
            ?.let { AndroidStorageProbe().availableBytes(it) } ?: 0L
        _state.update {
            it.copy(
                availableBytes = available,
                storageLimitBytes = locator.settings.current().storageLimitMb * 1024 * 1024,
                wifiOnly = locator.settings.current().wifiOnlyDownloads,
            )
        }
    }

    private fun loadCatalog() {
        viewModelScope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    locator.appContext.assets.open("regions.json").bufferedReader().use { it.readText() }
                }.getOrNull()
            }
            val regions = raw?.let {
                runCatching { RegionCatalogParser.toDomain(RegionCatalogParser.parse(it)) }.getOrNull()
            } ?: emptyList()
            _state.update { it.copy(regions = regions, loading = false) }
        }
    }

    /** Estimated size for a region before download - shown as an estimate, never as exact. */
    fun estimatedTileBytes(region: RegionCatalogEntry): Long =
        TileRegionEstimator.estimateBytes(region.bounds, region.minZoom, region.maxZoom)

    fun zoomDescription(region: RegionCatalogEntry): String = TileRegionEstimator.describeZoom(region.maxZoom)

    /** Resolves the real size from the server (HEAD) so the user sees it before starting. */
    fun probeSize(region: RegionCatalogEntry, pkg: RegionPackage) {
        if (_state.value.resolvedSizes.containsKey(pkg.url)) return
        viewModelScope.launch {
            val size = withContext(Dispatchers.IO) {
                runCatching {
                    val request = okhttp3.Request.Builder().url(pkg.url).head().build()
                    locator.httpClient.newCall(request).execute().use { response ->
                        response.header("Content-Length")?.toLongOrNull() ?: 0L
                    }
                }.getOrDefault(0L)
            }
            if (size > 0) _state.update { it.copy(resolvedSizes = it.resolvedSizes + (pkg.url to size)) }
        }
    }

    fun start(region: RegionCatalogEntry, pkg: RegionPackage) {
        val key = "${region.id}-${pkg.type.name}"
        val expected = _state.value.resolvedSizes[pkg.url] ?: pkg.sizeBytes
        val available = _state.value.availableBytes
        val used = _state.value.installed.sumOf { it.sizeBytes }
        val reason = StorageGuard.reasonFor(available, expected, _state.value.storageLimitBytes, used)
        if (expected > 0 && reason != null) {
            _state.update { it.copy(banner = reason) }
            return
        }
        val request = DownloadRequest(
            packageKey = key,
            regionId = region.id,
            displayName = "${region.name} · ${pkg.type.name.lowercase().replace('_', ' ')}",
            type = pkg.type,
            url = pkg.url,
            expectedBytes = expected,
            sha256 = pkg.sha256,
            md5 = pkg.md5,
            fileName = "$key.${extensionFor(pkg.type)}",
            targetDirectory = locator.packagesDir,
        )
        viewModelScope.launch {
            locator.downloadRecords.upsert(
                DownloadRecord(
                    packageKey = key,
                    regionId = region.id,
                    name = request.displayName,
                    type = pkg.type,
                    url = pkg.url,
                    status = DownloadStatus.QUEUED,
                    bytesTotal = expected,
                    bytesDownloaded = 0,
                    localPath = null,
                    sha256 = pkg.sha256,
                    md5 = pkg.md5,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            locator.downloadManager.start(request, _state.value.wifiOnly)
        }
    }

    fun pause(record: DownloadRecord) = locator.downloadManager.pause(record.packageKey)

    fun resume(record: DownloadRecord) {
        locator.downloadManager.start(locator.downloadRequestFor(record), _state.value.wifiOnly)
    }

    fun cancel(record: DownloadRecord) = locator.downloadManager.cancel(record.packageKey)

    fun delete(record: DownloadRecord) {
        viewModelScope.launch { locator.downloadManager.deletePackage(locator.downloadRequestFor(record)) }
    }

    fun deleteRegion(region: InstalledRegion) {
        viewModelScope.launch {
            locator.installedRegions.delete(region.regionId)
            locator.search.removeRegion(region.regionId)
            _state.update { it.copy(banner = "${region.name} removed from this device.") }
        }
    }

    fun dismissBanner() = _state.update { it.copy(banner = null) }

    private fun installIfNeeded(record: DownloadRecord) {
        val file = record.localPath?.let { File(it) } ?: return
        if (!file.exists()) return
        if (record.type == MapPackageType.TILE_REGION) return
        val alreadyInstalled = _state.value.installed.any {
            it.regionId == record.regionId &&
                ((record.type == MapPackageType.ROUTING_OSM && it.routingReady) ||
                    (record.type == MapPackageType.PLACES_PACK && it.searchReady))
        }
        if (alreadyInstalled) return
        if (_state.value.messages.containsKey("install-${record.packageKey}")) return

        _state.update { it.copy(messages = it.messages + ("install-${record.packageKey}" to "Installing…")) }
        viewModelScope.launch {
            val installer = PackageInstaller(locator.appContext, locator)
            val result = installer.install(record.regionId, record.name, record.type, file) { phase ->
                _state.update { it.copy(messages = it.messages + ("install-${record.packageKey}" to phase)) }
            }
            when (result) {
                is PackageInstaller.InstallResult.Success -> {
                    val region = _state.value.regions.firstOrNull { it.id == record.regionId }
                    if (region != null) {
                        locator.installedRegions.save(
                            InstalledRegion(
                                regionId = region.id,
                                name = region.name,
                                type = record.type,
                                sizeBytes = record.bytesTotal,
                                version = "1",
                                installedAt = System.currentTimeMillis(),
                                bounds = region.bounds,
                                minZoom = region.minZoom,
                                maxZoom = region.maxZoom,
                                routingReady = record.type == MapPackageType.ROUTING_OSM,
                                searchReady = record.type == MapPackageType.PLACES_PACK,
                            ),
                        )
                    }
                    _state.update {
                        it.copy(
                            banner = result.message,
                            messages = it.messages + ("install-${record.packageKey}" to result.message),
                        )
                    }
                }
                is PackageInstaller.InstallResult.Failure -> _state.update {
                    it.copy(
                        banner = result.message,
                        messages = it.messages + ("install-${record.packageKey}" to result.message),
                    )
                }
            }
        }
    }

    private fun extensionFor(type: MapPackageType): String = when (type) {
        MapPackageType.ROUTING_OSM -> "osm.pbf"
        MapPackageType.PLACES_PACK -> "jsonl.gz"
        MapPackageType.MBTILES -> "mbtiles"
        MapPackageType.TILE_REGION -> "tiles"
    }

    companion object {
        fun sizeLabel(bytes: Long): String = if (bytes > 0) Formatters.bytes(bytes) else "size from server"
    }
}

private inline fun <K, V, R> Map<K, V>.mapNotNullValues(transform: (Map.Entry<K, V>) -> R?): Map<K, R> {
    val result = LinkedHashMap<K, R>(size)
    for (entry in entries) {
        val mapped = transform(entry)
        if (mapped != null) result[entry.key] = mapped
    }
    return result
}

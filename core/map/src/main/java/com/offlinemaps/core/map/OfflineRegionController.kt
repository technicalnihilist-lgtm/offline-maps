package com.offlinemaps.core.map

import android.content.Context
import android.util.Log
import com.offlinemaps.core.common.GeoBounds
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.storage.FileSource
import kotlin.coroutines.resume

/**
 * Wraps MapLibre's native offline storage.
 *
 * MapLibre keeps offline tiles in its own SQLite-backed store, which gives pause/resume,
 * integrity-checked tile databases and automatic use of offline data when a request fails. That is
 * a well-supported offline tile package, and it avoids hand-rolling a tile database.
 *
 * The definition needs a *style URL*. We pass a `file://` URL to the style document this app
 * generates, so the downloader fetches our style and then all of its sources.
 */
class OfflineRegionController(private val context: Context) {

    private val manager: OfflineManager by lazy { OfflineManager.getInstance(context) }

    data class RegionStatus(
        val completedResourceCount: Long,
        val requiredResourceCount: Long,
        val completedResourceSize: Long,
        val isComplete: Boolean,
        val isActive: Boolean,
        val error: String? = null,
    ) {
        val fraction: Float
            get() = if (requiredResourceCount > 0) {
                (completedResourceCount.toFloat() / requiredResourceCount).coerceIn(0f, 1f)
            } else 0f
    }

    /** Creates (or recreates) an offline region and starts downloading it. */
    suspend fun createAndDownload(
        styleFileUrl: String,
        bounds: GeoBounds,
        minZoom: Int,
        maxZoom: Int,
        metadata: ByteArray,
        onStatus: (RegionStatus) -> Unit,
    ): Result<OfflineRegion> = runCatching {
        suspendCancellableCoroutine { cont ->
            val definition = OfflineTilePyramidRegionDefinition(
                styleFileUrl,
                LatLngBounds.Builder()
                    .include(LatLng(bounds.south, bounds.west))
                    .include(LatLng(bounds.north, bounds.east))
                    .build(),
                minZoom.toDouble(),
                maxZoom.toDouble(),
                context.resources.displayMetrics.density,
            )
            manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            onStatus(status.toRegionStatus())
                        }

                        override fun onError(error: OfflineRegionError) {
                            Log.w(TAG, "offline region error: ${error.message} (${error.reason})")
                            onStatus(
                                RegionStatus(0, 0, 0, isComplete = false, isActive = false, error = error.message),
                            )
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            onStatus(
                                RegionStatus(
                                    0, 0, 0, isComplete = false, isActive = false,
                                    error = "This region needs more tiles than the offline limit ($limit).",
                                ),
                            )
                        }
                    })
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    if (cont.isActive) cont.resume(region)
                }

                override fun onError(error: String) {
                    if (cont.isActive) cont.cancel(RuntimeException("Could not create offline region: $error"))
                }
            })
        }
    }

    /** Pauses an in-flight region download without discarding downloaded tiles. */
    fun pause(region: OfflineRegion) = region.setDownloadState(OfflineRegion.STATE_INACTIVE)

    fun resume(region: OfflineRegion) = region.setDownloadState(OfflineRegion.STATE_ACTIVE)

    suspend fun listRegions(): List<OfflineRegion> = suspendCancellableCoroutine { cont ->
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions: Array<OfflineRegion>?) {
                if (cont.isActive) cont.resume(regions?.toList() ?: emptyList())
            }

            override fun onError(error: String) {
                if (cont.isActive) cont.resume(emptyList())
            }
        })
    }

    suspend fun deleteRegion(region: OfflineRegion): Boolean = suspendCancellableCoroutine { cont ->
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { if (cont.isActive) cont.resume(true) }
            override fun onError(error: String) {
                Log.w(TAG, "delete failed: $error")
                if (cont.isActive) cont.resume(false)
            }
        })
    }

    /**
     * Imports an MBTiles file into MapLibre's offline store. This is how a user-supplied or
     * pre-packaged MBTiles download becomes usable offline without re-downloading tiles.
     */
    suspend fun mergeMbtiles(path: String): Result<Unit> = runCatching {
        suspendCancellableCoroutine { cont ->
            manager.mergeOfflineRegions(path, object : OfflineManager.MergeOfflineRegionsCallback {
                override fun onMerge(regions: Array<OfflineRegion>?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(error: String) {
                    if (cont.isActive) cont.cancel(RuntimeException("MBTiles merge failed: $error"))
                }
            })
        }
    }

    suspend fun status(region: OfflineRegion): RegionStatus = suspendCancellableCoroutine { cont ->
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) {
                if (cont.isActive) {
                    cont.resume(status?.toRegionStatus() ?: RegionStatus(0, 0, 0, false, false, "no status"))
                }
            }

            override fun onError(error: String?) {
                if (cont.isActive) cont.resume(RegionStatus(0, 0, 0, false, false, error))
            }
        })
    }

    /** Clears the ambient (browsing) tile cache - the "clear cached map data" privacy control. */
    suspend fun clearAmbientCache(): Boolean = suspendCancellableCoroutine { cont ->
        manager.clearAmbientCache(object : OfflineManager.FileSourceCallback {
            override fun onSuccess() { if (cont.isActive) cont.resume(true) }
            override fun onError(error: String) { if (cont.isActive) cont.resume(false) }
        })
    }

    /** Caps the ambient cache so map browsing cannot fill the device. */
    suspend fun setAmbientCacheSize(bytes: Long): Boolean = suspendCancellableCoroutine { cont ->
        manager.setMaximumAmbientCacheSize(bytes, object : OfflineManager.FileSourceCallback {
            override fun onSuccess() { if (cont.isActive) cont.resume(true) }
            override fun onError(error: String) { if (cont.isActive) cont.resume(false) }
        })
    }

    fun fileSource(): FileSource = FileSource.getInstance(context)

    private fun OfflineRegionStatus.toRegionStatus() = RegionStatus(
        completedResourceCount = completedResourceCount,
        requiredResourceCount = requiredResourceCount,
        completedResourceSize = completedResourceSize,
        isComplete = isComplete,
        isActive = downloadState == OfflineRegion.STATE_ACTIVE,
    )

    private companion object {
        const val TAG = "OfflineRegion"
    }
}

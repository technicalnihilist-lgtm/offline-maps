package com.offlinemaps.core.data

import com.offlinemaps.core.common.DownloadStatus
import com.offlinemaps.core.common.GeoBounds
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.MapPackageType
import com.offlinemaps.core.common.SavedPlaceLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Saved places, kept on device only. */
class SavedPlacesRepository(private val dao: SavedPlaceDao) {

    fun observe(): Flow<List<SavedPlace>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun all(): List<SavedPlace> = dao.getAll().map { it.toDomain() }

    suspend fun findLabel(label: SavedPlaceLabel): SavedPlace? = dao.findByLabel(label.id)?.toDomain()

    suspend fun save(place: SavedPlace): Long {
        // Home/Work/School are singletons: replace any previous entry with the same label.
        if (place.label != SavedPlaceLabel.CUSTOM) {
            dao.findByLabel(place.label.id)?.let { dao.delete(it) }
        }
        return dao.insert(place.toEntity())
    }

    suspend fun delete(place: SavedPlace) = dao.delete(place.toEntity())

    suspend fun deleteAll() = dao.deleteAll()
}

data class SavedPlace(
    val id: Long = 0,
    val name: String,
    val label: SavedPlaceLabel,
    val point: GeoPoint,
    val address: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

internal fun SavedPlaceEntity.toDomain() = SavedPlace(
    id = id,
    name = name,
    label = SavedPlaceLabel.entries.firstOrNull { it.id == label } ?: SavedPlaceLabel.CUSTOM,
    point = GeoPoint(latitude, longitude),
    address = address,
    createdAt = createdAt,
)

internal fun SavedPlace.toEntity() = SavedPlaceEntity(
    id = id,
    name = name,
    label = label.id,
    latitude = point.latitude,
    longitude = point.longitude,
    address = address,
    createdAt = createdAt,
)

class RecentSearchRepository(private val dao: RecentSearchDao) {
    fun observe(limit: Int = 20): Flow<List<RecentSearch>> = dao.observeRecent(limit).map { rows ->
        rows.map { RecentSearch(it.query, it.name, GeoPoint(it.latitude, it.longitude), it.usedAt) }
    }

    suspend fun record(query: String, name: String, point: GeoPoint) {
        if (query.isBlank()) return
        dao.insert(
            RecentSearchEntity(
                query = query.trim(),
                name = name,
                latitude = point.latitude,
                longitude = point.longitude,
            ),
        )
    }

    suspend fun clear() = dao.deleteAll()
}

data class RecentSearch(val query: String, val name: String, val point: GeoPoint, val usedAt: Long)

/** Download queue state, durable across process death. */
class DownloadRepository(private val dao: DownloadDao) {
    fun observeAll(): Flow<List<DownloadRecord>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    fun observe(key: String): Flow<DownloadRecord?> = dao.observe(key).map { it?.toDomain() }
    suspend fun find(key: String): DownloadRecord? = dao.find(key)?.toDomain()
    suspend fun pending(): List<DownloadRecord> = dao.pending().map { it.toDomain() }
    suspend fun upsert(record: DownloadRecord) = dao.upsert(record.toEntity())
    suspend fun updateProgress(key: String, status: DownloadStatus, bytes: Long, error: String? = null) =
        dao.updateProgress(key, status.name, bytes, error)
    suspend fun delete(key: String) = dao.delete(key)
}

data class DownloadRecord(
    val packageKey: String,
    val regionId: String,
    val name: String,
    val type: MapPackageType,
    val url: String,
    val status: DownloadStatus,
    val bytesTotal: Long,
    val bytesDownloaded: Long,
    val localPath: String?,
    val sha256: String?,
    val md5: String?,
    val errorMessage: String?,
    val updatedAt: Long,
) {
    val fraction: Float
        get() = if (bytesTotal > 0) (bytesDownloaded.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
}

internal fun DownloadEntity.toDomain() = DownloadRecord(
    packageKey = packageKey,
    regionId = regionId,
    name = name,
    type = MapPackageType.entries.firstOrNull { it.name == type } ?: MapPackageType.TILE_REGION,
    url = url,
    status = DownloadStatus.entries.firstOrNull { it.name == status } ?: DownloadStatus.QUEUED,
    bytesTotal = bytesTotal,
    bytesDownloaded = bytesDownloaded,
    localPath = localPath,
    sha256 = sha256,
    md5 = md5,
    errorMessage = errorMessage,
    updatedAt = updatedAt,
)

internal fun DownloadRecord.toEntity() = DownloadEntity(
    packageKey = packageKey,
    regionId = regionId,
    name = name,
    type = type.name,
    url = url,
    status = status.name,
    bytesTotal = bytesTotal,
    bytesDownloaded = bytesDownloaded,
    localPath = localPath,
    sha256 = sha256,
    md5 = md5,
    errorMessage = errorMessage,
    updatedAt = updatedAt,
)

/** Regions installed and usable offline. */
class InstalledRegionRepository(private val dao: InstalledRegionDao) {
    fun observe(): Flow<List<InstalledRegion>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }
    suspend fun all(): List<InstalledRegion> = dao.getAll().map { it.toDomain() }
    suspend fun find(regionId: String): InstalledRegion? = dao.find(regionId)?.toDomain()
    suspend fun count(): Int = dao.count()
    suspend fun save(region: InstalledRegion) = dao.upsert(region.toEntity())
    suspend fun delete(regionId: String) = dao.delete(regionId)
    suspend fun deleteAll() = dao.deleteAll()
    suspend fun setRoutingReady(regionId: String, ready: Boolean) = dao.setRoutingReady(regionId, ready)
    suspend fun setSearchReady(regionId: String, ready: Boolean) = dao.setSearchReady(regionId, ready)
}

data class InstalledRegion(
    val id: Long = 0,
    val regionId: String,
    val name: String,
    val type: MapPackageType,
    val sizeBytes: Long,
    val version: String,
    val installedAt: Long,
    val bounds: GeoBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val routingReady: Boolean = false,
    val searchReady: Boolean = false,
) {
    fun covers(point: GeoPoint): Boolean = bounds.contains(point)
}

internal fun InstalledRegionEntity.toDomain() = InstalledRegion(
    id = id,
    regionId = regionId,
    name = name,
    type = MapPackageType.entries.firstOrNull { it.name == type } ?: MapPackageType.TILE_REGION,
    sizeBytes = sizeBytes,
    version = version,
    installedAt = installedAt,
    bounds = GeoBounds(south, west, north, east),
    minZoom = minZoom,
    maxZoom = maxZoom,
    routingReady = routingReady,
    searchReady = searchReady,
)

internal fun InstalledRegion.toEntity() = InstalledRegionEntity(
    id = id,
    regionId = regionId,
    name = name,
    type = type.name,
    sizeBytes = sizeBytes,
    version = version,
    installedAt = installedAt,
    minZoom = minZoom,
    maxZoom = maxZoom,
    south = bounds.south,
    west = bounds.west,
    north = bounds.north,
    east = bounds.east,
    routingReady = routingReady,
    searchReady = searchReady,
)

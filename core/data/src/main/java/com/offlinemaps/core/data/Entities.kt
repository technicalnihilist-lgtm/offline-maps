package com.offlinemaps.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "saved_places", indices = [Index("label")])
data class SavedPlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "recent_searches", indices = [Index("query", unique = true)])
data class RecentSearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "used_at") val usedAt: Long = System.currentTimeMillis(),
)

/**
 * One row per downloadable package. This table is the durable source of truth for the download
 * queue, so an interrupted download resumes correctly after the process is killed.
 */
@Entity(tableName = "downloads", indices = [Index("region_id"), Index("status")])
data class DownloadEntity(
    @PrimaryKey val packageKey: String,
    @ColumnInfo(name = "region_id") val regionId: String,
    val name: String,
    val type: String,
    val url: String,
    val status: String,
    @ColumnInfo(name = "bytes_total") val bytesTotal: Long,
    @ColumnInfo(name = "bytes_downloaded") val bytesDownloaded: Long = 0,
    @ColumnInfo(name = "local_path") val localPath: String? = null,
    val sha256: String? = null,
    val md5: String? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

/** A region the user has actually installed and can use offline. */
@Entity(tableName = "installed_regions", indices = [Index("region_id", unique = true)])
data class InstalledRegionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "region_id") val regionId: String,
    val name: String,
    val type: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    val version: String,
    @ColumnInfo(name = "installed_at") val installedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "min_zoom") val minZoom: Int,
    @ColumnInfo(name = "max_zoom") val maxZoom: Int,
    @ColumnInfo(name = "south") val south: Double,
    @ColumnInfo(name = "west") val west: Double,
    @ColumnInfo(name = "north") val north: Double,
    @ColumnInfo(name = "east") val east: Double,
    @ColumnInfo(name = "routing_ready") val routingReady: Boolean = false,
    @ColumnInfo(name = "search_ready") val searchReady: Boolean = false,
)

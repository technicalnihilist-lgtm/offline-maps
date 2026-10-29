package com.offlinemaps.core.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedPlaceDao {
    @Query("SELECT * FROM saved_places ORDER BY created_at DESC")
    fun observeAll(): Flow<List<SavedPlaceEntity>>

    @Query("SELECT * FROM saved_places WHERE label = :label LIMIT 1")
    suspend fun findByLabel(label: String): SavedPlaceEntity?

    @Query("SELECT * FROM saved_places ORDER BY created_at DESC")
    suspend fun getAll(): List<SavedPlaceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(place: SavedPlaceEntity): Long

    @Upsert
    suspend fun upsert(place: SavedPlaceEntity)

    @Delete
    suspend fun delete(place: SavedPlaceEntity)

    @Query("DELETE FROM saved_places")
    suspend fun deleteAll()
}

@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM recent_searches ORDER BY used_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<RecentSearchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(search: RecentSearchEntity)

    @Query("DELETE FROM recent_searches")
    suspend fun deleteAll()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE packageKey = :key")
    suspend fun find(key: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status IN ('RUNNING','QUEUED','PAUSED') ORDER BY updated_at DESC")
    suspend fun pending(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE packageKey = :key")
    fun observe(key: String): Flow<DownloadEntity?>

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("UPDATE downloads SET status = :status, bytes_downloaded = :bytes, error_message = :error, updated_at = :now WHERE packageKey = :key")
    suspend fun updateProgress(key: String, status: String, bytes: Long, error: String?, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM downloads WHERE packageKey = :key")
    suspend fun delete(key: String)
}

@Dao
interface InstalledRegionDao {
    @Query("SELECT * FROM installed_regions ORDER BY installed_at DESC")
    fun observeAll(): Flow<List<InstalledRegionEntity>>

    @Query("SELECT * FROM installed_regions")
    suspend fun getAll(): List<InstalledRegionEntity>

    @Query("SELECT * FROM installed_regions WHERE region_id = :regionId LIMIT 1")
    suspend fun find(regionId: String): InstalledRegionEntity?

    @Query("SELECT COUNT(*) FROM installed_regions")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(region: InstalledRegionEntity)

    @Query("DELETE FROM installed_regions WHERE region_id = :regionId")
    suspend fun delete(regionId: String)

    @Query("DELETE FROM installed_regions")
    suspend fun deleteAll()

    @Query("UPDATE installed_regions SET routing_ready = :ready WHERE region_id = :regionId")
    suspend fun setRoutingReady(regionId: String, ready: Boolean)

    @Query("UPDATE installed_regions SET search_ready = :ready WHERE region_id = :regionId")
    suspend fun setSearchReady(regionId: String, ready: Boolean)
}

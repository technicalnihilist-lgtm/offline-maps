package com.offlinemaps.core.offline

import com.offlinemaps.core.common.DownloadStatus
import com.offlinemaps.core.common.MapPackageType

/**
 * Durable record of a download. An interface so the engine has no dependency on Room and can be
 * exercised in plain JVM tests with an in-memory implementation.
 */
data class DownloadEntry(
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
)

interface DownloadStore {
    suspend fun find(key: String): DownloadEntry?
    suspend fun save(entry: DownloadEntry)
    suspend fun update(key: String, status: DownloadStatus, bytes: Long, error: String?)
    suspend fun delete(key: String)
    suspend fun pending(): List<DownloadEntry>
}

/** Simple in-memory store - used by unit tests and as a fallback if Room is unavailable. */
class InMemoryDownloadStore : DownloadStore {
    private val items = mutableMapOf<String, DownloadEntry>()
    override suspend fun find(key: String): DownloadEntry? = items[key]
    override suspend fun save(entry: DownloadEntry) { items[entry.packageKey] = entry }
    override suspend fun update(key: String, status: DownloadStatus, bytes: Long, error: String?) {
        items[key]?.let { items[key] = it.copy(status = status, bytesDownloaded = bytes, errorMessage = error) }
    }
    override suspend fun delete(key: String) { items.remove(key) }
    override suspend fun pending(): List<DownloadEntry> = items.values.filter {
        it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.QUEUED
    }
}

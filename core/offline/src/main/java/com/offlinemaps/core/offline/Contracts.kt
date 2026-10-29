package com.offlinemaps.core.offline

import com.offlinemaps.core.common.MapPackageType
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Network reachability plus whether the current link is unmetered. */
data class NetworkStatus(val online: Boolean, val unmetered: Boolean) {
    companion object {
        val UNKNOWN = NetworkStatus(online = false, unmetered = false)
    }
}

interface NetworkStateProvider {
    val status: StateFlow<NetworkStatus>
}

/** Free-space probe. Kept behind an interface so tests can simulate a full disk. */
interface StorageProbe {
    fun availableBytes(directory: File): Long
}

enum class DownloadFailureKind { NETWORK, SERVER, STORAGE, CHECKSUM, CANCELLED, UNKNOWN }

class DownloadException(
    val kind: DownloadFailureKind,
    message: String,
    val retryable: Boolean = kind == DownloadFailureKind.NETWORK,
    cause: Throwable? = null,
) : Exception(message, cause)

/** What to download and where it belongs. */
data class DownloadRequest(
    val packageKey: String,
    val regionId: String,
    val displayName: String,
    val type: MapPackageType,
    val url: String,
    val expectedBytes: Long = 0L,
    val sha256: String? = null,
    val md5: String? = null,
    val fileName: String,
    val targetDirectory: File,
)

/** Live progress for one package. [bytesPerSecond] is a smoothed rate, 0 while unknown. */
data class DownloadProgress(
    val packageKey: String,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val bytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
) {
    val fraction: Float
        get() = when {
            bytesTotal > 0 -> (bytesDownloaded.toFloat() / bytesTotal).coerceIn(0f, 1f)
            else -> 0f
        }
}

package com.offlinemaps.core.offline

import com.offlinemaps.core.common.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** UI-facing state of one package download. */
data class DownloadRuntime(
    val packageKey: String,
    val status: DownloadStatus,
    val progress: DownloadProgress? = null,
    val error: String? = null,
)

/**
 * Resumable, verified HTTP download engine.
 *
 * Design notes that matter for correctness:
 *  - Bytes are written to `<name>.part`; the final file only appears after the checksum passes.
 *  - A sidecar `.meta` file records the ETag/Last-Modified and total size, so a resume after a
 *    process restart cannot silently splice two different upstream files together.
 *  - Every failure mode (offline, metered link when Wi-Fi-only is set, full disk, bad checksum,
 *    HTTP error) produces a specific, user-readable message rather than a stack trace.
 */
class DownloadManager(
    private val client: OkHttpClient,
    private val store: DownloadStore,
    private val storage: StorageProbe,
    private val network: NetworkStateProvider,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxRetries: Int = 3,
) {
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Packages the user has paused; blocks late progress writes from reviving the RUNNING state. */
    private val pausedKeys = ConcurrentHashMap.newKeySet<String>()

    private val _states = MutableStateFlow<Map<String, DownloadRuntime>>(emptyMap())
    val states: StateFlow<Map<String, DownloadRuntime>> = _states.asStateFlow()

    fun runtime(key: String): DownloadRuntime? = _states.value[key]

    /**
     * Starts (or resumes) a download. Returns immediately; observe [states] for progress.
     * A second call for the same package while it is running is a no-op.
     */
    fun start(request: DownloadRequest, wifiOnly: Boolean) {
        if (jobs[request.packageKey]?.isActive == true) return
        pausedKeys.remove(request.packageKey)
        setState(request.packageKey, DownloadStatus.QUEUED)
        val job = scope.launch(Dispatchers.IO) {
            runCatching { execute(request, wifiOnly) }
        }
        jobs[request.packageKey] = job
        job.invokeOnCompletion { cause ->
            jobs.remove(request.packageKey)
            // Pause/cancel already persisted the right status; nothing to do here.
            if (cause is CancellationException) Unit
        }
    }

    fun pause(key: String) {
        pausedKeys.add(key)
        val job = jobs.remove(key) ?: return
        job.cancel()
        scope.launch {
            val current = _states.value[key]?.progress?.bytesDownloaded
                ?: store.find(key)?.bytesDownloaded ?: 0L
            store.update(key, DownloadStatus.PAUSED, current, null)
            setState(key, DownloadStatus.PAUSED, bytes = current)
        }
    }

    fun cancel(key: String) {
        pausedKeys.add(key)
        val job = jobs.remove(key)
        job?.cancel()
        scope.launch {
            store.delete(key)
            _states.update { it - key }
        }
    }

    /** Deletes a downloaded package from disk and forgets it. */
    suspend fun deletePackage(request: DownloadRequest) {
        pausedKeys.add(request.packageKey)
        jobs.remove(request.packageKey)?.cancel()
        withContext(Dispatchers.IO) {
            finalFile(request).delete()
            partFile(request).delete()
            metaFile(request).delete()
        }
        store.delete(request.packageKey)
        _states.update { it - request.packageKey }
    }

    /** Resumes every download that was mid-flight when the process died. */
    fun resumePending(wifiOnly: Boolean, requestFactory: (DownloadEntry) -> DownloadRequest?) {
        scope.launch {
            store.pending().forEach { entry ->
                if (entry.status != DownloadStatus.COMPLETED) {
                    requestFactory(entry)?.let { start(it, wifiOnly) }
                }
            }
        }
    }

    private suspend fun execute(request: DownloadRequest, wifiOnly: Boolean) {
        val net = network.status.value
        if (!net.online) {
            fail(request, DownloadFailureKind.NETWORK, "You are offline. Connect to a network to download.")
            return
        }
        if (wifiOnly && !net.unmetered) {
            fail(request, DownloadFailureKind.NETWORK, "Wi-Fi only downloads are enabled and this is a metered connection.")
            return
        }

        request.targetDirectory.mkdirs()
        val required = max(request.expectedBytes, 0L)
        val available = storage.availableBytes(request.targetDirectory)
        val partial = if (partFile(request).exists()) partFile(request).length() else 0L
        if (!StorageGuard.canFit(available, max(required - partial, 0L))) {
            fail(
                request,
                DownloadFailureKind.STORAGE,
                StorageGuard.reasonFor(available, max(required - partial, 0L)) ?: "Not enough free storage.",
            )
            return
        }

        store.save(
            DownloadEntry(
                packageKey = request.packageKey,
                regionId = request.regionId,
                name = request.displayName,
                type = request.type,
                url = request.url,
                status = DownloadStatus.RUNNING,
                bytesTotal = request.expectedBytes,
                bytesDownloaded = partial,
                localPath = null,
                sha256 = request.sha256,
                md5 = request.md5,
                errorMessage = null,
            ),
        )

        var attempt = 0
        while (true) {
            try {
                downloadOnce(request)
                verifyAndFinish(request)
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: DownloadException) {
                if (!e.retryable || attempt >= maxRetries) {
                    fail(request, e.kind, e.message ?: "Download failed.")
                    return
                }
                attempt++
                setState(request.packageKey, DownloadStatus.QUEUED, error = e.message)
                delay(backoffMillis(attempt))
            } catch (e: IOException) {
                if (attempt >= maxRetries) {
                    fail(request, DownloadFailureKind.NETWORK, "Network error: ${e.message ?: "connection lost"}")
                    return
                }
                attempt++
                delay(backoffMillis(attempt))
            } catch (e: Exception) {
                fail(request, DownloadFailureKind.UNKNOWN, e.message ?: "Download failed.")
                return
            }
        }
    }

    private suspend fun downloadOnce(request: DownloadRequest) = withContext(Dispatchers.IO) {
        val part = partFile(request)
        val meta = readMeta(request)
        var existing = if (part.exists()) part.length() else 0L

        val builder = Request.Builder().url(request.url).header("Accept-Encoding", "identity")
        if (existing > 0) {
            builder.header("Range", "bytes=$existing-")
            meta.etag?.let { builder.header("If-Range", it) }
        }

        client.newCall(builder.build()).execute().use { response ->
            val code = response.code
            var total = request.expectedBytes
            var append = existing > 0

            when {
                code == 206 -> {
                    val contentRange = response.header("Content-Range")
                    val start = contentRange?.substringAfter("bytes ")?.substringBefore("-")?.toLongOrNull()
                    if (start != null && start != existing) {
                        // Server resumed us somewhere we did not expect - restart cleanly.
                        part.delete()
                        existing = 0
                        append = false
                        throw DownloadException(
                            DownloadFailureKind.SERVER,
                            "Server returned an unexpected resume offset; restarting the download.",
                            retryable = true,
                        )
                    }
                    total = contentRange?.substringAfter("/")?.toLongOrNull() ?: request.expectedBytes
                }
                code == 200 -> {
                    // Server ignored our Range header (or this is a fresh download).
                    if (existing > 0) {
                        part.delete()
                        existing = 0
                    }
                    append = false
                    total = response.body?.contentLength()?.takeIf { it > 0 } ?: request.expectedBytes
                }
                code == 416 -> {
                    // Already complete as far as the server is concerned.
                    val totalFromRange = response.header("Content-Range")?.substringAfter("/")?.toLongOrNull()
                    if (totalFromRange != null && existing >= totalFromRange) return@use
                    part.delete()
                    throw DownloadException(
                        DownloadFailureKind.SERVER,
                        "The server rejected the resume request; the partial file was discarded. Please retry.",
                        retryable = true,
                    )
                }
                code == 404 -> throw DownloadException(
                    DownloadFailureKind.SERVER,
                    "This map package is not available at its source (HTTP 404).",
                    retryable = false,
                )
                code == 403 || code == 401 -> throw DownloadException(
                    DownloadFailureKind.SERVER,
                    "Access to this map package was refused (HTTP $code).",
                    retryable = false,
                )
                code >= 500 -> throw DownloadException(
                    DownloadFailureKind.SERVER,
                    "The map server had a problem (HTTP $code).",
                    retryable = true,
                )
                else -> throw DownloadException(
                    DownloadFailureKind.SERVER,
                    "Unexpected server response (HTTP $code).",
                    retryable = false,
                )
            }

            writeMeta(
                request,
                DownloadMeta(
                    etag = response.header("ETag") ?: meta.etag,
                    lastModified = response.header("Last-Modified") ?: meta.lastModified,
                    totalBytes = total,
                ),
            )

            val body = response.body ?: throw DownloadException(
                DownloadFailureKind.SERVER,
                "The server returned an empty response body.",
            )

            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(if (append) existing else 0L)
                if (!append) raf.setLength(0)

                val buffer = ByteArray(128 * 1024)
                var written = if (append) existing else 0L
                var windowStart = now()
                var windowBytes = written
                var lastRate = 0L
                var lastPublish = 0L

                body.byteStream().use { input ->
                    while (isActive) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        written += read
                        val t = now()
                        if (t - windowStart >= 1000) {
                            lastRate = ((written - windowBytes) * 1000L) / (t - windowStart)
                            windowStart = t
                            windowBytes = written
                        }
                        if (t - lastPublish >= 250) {
                            lastPublish = t
                            publishProgress(request, written, total, lastRate)
                        }
                    }
                }
                publishProgress(request, written, total, lastRate)
            }
        }
    }

    private suspend fun verifyAndFinish(request: DownloadRequest) {
        val part = partFile(request)
        setState(request.packageKey, DownloadStatus.VERIFYING, bytes = part.length())
        store.update(request.packageKey, DownloadStatus.VERIFYING, part.length(), null)

        if (request.expectedBytes > 0 && part.length() != request.expectedBytes) {
            // A size mismatch means the transfer ended early; keep the partial data so a retry resumes.
            throw DownloadException(
                DownloadFailureKind.NETWORK,
                "The download stopped early (${part.length()} of ${request.expectedBytes} bytes).",
                retryable = true,
            )
        }

        val problem = withContext(Dispatchers.IO) { FileVerifier.verify(part, request.sha256, request.md5) }
        if (problem != null) {
            withContext(Dispatchers.IO) { part.delete() }
            fail(request, DownloadFailureKind.CHECKSUM, problem)
            return
        }

        val finalFile = finalFile(request)
        withContext(Dispatchers.IO) {
            if (finalFile.exists()) finalFile.delete()
            if (!part.renameTo(finalFile)) {
                // Fall back to a copy when rename fails across mount points.
                part.copyTo(finalFile, overwrite = true)
                part.delete()
            }
            metaFile(request).delete()
        }

        store.save(
            DownloadEntry(
                packageKey = request.packageKey,
                regionId = request.regionId,
                name = request.displayName,
                type = request.type,
                url = request.url,
                status = DownloadStatus.COMPLETED,
                bytesTotal = finalFile.length(),
                bytesDownloaded = finalFile.length(),
                localPath = finalFile.absolutePath,
                sha256 = request.sha256,
                md5 = request.md5,
                errorMessage = null,
            ),
        )
        _states.update {
            it + (request.packageKey to DownloadRuntime(
                packageKey = request.packageKey,
                status = DownloadStatus.COMPLETED,
                progress = DownloadProgress(request.packageKey, finalFile.length(), finalFile.length()),
            ))
        }
    }

    private suspend fun publishProgress(request: DownloadRequest, written: Long, total: Long, rate: Long) {
        if (pausedKeys.contains(request.packageKey)) return
        val eta = if (rate > 0 && total > written) (total - written) / rate else null
        _states.update {
            it + (request.packageKey to DownloadRuntime(
                packageKey = request.packageKey,
                status = DownloadStatus.RUNNING,
                progress = DownloadProgress(request.packageKey, written, total, rate, eta),
            ))
        }
        store.update(request.packageKey, DownloadStatus.RUNNING, written, null)
    }

    private suspend fun fail(request: DownloadRequest, kind: DownloadFailureKind, message: String) {
        val bytes = if (partFile(request).exists()) partFile(request).length() else 0L
        store.update(request.packageKey, DownloadStatus.FAILED, bytes, message)
        _states.update {
            it + (request.packageKey to DownloadRuntime(
                packageKey = request.packageKey,
                status = DownloadStatus.FAILED,
                progress = DownloadProgress(request.packageKey, bytes, request.expectedBytes),
                error = message,
            ))
        }
    }

    private fun setState(key: String, status: DownloadStatus, bytes: Long = 0L, error: String? = null) {
        _states.update {
            it + (key to DownloadRuntime(
                packageKey = key,
                status = status,
                progress = it[key]?.progress?.copy(bytesDownloaded = bytes),
                error = error,
            ))
        }
    }

    private fun partFile(request: DownloadRequest) = File(request.targetDirectory, request.fileName + ".part")
    private fun finalFile(request: DownloadRequest) = File(request.targetDirectory, request.fileName)
    private fun metaFile(request: DownloadRequest) = File(request.targetDirectory, request.fileName + ".meta")

    private fun readMeta(request: DownloadRequest): DownloadMeta = runCatching {
        val f = metaFile(request)
        if (!f.exists()) return@runCatching DownloadMeta()
        val parts = f.readText().split("\n")
        DownloadMeta(
            etag = parts.getOrNull(0)?.takeIf { it.isNotBlank() },
            lastModified = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
            totalBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
        )
    }.getOrDefault(DownloadMeta())

    private fun writeMeta(request: DownloadRequest, meta: DownloadMeta) {
        runCatching {
            metaFile(request).writeText("${meta.etag ?: ""}\n${meta.lastModified ?: ""}\n${meta.totalBytes}")
        }
    }

    private fun backoffMillis(attempt: Int): Long = (500L * (1 shl (attempt - 1))).coerceAtMost(8_000L)

    private data class DownloadMeta(
        val etag: String? = null,
        val lastModified: String? = null,
        val totalBytes: Long = 0L,
    )
}

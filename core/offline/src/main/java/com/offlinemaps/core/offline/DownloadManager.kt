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
import kotlin.random.Random

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
    private val random: Random = Random.Default,
) {
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * The newest job that may still be touching one package's files: a transfer, or the cleanup a
     * cancel runs after it.
     *
     * A replacement transfer joins its predecessor before it opens the `.part` file, so a job that
     * was cancelled but is still inside a chunk write - or a cancel that has not deleted the file
     * yet - cannot write to, or delete, the file the new transfer is working on. Entries are dropped
     * when their job completes, so the map only ever holds one small object per package the user
     * started in this process.
     */
    private val predecessors = ConcurrentHashMap<String, Job>()

    /**
     * Serialises the per-key bookkeeping above.
     *
     * "Is this package already running?" has to be atomic with the launch that answers it. Two
     * callers - a double tap, or a resume racing the restart after a process restore - could
     * otherwise both see no job, both launch a transfer, and two write loops would then append to
     * the same `.part` file from two different Range offsets, which corrupts the package.
     */
    private val startLock = Any()

    /** Packages the user has paused; blocks late progress writes from reviving the RUNNING state. */
    private val pausedKeys = ConcurrentHashMap.newKeySet<String>()

    /**
     * The request each package key was last started with. Cancellation has to name the files to
     * delete (`<name>.part`, `<name>.meta`) and only the request knows them, so the engine keeps the
     * last one per key. One small object per package the user started - and dropped as soon as the
     * package is installed or forgotten - so it cannot grow with the catalogue.
     */
    private val requests = ConcurrentHashMap<String, DownloadRequest>()

    private val _states = MutableStateFlow<Map<String, DownloadRuntime>>(emptyMap())
    val states: StateFlow<Map<String, DownloadRuntime>> = _states.asStateFlow()

    fun runtime(key: String): DownloadRuntime? = _states.value[key]

    /**
     * Starts (or resumes) a download. Returns immediately; observe [states] for progress.
     * A second call for the same package while it is running is a no-op.
     */
    fun start(request: DownloadRequest, wifiOnly: Boolean) {
        val key = request.packageKey
        val job = synchronized(startLock) {
            if (jobs[key]?.isActive == true) return
            val previous = predecessors[key]
            pausedKeys.remove(key)
            requests[key] = request
            setState(key, DownloadStatus.QUEUED)
            val launched = scope.launch(Dispatchers.IO) {
                // A cancelled predecessor is no longer active but may still be inside a write, and
                // a cancel cleanup may still have the files to delete. Either way it owns them
                // until it is done, so the transfer waits instead of opening them alongside it.
                previous?.join()
                runCatching { execute(request, wifiOnly) }
            }
            jobs[key] = launched
            predecessors[key] = launched
            launched
        }
        job.invokeOnCompletion {
            // Only this job's own entry may be dropped: a newer start for the same key has already
            // replaced both maps, and evicting its entry would let a third start run beside it.
            synchronized(startLock) {
                jobs.remove(key, job)
                predecessors.remove(key, job)
            }
            // Pause/cancel already persisted the right status; nothing else to do here.
        }
    }

    fun pause(key: String) {
        pausedKeys.add(key)
        val job = synchronized(startLock) { jobs.remove(key) } ?: return
        job.cancel()
        scope.launch {
            val current = _states.value[key]?.progress?.bytesDownloaded
                ?: store.find(key)?.bytesDownloaded ?: 0L
            // A start that arrived while the cancelled transfer was winding down owns the key now:
            // writing PAUSED here would replace the state and the stored row of the new transfer.
            if (!synchronized(startLock) { jobs[key] == null }) return@launch
            store.update(key, DownloadStatus.PAUSED, current, null)
            setState(key, DownloadStatus.PAUSED, bytes = current)
        }
    }

    /**
     * Cancels a download and removes every trace of it, on disk as well as in the store.
     *
     * The partial file and its sidecar are deleted only after the transfer job has finished, never
     * before: `cancel()` returns while the job's write loop can still be inside a chunk, and a
     * delete racing that write lets the engine recreate the `.part` file it is still holding open.
     * Joining the job first makes the cleanup deterministic instead of best-effort.
     *
     * A request the engine no longer remembers (the download finished or failed in an earlier
     * process) is left to [PartialFileSweeper], which removes the same files at the next start.
     */
    fun cancel(key: String) {
        pausedKeys.add(key)
        val cleanup = synchronized(startLock) {
            val job = jobs.remove(key)
            val request = requests.remove(key)
            job?.cancel()
            // The cleanup joins the transfer it cancelled and is registered as the key's newest
            // file owner in the same step, so a start that lands while the delete is still to come
            // waits for it rather than downloading into a file that is about to be removed.
            val previous = predecessors[key]
            val launched = scope.launch {
                job?.join()
                previous?.join()
                if (request != null) {
                    withContext(Dispatchers.IO) {
                        partFile(request).delete()
                        metaFile(request).delete()
                    }
                }
                store.delete(key)
                // A start that arrived during the cleanup has already published its own state;
                // removing the key here would wipe it.
                synchronized(startLock) {
                    if (jobs[key] == null) _states.update { it - key }
                }
            }
            predecessors[key] = launched
            launched
        }
        cleanup.invokeOnCompletion {
            synchronized(startLock) { predecessors.remove(key, cleanup) }
        }
    }

    /** Deletes a downloaded package from disk and forgets it. */
    suspend fun deletePackage(request: DownloadRequest) {
        pausedKeys.add(request.packageKey)
        jobs.remove(request.packageKey)?.cancel()
        requests.remove(request.packageKey)
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
        // A package whose bytes are already all on disk needs no transfer: the process can die
        // between the last chunk and the rename, and resuming that download used to cost a request
        // and, on a server that answers the range from zero, the whole package over again. The
        // bytes are verified against the published checksum here and installed in place.
        if (finishCompletedPart(request)) return

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
        spaceShortfall(request)?.let { reason ->
            fail(request, DownloadFailureKind.STORAGE, reason)
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
                bytesDownloaded = resumableBytes(request),
                localPath = null,
                sha256 = request.sha256,
                md5 = request.md5,
                errorMessage = null,
            ),
        )

        var attempt = 0
        while (true) {
            if (attempt > 0) {
                // A retry is a resume, and the disk that was big enough when the transfer started
                // may not be big enough now: the previous attempt wrote more bytes into the .part
                // file, and the device may have filled up in the meantime. Checking only once at
                // the start let a resume run until a write failed with a raw IOException; the
                // shortfall is known here, so it is reported as the storage failure it is.
                spaceShortfall(request)?.let { reason ->
                    fail(request, DownloadFailureKind.STORAGE, reason)
                    return
                }
            }
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
        var meta = readMeta(request)
        var existing = if (part.exists()) part.length() else 0L

        if (existing > 0 && ResumePolicy.decide(existing, meta, request.expectedBytes) == ResumeDecision.RESTART) {
            // The bytes on disk cannot be validated against what was recorded for them (no
            // validator, or a partial longer than the file it belongs to), so they are not spliced
            // with the upstream file: drop them and fetch the package from zero.
            part.delete()
            metaFile(request).delete()
            meta = ResumeMeta.EMPTY
            existing = 0L
        }

        val builder = Request.Builder().url(request.url).header("Accept-Encoding", "identity")
        if (existing > 0) {
            builder.header("Range", "bytes=$existing-")
            // Validate the resume instead of guessing: the server answers 200 with the whole file
            // when the validator no longer matches, which the 200 branch below turns into a clean
            // restart. A server that sent only Last-Modified is covered too, via the HTTP date.
            meta.validator?.let { builder.header("If-Range", it) }
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

            // A 206 may not repeat the validator, so the stored one stands; a 200 restarted the
            // transfer, so the bytes now on disk belong to this response only and an older
            // validator must not be inherited.
            writeMeta(
                request,
                ResumeMeta(
                    etag = response.header("ETag") ?: meta.etag.takeIf { append },
                    lastModified = response.header("Last-Modified") ?: meta.lastModified.takeIf { append },
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
                val publishGate = ProgressPublishGate()

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
                        val publish = publishGate.decide(t)
                        if (publish.publishUi) {
                            publishProgress(request, written, total, lastRate, persist = publish.persist)
                        }
                    }
                }
                // The transfer is over, so the store must end up holding the final byte count.
                publishProgress(request, written, total, lastRate, persist = true)
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

        installPart(request, part)
    }

    /**
     * Installs a package whose `.part` file is already complete, without touching the network.
     *
     * Returns true when the package was verified and installed, false when the caller should carry
     * on with the ordinary transfer (nothing on disk, an unknown expected size, no published
     * checksum to trust the bytes against, or a checksum mismatch - in which case the unusable
     * bytes are dropped so the normal path refetches them from zero instead of failing).
     */
    private suspend fun finishCompletedPart(request: DownloadRequest): Boolean {
        // Without a published checksum there is nothing to verify the leftover bytes against, so
        // the ordinary resume path (which asks the server) is the only safe route.
        if (request.sha256.isNullOrBlank() && request.md5.isNullOrBlank()) return false
        val part = partFile(request)
        if (!part.exists()) return false
        val expected = when {
            request.expectedBytes > 0L -> request.expectedBytes
            else -> readMeta(request).totalBytes
        }
        if (expected <= 0L || part.length() != expected) return false

        setState(request.packageKey, DownloadStatus.VERIFYING, bytes = part.length())
        store.update(request.packageKey, DownloadStatus.VERIFYING, part.length(), null)
        val problem = withContext(Dispatchers.IO) { FileVerifier.verify(part, request.sha256, request.md5) }
        if (problem != null) {
            withContext(Dispatchers.IO) {
                part.delete()
                metaFile(request).delete()
            }
            return false
        }
        installPart(request, part)
        return true
    }

    /** Renames a verified `.part` file into place and records the package as completed. */
    private suspend fun installPart(request: DownloadRequest, part: File) {
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
        // Nothing partial is left behind once the package is installed, so the request is no
        // longer needed to clean up after a cancel.
        requests.remove(request.packageKey)
        _states.update {
            it + (request.packageKey to DownloadRuntime(
                packageKey = request.packageKey,
                status = DownloadStatus.COMPLETED,
                progress = DownloadProgress(request.packageKey, finalFile.length(), finalFile.length()),
            ))
        }
    }

    private suspend fun publishProgress(
        request: DownloadRequest,
        written: Long,
        total: Long,
        rate: Long,
        persist: Boolean,
    ) {
        if (pausedKeys.contains(request.packageKey)) return
        val eta = if (rate > 0 && total > written) (total - written) / rate else null
        _states.update {
            it + (request.packageKey to DownloadRuntime(
                packageKey = request.packageKey,
                status = DownloadStatus.RUNNING,
                progress = DownloadProgress(request.packageKey, written, total, rate, eta),
            ))
        }
        // The UI state above is republished on every tick; the durable row is not. Nothing on the
        // resume path depends on it being current: a resume re-measures the .part file.
        if (persist) store.update(request.packageKey, DownloadStatus.RUNNING, written, null)
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

    /**
     * The user-readable reason the transfer cannot proceed for lack of space, or null when it can.
     *
     * Only the part of the partial file that a validated resume can keep counts towards the space
     * already on disk; an unvalidated partial is about to be thrown away and must be paid for again.
     */
    private fun spaceShortfall(request: DownloadRequest): String? {
        val required = max(request.expectedBytes, 0L)
        val available = storage.availableBytes(request.targetDirectory)
        val needed = max(required - resumableBytes(request), 0L)
        return if (StorageGuard.canFit(available, needed)) {
            null
        } else {
            StorageGuard.reasonFor(available, needed) ?: "Not enough free storage."
        }
    }

    private fun partFile(request: DownloadRequest) = File(request.targetDirectory, request.fileName + ".part")
    private fun finalFile(request: DownloadRequest) = File(request.targetDirectory, request.fileName)
    private fun metaFile(request: DownloadRequest) = File(request.targetDirectory, request.fileName + ".meta")

    /** Bytes of the partial file a validated resume may keep; zero when it has to be refetched. */
    private fun resumableBytes(request: DownloadRequest): Long {
        val part = partFile(request)
        if (!part.exists()) return 0L
        val length = part.length()
        return when (ResumePolicy.decide(length, readMeta(request), request.expectedBytes)) {
            ResumeDecision.RESUME -> length
            ResumeDecision.RESTART -> 0L
        }
    }

    private fun readMeta(request: DownloadRequest): ResumeMeta = runCatching {
        val f = metaFile(request)
        if (!f.exists()) return@runCatching ResumeMeta.EMPTY
        ResumeMeta.parse(f.readText())
    }.getOrDefault(ResumeMeta.EMPTY)

    private fun writeMeta(request: DownloadRequest, meta: ResumeMeta) {
        runCatching {
            val target = metaFile(request)
            val text = ResumeMeta.format(meta)
            val temp = File(target.absoluteFile.parentFile, target.name + ".tmp")
            // Rename into place so a process killed mid-write cannot leave a half-written
            // validator behind, which the next resume would have to treat as "no validator".
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                target.writeText(text)
                temp.delete()
            }
        }
    }

    /**
     * Exponential ceiling for a retry, unchanged from the original schedule: 500 ms, 1 s, 2 s,
     * 4 s, then flat at 8 s. Only the ceiling is computed here; the delay actually slept is jittered.
     */
    internal fun backoffCeilingMillis(attempt: Int): Long {
        // Guard the shift so a large attempt count cannot overflow into a negative delay.
        val shift = (attempt - 1).coerceIn(0, 16)
        return (BASE_BACKOFF_MILLIS * (1L shl shift)).coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    /**
     * "Equal jitter": half of the ceiling is a fixed floor and the other half is drawn at random,
     * so the delay lands in [ceiling/2, ceiling].
     *
     * Without the random half every device that lost the same link retries on the same beat and
     * hits the server in a synchronised wave, which keeps the link saturated after an outage.
     * Spreading the retries over the second half of the window decorrelates them while still
     * backing off at least as far as the old fixed schedule's midpoint.
     */
    internal fun backoffMillis(attempt: Int): Long {
        val ceiling = backoffCeilingMillis(attempt)
        val floor = ceiling / 2
        return floor + random.nextLong(ceiling - floor + 1)
    }

    private companion object {
        const val BASE_BACKOFF_MILLIS = 500L
        const val MAX_BACKOFF_MILLIS = 8_000L
    }
}

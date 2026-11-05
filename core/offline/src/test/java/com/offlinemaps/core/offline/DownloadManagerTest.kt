package com.offlinemaps.core.offline

import com.offlinemaps.core.common.DownloadStatus
import com.offlinemaps.core.common.MapPackageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises the real download engine against a local HTTP server, so resume, verification and the
 * failure paths are all covered without touching the network.
 */
class DownloadManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var store: InMemoryDownloadStore
    private lateinit var targetDir: File
    private lateinit var network: FakeNetwork
    private lateinit var storage: FakeStorage

    private class FakeNetwork(online: Boolean = true, unmetered: Boolean = true) : NetworkStateProvider {
        private val state = MutableStateFlow(NetworkStatus(online, unmetered))
        override val status: StateFlow<NetworkStatus> = state
        fun update(online: Boolean, unmetered: Boolean) { state.value = NetworkStatus(online, unmetered) }
    }

    private class FakeStorage(var available: Long = 10L * 1024 * 1024 * 1024) : StorageProbe {
        override fun availableBytes(directory: File) = available
    }

    /** Counts the durable writes the engine makes, so the throttling can be asserted. */
    private class CountingStore(private val delegate: DownloadStore) : DownloadStore {
        val updates = java.util.concurrent.atomic.AtomicInteger(0)

        override suspend fun find(key: String): DownloadEntry? = delegate.find(key)
        override suspend fun save(entry: DownloadEntry) = delegate.save(entry)
        override suspend fun update(key: String, status: DownloadStatus, bytes: Long, error: String?) {
            updates.incrementAndGet()
            delegate.update(key, status, bytes, error)
        }
        override suspend fun delete(key: String) = delegate.delete(key)
        override suspend fun pending(): List<DownloadEntry> = delegate.pending()
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = InMemoryDownloadStore()
        network = FakeNetwork()
        storage = FakeStorage()
        targetDir = temp.newFolder("packages")
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun manager() = DownloadManager(
        client = OkHttpClient(),
        store = store,
        storage = storage,
        network = network,
        scope = scope,
        maxRetries = 1,
    )

    private fun managerWithSeed(seed: Int) = DownloadManager(
        client = OkHttpClient(),
        store = store,
        storage = storage,
        network = network,
        scope = scope,
        maxRetries = 1,
        random = kotlin.random.Random(seed),
    )

    private fun request(sha256: String? = null, expectedBytes: Long = 0) = DownloadRequest(
        packageKey = "test-pkg",
        regionId = "test-region",
        displayName = "Test package",
        type = MapPackageType.ROUTING_OSM,
        url = server.url("/file.pbf").toString(),
        expectedBytes = expectedBytes,
        sha256 = sha256,
        md5 = null,
        fileName = "file.pbf",
        targetDirectory = targetDir,
    )

    /** Writes the `<name>.meta` sidecar the way a previous run of the engine would have. */
    private fun writeMeta(etag: String?, lastModified: String?, totalBytes: Long) {
        File(targetDir, "file.pbf.meta").writeText("${etag ?: ""}\n${lastModified ?: ""}\n$totalBytes")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun awaitTerminal(manager: DownloadManager, key: String = "test-pkg"): DownloadRuntime =
        withTimeout(15_000) {
            while (true) {
                val runtime = manager.states.value[key]
                if (runtime != null && (runtime.status == DownloadStatus.COMPLETED ||
                        runtime.status == DownloadStatus.FAILED || runtime.status == DownloadStatus.PAUSED)
                ) {
                    return@withTimeout runtime
                }
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    @Test
    fun `downloads a file and verifies its checksum`() = runBlocking {
        val body = "the quick brown fox".toByteArray()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(body)).setResponseCode(200))
        val manager = manager()
        manager.start(request(sha256 = sha256(body)), wifiOnly = false)
        val runtime = awaitTerminal(manager)

        assertEquals(DownloadStatus.COMPLETED, runtime.status)
        val file = File(targetDir, "file.pbf")
        assertTrue(file.exists())
        assertEquals(body.size.toLong(), file.length())
        assertEquals(DownloadStatus.COMPLETED, store.find("test-pkg")?.status)
        assertNotNull(store.find("test-pkg")?.localPath)
    }

    @Test
    fun `rejects a file whose checksum does not match`() = runBlocking {
        val body = "corrupted payload".toByteArray()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(body)))
        val manager = manager()
        manager.start(request(sha256 = "0".repeat(64)), wifiOnly = false)
        val runtime = awaitTerminal(manager)

        assertEquals(DownloadStatus.FAILED, runtime.status)
        assertTrue(runtime.error?.contains("Checksum") == true)
        assertTrue(!File(targetDir, "file.pbf").exists())
    }

    @Test
    fun `resumes a partial download using a range request`() = runBlocking {
        val full = ByteArray(4096) { (it % 251).toByte() }
        val alreadyHave = 1024
        File(targetDir, "file.pbf.part").writeBytes(full.copyOfRange(0, alreadyHave))
        writeMeta(etag = "\"v1\"", lastModified = null, totalBytes = full.size.toLong())
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $alreadyHave-${full.size - 1}/${full.size}")
                .setBody(okio.Buffer().write(full.copyOfRange(alreadyHave, full.size))),
        )
        val manager = manager()
        manager.start(request(sha256 = sha256(full), expectedBytes = full.size.toLong()), wifiOnly = false)
        val runtime = awaitTerminal(manager)

        assertEquals(DownloadStatus.COMPLETED, runtime.status)
        val sent = server.takeRequest()
        assertEquals("bytes=$alreadyHave-", sent.getHeader("Range"))
        assertEquals("the recorded ETag must validate the resume", "\"v1\"", sent.getHeader("If-Range"))
        assertTrue(File(targetDir, "file.pbf").readBytes().contentEquals(full))
        assertTrue("the sidecar is consumed on success", !File(targetDir, "file.pbf.meta").exists())
    }

    @Test
    fun `resumes with an if range date when only last modified was recorded`() = runBlocking {
        val full = ByteArray(4096) { (it % 251).toByte() }
        val alreadyHave = 2048
        val date = "Wed, 21 Oct 2015 07:28:00 GMT"
        File(targetDir, "file.pbf.part").writeBytes(full.copyOfRange(0, alreadyHave))
        writeMeta(etag = null, lastModified = date, totalBytes = full.size.toLong())
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $alreadyHave-${full.size - 1}/${full.size}")
                .setBody(okio.Buffer().write(full.copyOfRange(alreadyHave, full.size))),
        )
        val manager = manager()
        manager.start(request(sha256 = sha256(full), expectedBytes = full.size.toLong()), wifiOnly = false)

        assertEquals(DownloadStatus.COMPLETED, awaitTerminal(manager).status)
        val sent = server.takeRequest()
        assertEquals("bytes=$alreadyHave-", sent.getHeader("Range"))
        assertEquals("a server without an ETag must still validate the resume", date, sent.getHeader("If-Range"))
        assertTrue(File(targetDir, "file.pbf").readBytes().contentEquals(full))
    }

    @Test
    fun `a partial with no recorded validator is refetched instead of spliced`() = runBlocking {
        val full = ByteArray(2048) { (it % 89).toByte() }
        // Left behind by something that wrote bytes but no sidecar: nothing to validate against.
        File(targetDir, "file.pbf.part").writeBytes(ByteArray(1024) { 0 })
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(full)))
        val manager = manager()
        manager.start(request(sha256 = sha256(full), expectedBytes = full.size.toLong()), wifiOnly = false)

        assertEquals(DownloadStatus.COMPLETED, awaitTerminal(manager).status)
        val sent = server.takeRequest()
        assertEquals("an unvalidated partial must not be resumed", null, sent.getHeader("Range"))
        assertEquals(null, sent.getHeader("If-Range"))
        assertTrue(File(targetDir, "file.pbf").readBytes().contentEquals(full))
    }

    @Test
    fun `a changed upstream file restarts cleanly when the validator no longer matches`() = runBlocking {
        val full = ByteArray(2048) { (it % 53).toByte() }
        File(targetDir, "file.pbf.part").writeBytes(ByteArray(512) { 9 })
        writeMeta(etag = "\"old\"", lastModified = null, totalBytes = full.size.toLong())
        // If-Range did not match upstream, so the server answers with the whole new file.
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(full)))
        val manager = manager()
        manager.start(request(sha256 = sha256(full), expectedBytes = full.size.toLong()), wifiOnly = false)

        assertEquals(DownloadStatus.COMPLETED, awaitTerminal(manager).status)
        val sent = server.takeRequest()
        assertEquals("bytes=512-", sent.getHeader("Range"))
        assertEquals("\"old\"", sent.getHeader("If-Range"))
        assertTrue(File(targetDir, "file.pbf").readBytes().contentEquals(full))
    }

    @Test
    fun `a partial longer than the file it belongs to is discarded`() = runBlocking {
        val full = ByteArray(1024) { (it % 31).toByte() }
        File(targetDir, "file.pbf.part").writeBytes(ByteArray(4096) { 1 })
        writeMeta(etag = "\"v1\"", lastModified = null, totalBytes = 2048L)
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(full)))
        val manager = manager()
        manager.start(request(sha256 = sha256(full), expectedBytes = full.size.toLong()), wifiOnly = false)

        assertEquals(DownloadStatus.COMPLETED, awaitTerminal(manager).status)
        assertEquals("an over-long partial must not be resumed", null, server.takeRequest().getHeader("Range"))
        assertTrue(File(targetDir, "file.pbf").readBytes().contentEquals(full))
    }

    @Test
    fun `the validator is written next to the part file before the body arrives`() = runBlocking {
        val body = ByteArray(64 * 1024) { 5 }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"abc123\"")
                .setHeader("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT")
                .setBody(okio.Buffer().write(body))
                .throttleBody(2048, 50, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        val manager = manager()
        manager.start(request(expectedBytes = body.size.toLong()), wifiOnly = false)
        delay(300)

        val metaFile = File(targetDir, "file.pbf.meta")
        assertTrue("the sidecar must exist while the transfer runs", metaFile.exists())
        val stored = ResumeMeta.parse(metaFile.readText())
        assertEquals("\"abc123\"", stored.etag)
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", stored.lastModified)
        assertEquals(body.size.toLong(), stored.totalBytes)
        assertEquals("\"abc123\"", stored.validator)

        manager.pause("test-pkg")
        delay(300)
    }

    @Test
    fun `restarts when the server ignores the range header`() = runBlocking {
        val full = ByteArray(2048) { (it % 97).toByte() }
        File(targetDir, "file.pbf.part").writeBytes(full.copyOfRange(0, 512))
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(full)))
        val manager = manager()
        manager.start(request(sha256 = sha256(full), expectedBytes = full.size.toLong()), wifiOnly = false)
        val runtime = awaitTerminal(manager)

        assertEquals(DownloadStatus.COMPLETED, runtime.status)
        assertTrue(File(targetDir, "file.pbf").readBytes().contentEquals(full))
    }

    @Test
    fun `a part file that is already complete is installed without any network request`() = runBlocking {
        val full = ByteArray(4096) { (it % 241).toByte() }
        // The state a process killed between the last chunk and the rename leaves behind.
        File(targetDir, "file.pbf.part").writeBytes(full)
        writeMeta(etag = "\"v1\"", lastModified = null, totalBytes = full.size.toLong())
        // No response is enqueued and the device is offline: neither may matter.
        network.update(online = false, unmetered = false)
        val manager = manager()
        manager.start(request(sha256 = sha256(full), expectedBytes = full.size.toLong()), wifiOnly = true)

        val runtime = awaitTerminal(manager)

        assertEquals(DownloadStatus.COMPLETED, runtime.status)
        assertEquals("a complete part must not be re-downloaded", 0, server.requestCount)
        assertTrue(File(targetDir, "file.pbf").readBytes().contentEquals(full))
        assertFalse("the part file is renamed away", File(targetDir, "file.pbf.part").exists())
        assertFalse("the sidecar is consumed on success", File(targetDir, "file.pbf.meta").exists())
        assertEquals(DownloadStatus.COMPLETED, store.find("test-pkg")?.status)
        assertNotNull(store.find("test-pkg")?.localPath)
    }

    @Test
    fun `a complete part file with the wrong checksum is refetched from zero`() = runBlocking {
        val full = ByteArray(2048) { (it % 71).toByte() }
        // Right size, wrong bytes: the length check alone would have accepted it.
        File(targetDir, "file.pbf.part").writeBytes(ByteArray(full.size) { 7 })
        writeMeta(etag = "\"v1\"", lastModified = null, totalBytes = full.size.toLong())
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(full)))
        val manager = manager()
        manager.start(request(sha256 = sha256(full), expectedBytes = full.size.toLong()), wifiOnly = false)

        assertEquals(DownloadStatus.COMPLETED, awaitTerminal(manager).status)
        val sent = server.takeRequest()
        assertEquals("the unusable bytes are dropped, not resumed", null, sent.getHeader("Range"))
        assertTrue(File(targetDir, "file.pbf").readBytes().contentEquals(full))
    }

    @Test
    fun `a complete part file without a published checksum still asks the server`() = runBlocking {
        val full = ByteArray(1024) { (it % 37).toByte() }
        File(targetDir, "file.pbf.part").writeBytes(full)
        writeMeta(etag = "\"v1\"", lastModified = null, totalBytes = full.size.toLong())
        server.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */${full.size}"),
        )
        val manager = manager()
        manager.start(request(expectedBytes = full.size.toLong()), wifiOnly = false)

        assertEquals(DownloadStatus.COMPLETED, awaitTerminal(manager).status)
        assertEquals("bytes=${full.size}-", server.takeRequest().getHeader("Range"))
        assertTrue(File(targetDir, "file.pbf").readBytes().contentEquals(full))
    }

    @Test
    fun `refuses to download on a metered connection when wifi only is set`() = runBlocking {
        network.update(online = true, unmetered = false)
        val manager = manager()
        manager.start(request(), wifiOnly = true)
        val runtime = awaitTerminal(manager)

        assertEquals(DownloadStatus.FAILED, runtime.status)
        assertTrue(runtime.error?.contains("Wi-Fi only") == true)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refuses to download when offline`() = runBlocking {
        network.update(online = false, unmetered = false)
        val manager = manager()
        manager.start(request(), wifiOnly = false)
        val runtime = awaitTerminal(manager)
        assertEquals(DownloadStatus.FAILED, runtime.status)
        assertTrue(runtime.error?.contains("offline") == true)
    }

    @Test
    fun `refuses to start when there is not enough free space`() = runBlocking {
        storage.available = 1024L
        server.enqueue(MockResponse().setBody("x".repeat(100)))
        val manager = manager()
        manager.start(request(expectedBytes = 500L * 1024 * 1024), wifiOnly = false)
        val runtime = awaitTerminal(manager)
        assertEquals(DownloadStatus.FAILED, runtime.status)
        assertTrue(runtime.error?.contains("free space") == true)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `re-checks free space before resuming a retry`() = runBlocking {
        // OPTIMIZATION.md C-05: the free-space check at start must not be the only one. A device
        // that filled up while the transfer was failing has to be told so before the resume starts,
        // rather than failing later on a raw write error.
        val full = ByteArray(4096) { (it % 251).toByte() }
        val alreadyHave = 1024
        File(targetDir, "file.pbf.part").writeBytes(full.copyOfRange(0, alreadyHave))
        writeMeta(etag = "\"v1\"", lastModified = null, totalBytes = full.size.toLong())
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $alreadyHave-${full.size - 1}/${full.size}")
                .setBody(okio.Buffer().write(full.copyOfRange(alreadyHave, full.size))),
        )
        // Plenty of space for the first check, almost nothing afterwards.
        val probes = AtomicInteger(0)
        val shrinking = object : StorageProbe {
            override fun availableBytes(directory: File): Long =
                if (probes.incrementAndGet() == 1) 10L * 1024 * 1024 * 1024 else 1024L
        }
        val manager = DownloadManager(
            client = OkHttpClient(),
            store = store,
            storage = shrinking,
            network = network,
            scope = scope,
            maxRetries = 2,
        )

        manager.start(request(sha256 = sha256(full), expectedBytes = full.size.toLong()), wifiOnly = false)
        val runtime = awaitTerminal(manager)

        assertEquals(DownloadStatus.FAILED, runtime.status)
        assertTrue(
            "expected a storage message, saw ${runtime.error}",
            runtime.error?.contains("free space") == true,
        )
        assertEquals("the resume must not be attempted once the space is gone", 1, server.requestCount)
        assertTrue("the partial file is kept for a later resume", File(targetDir, "file.pbf.part").exists())
        assertEquals(DownloadStatus.FAILED, store.find("test-pkg")?.status)
    }

    @Test
    fun `reports a clear error for a missing package`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val manager = manager()
        manager.start(request(), wifiOnly = false)
        val runtime = awaitTerminal(manager)
        assertEquals(DownloadStatus.FAILED, runtime.status)
        assertTrue(runtime.error?.contains("404") == true)
    }

    @Test
    fun `pausing keeps the partial file for a later resume`() = runBlocking {
        val body = ByteArray(64 * 1024) { 7 }
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(body))
                .throttleBody(2048, 50, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        val manager = manager()
        manager.start(request(), wifiOnly = false)
        delay(300)
        manager.pause("test-pkg")
        delay(400)
        val part = File(targetDir, "file.pbf.part")
        assertTrue("partial file should survive a pause", part.exists() || File(targetDir, "file.pbf").exists())
        assertTrue(store.find("test-pkg")?.status in setOf(DownloadStatus.PAUSED, DownloadStatus.COMPLETED))
    }

    @Test
    fun `cancelling removes the record`() = runBlocking {
        val body = ByteArray(64 * 1024) { 3 }
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(body))
                .throttleBody(2048, 50, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        val manager = manager()
        manager.start(request(), wifiOnly = false)
        delay(300)
        manager.cancel("test-pkg")
        delay(400)
        assertEquals(null, store.find("test-pkg"))
    }

    @Test
    fun `cancelling deletes the partial file as well as the record`() = runBlocking {
        // OPTIMIZATION.md C-06: the bytes of a cancelled download must not survive on disk.
        val body = ByteArray(64 * 1024) { 5 }
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(body))
                .throttleBody(2048, 50, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        val manager = manager()
        manager.start(request(), wifiOnly = false)
        delay(300)
        assertTrue("the transfer should have started", File(targetDir, "file.pbf.part").exists())

        manager.cancel("test-pkg")

        withTimeout(15_000) {
            while (store.find("test-pkg") != null) delay(25)
        }
        assertFalse("the partial file must be gone", File(targetDir, "file.pbf.part").exists())
        assertFalse("the sidecar must be gone", File(targetDir, "file.pbf.meta").exists())
        assertFalse("nothing may be installed", File(targetDir, "file.pbf").exists())
        assertNull(manager.states.value["test-pkg"])
    }

    @Test
    fun `cancelling a paused download removes the partial the pause kept`() = runBlocking {
        // A pause deliberately keeps the bytes and stops the job, so a cancel afterwards has no
        // running transfer to wait for: it still has to delete what the pause left on disk.
        val body = ByteArray(64 * 1024) { 9 }
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(body))
                .throttleBody(2048, 50, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        val manager = manager()
        manager.start(request(), wifiOnly = false)
        delay(300)
        manager.pause("test-pkg")
        delay(400)
        val part = File(targetDir, "file.pbf.part")
        assertTrue("a pause must keep the partial", part.exists())

        manager.cancel("test-pkg")

        withTimeout(15_000) {
            while (store.find("test-pkg") != null) delay(25)
        }
        assertFalse("the partial file must be gone", part.exists())
        assertFalse("the sidecar must be gone", File(targetDir, "file.pbf.meta").exists())
    }

    @Test
    fun `progress reaches the store far less often than it reaches the ui`() = runBlocking {
        val body = ByteArray(1024 * 1024) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(body)))
        val counting = CountingStore(store)
        // A clock that advances one second per read, so the cadence is exercised deterministically
        // instead of by sleeping: every chunk is a UI publish, only every other one may persist.
        val clockReads = java.util.concurrent.atomic.AtomicInteger(0)
        val manager = DownloadManager(
            client = OkHttpClient(),
            store = counting,
            storage = storage,
            network = network,
            scope = scope,
            now = { clockReads.incrementAndGet() * 1_000L },
            maxRetries = 1,
        )
        manager.start(request(sha256 = sha256(body), expectedBytes = body.size.toLong()), wifiOnly = false)

        assertEquals(DownloadStatus.COMPLETED, awaitTerminal(manager).status)

        val chunks = clockReads.get() - 1 // one read for the rate window, then one per chunk
        assertTrue("expected several chunks, saw $chunks", chunks >= 4)
        val progressWrites = counting.updates.get() - 1 // minus the VERIFYING write
        // The gate persists on the first chunk and then once per two seconds, i.e. every other
        // chunk, so the ceiling is ceil(chunks / 2) gate writes plus the one forced write at the
        // end of the transfer. An odd chunk count used to push the count one past a floor-based
        // bound and fail the run for no reason.
        val ceiling = (chunks + 1) / 2 + 1
        assertTrue(
            "progress writes to the store ($progressWrites) should be well under UI publishes ($chunks)",
            progressWrites <= ceiling,
        )
        assertTrue("the throttling must actually skip writes", progressWrites < chunks)
        assertEquals(DownloadStatus.COMPLETED, store.find("test-pkg")?.status)
        assertEquals(body.size.toLong(), File(targetDir, "file.pbf").length())
    }

    @Test
    fun `every transfer goes through the client it was handed, not one of its own`() = runBlocking {
        // OPTIMIZATION.md C-04: the engine must reuse the app's client (and so its connection pool
        // and dispatcher). An EventListener attached to the injected client only sees a call if the
        // engine really used that instance - a per-request client would leave it at zero.
        val body = "shared client body".toByteArray()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(body)))
        val callStarts = AtomicInteger(0)
        val observed = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    callStarts.incrementAndGet()
                }
            })
            .build()
        val manager = DownloadManager(
            client = observed,
            store = store,
            storage = storage,
            network = network,
            scope = scope,
            maxRetries = 1,
        )

        manager.start(request(sha256 = sha256(body), expectedBytes = body.size.toLong()), wifiOnly = false)

        assertEquals(DownloadStatus.COMPLETED, awaitTerminal(manager).status)
        assertEquals("exactly one request for one completed download", 1, callStarts.get())
        assertEquals(1, server.requestCount)
        assertEquals(body.size.toLong(), File(targetDir, "file.pbf").length())
    }

    @Test
    fun `retry backoff stays inside its jitter window and is decorrelated`() {
        val a = managerWithSeed(1)
        val b = managerWithSeed(2)
        for (attempt in 1..6) {
            val ceiling = a.backoffCeilingMillis(attempt)
            val floor = ceiling / 2
            val samples = (1..64).map { a.backoffMillis(attempt) }
            assertTrue(
                "attempt $attempt: every delay must be within [$floor, $ceiling], saw ${samples.min()}..${samples.max()}",
                samples.all { it in floor..ceiling },
            )
            assertTrue(
                "attempt $attempt: the jitter must actually spread the delays, saw only ${samples.toSet()}",
                samples.toSet().size > 1,
            )
            assertTrue(
                "attempt $attempt: the backoff must not collapse to a zero delay",
                samples.min() > 0,
            )
        }
        // Two engines started at the same moment must not retry on the same beat.
        val firstA = (1..8).map { a.backoffMillis(4) }
        val firstB = (1..8).map { b.backoffMillis(4) }
        assertTrue("two seeds must not produce an identical retry schedule", firstA != firstB)
    }

    @Test
    fun `retry backoff ceiling keeps the original exponential schedule and cap`() {
        val manager = managerWithSeed(3)
        assertEquals(500L, manager.backoffCeilingMillis(1))
        assertEquals(1_000L, manager.backoffCeilingMillis(2))
        assertEquals(2_000L, manager.backoffCeilingMillis(3))
        assertEquals(4_000L, manager.backoffCeilingMillis(4))
        assertEquals(8_000L, manager.backoffCeilingMillis(5))
        assertEquals(8_000L, manager.backoffCeilingMillis(20))
        assertTrue("the jitter must never push the delay past the cap", manager.backoffMillis(20) <= 8_000L)
    }

    @Test
    fun `connection drop surfaces a failure after the retry budget`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val manager = manager()
        manager.start(request(), wifiOnly = false)
        assertEquals(DownloadStatus.FAILED, awaitTerminal(manager).status)
    }
}

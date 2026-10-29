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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

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
        assertEquals("bytes=$alreadyHave-", server.takeRequest().getHeader("Range"))
        assertTrue(File(targetDir, "file.pbf").readBytes().contentEquals(full))
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
    fun `connection drop surfaces a failure after the retry budget`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val manager = manager()
        manager.start(request(), wifiOnly = false)
        assertEquals(DownloadStatus.FAILED, awaitTerminal(manager).status)
    }
}

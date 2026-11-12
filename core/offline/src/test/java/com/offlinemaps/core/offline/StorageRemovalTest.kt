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
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * OPTIMIZATION.md I-06: what happens when the storage a package is being written to is removed while
 * the download is running.
 *
 * Before this, a card pulled mid-transfer threw an `IOException` from the write or the open, which
 * the engine retried like a dropped connection and finally reported as
 * "Network error: /.../file.pbf.part: open failed: ENOENT" - the wrong cause, a leaked filesystem
 * path, and a retry budget spent on something no retry can fix. These cases pin the behaviour that
 * replaced it: a storage sentence, reported once, with no retry.
 */
class StorageRemovalTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var store: InMemoryDownloadStore
    private lateinit var targetDir: File

    private class AlwaysOnline : NetworkStateProvider {
        private val state = MutableStateFlow(NetworkStatus(online = true, unmetered = true))
        override val status: StateFlow<NetworkStatus> = state
    }

    private class PlentyOfSpace : StorageProbe {
        override fun availableBytes(directory: File) = 10L * 1024 * 1024 * 1024
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = InMemoryDownloadStore()
        targetDir = temp.newFolder("packages")
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    /**
     * The engine with three retries available, so "reported once" is a claim about behaviour rather
     * than about the retry budget being 1.
     */
    private fun manager(client: OkHttpClient = OkHttpClient()) = DownloadManager(
        client = client,
        store = store,
        storage = PlentyOfSpace(),
        network = AlwaysOnline(),
        scope = scope,
        maxRetries = 3,
    )

    private fun request(directory: File = targetDir, expectedBytes: Long = 0) = DownloadRequest(
        packageKey = "test-pkg",
        regionId = "test-region",
        displayName = "Test package",
        type = MapPackageType.ROUTING_OSM,
        url = server.url("/file.pbf").toString(),
        expectedBytes = expectedBytes,
        sha256 = null,
        md5 = null,
        fileName = "file.pbf",
        targetDirectory = directory,
    )

    private suspend fun awaitTerminal(manager: DownloadManager): DownloadRuntime =
        withTimeout(15_000) {
            while (true) {
                val runtime = manager.states.value["test-pkg"]
                if (runtime != null && (runtime.status == DownloadStatus.COMPLETED ||
                        runtime.status == DownloadStatus.FAILED ||
                        runtime.status == DownloadStatus.PAUSED)
                ) {
                    return@withTimeout runtime
                }
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    @Test
    fun `a folder that cannot be created is reported as storage before any request`() = runBlocking {
        // Nothing to write into at all: the path the app was given is a plain file.
        val blocker = temp.newFile("not-a-folder")
        server.enqueue(MockResponse().setBody(Buffer().write("payload".toByteArray())))
        val manager = manager()
        manager.start(request(directory = blocker), wifiOnly = false)

        val runtime = awaitTerminal(manager)
        assertEquals(DownloadStatus.FAILED, runtime.status)
        assertEquals(TargetStorage.UNAVAILABLE_SENTENCE, runtime.error)
        assertEquals("no bytes should be requested for an unusable folder", 0, server.requestCount)
        // Like every other failure raised before the transfer starts (offline, metered link, no
        // space), nothing was written to the store yet, so there is no row to assert on: the
        // reason reaches the user through the published state above.
        assertNull(store.find("test-pkg"))
    }

    @Test
    fun `storage removed after the request starts fails once, as storage, without a retry`() = runBlocking {
        // The card is pulled once the response headers are in, so the engine's first open of the
        // partial file cannot succeed. This used to be retried three times and then reported as a
        // network error carrying the filesystem path.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            targetDir.deleteRecursively()
            response
        }.build()
        server.enqueue(MockResponse().setBody(Buffer().write("payload".toByteArray())))
        val manager = manager(client)
        manager.start(request(), wifiOnly = false)

        val runtime = awaitTerminal(manager)
        assertEquals(DownloadStatus.FAILED, runtime.status)
        assertEquals(TargetStorage.UNAVAILABLE_SENTENCE, runtime.error)
        assertEquals("a removed card must not be retried", 1, server.requestCount)
    }

    @Test
    fun `storage removed while the body is streaming is reported as storage, not as a short transfer`() = runBlocking {
        // A handle on an unlinked file keeps accepting writes, so the transfer itself completes and
        // the loss only shows at verification. It used to surface as "The download stopped early
        // (0 of N bytes)" - a network sentence for a storage fault - and was retried.
        val body = ByteArray(64 * 1024) { (it % 251).toByte() }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val original = response.body!!
            val deleting = object : ResponseBody() {
                private var removed = false
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun source(): BufferedSource = object : ForwardingSource(original.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (!removed) {
                            removed = true
                            targetDir.deleteRecursively()
                        }
                        return super.read(sink, byteCount)
                    }
                }.buffer()
            }
            response.newBuilder().body(deleting).build()
        }.build()
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val manager = manager(client)
        manager.start(request(expectedBytes = body.size.toLong()), wifiOnly = false)

        val runtime = awaitTerminal(manager)
        assertEquals(DownloadStatus.FAILED, runtime.status)
        assertEquals(TargetStorage.UNAVAILABLE_SENTENCE, runtime.error)
        assertTrue(
            "a storage fault must not be described as a short transfer: ${runtime.error}",
            runtime.error?.contains("stopped early") == false,
        )
        assertEquals("the transfer must not be restarted onto missing storage", 1, server.requestCount)
    }

    @Test
    fun `a network failure on intact storage still says network`() = runBlocking {
        // The guard must not swallow the ordinary case: a connection that dies while the folder is
        // still there keeps its network sentence.
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        val manager = manager()
        manager.start(request(), wifiOnly = false)

        val runtime = awaitTerminal(manager)
        assertEquals(DownloadStatus.FAILED, runtime.status)
        assertTrue("expected a network reason, saw ${runtime.error}", runtime.error?.contains("Network error") == true)
        assertTrue(TargetStorage.isUsable(targetDir))
    }
}

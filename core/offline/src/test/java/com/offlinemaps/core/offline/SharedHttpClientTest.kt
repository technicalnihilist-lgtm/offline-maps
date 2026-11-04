package com.offlinemaps.core.offline

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Pins OPTIMIZATION.md C-04: one client for the whole app, with the connection pool and dispatcher
 * that go with it, and no per-request client anywhere.
 *
 * The connection-reuse case is the one that matters in practice - a size probe followed by the
 * download of the same package must not handshake twice - so it is measured against a bare
 * `ServerSocket` that counts how many times it was connected to, rather than assumed.
 */
class SharedHttpClientTest {

    @Test
    fun `the holder hands out one client however often it is asked`() {
        val first = SharedHttpClient.client
        repeat(100) { assertSame(first, SharedHttpClient.client) }
        // The counter is process-wide: other tests in this JVM may have touched the holder first,
        // so what is asserted is that asking again built nothing new.
        val builds = SharedHttpClient.buildCount
        repeat(50) { SharedHttpClient.client }
        assertEquals(builds, SharedHttpClient.buildCount)
        assertEquals(1, SharedHttpClient.buildCount)
    }

    @Test
    fun `concurrent first use still builds a single client`() {
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val ready = CountDownLatch(threads)
            val go = CountDownLatch(1)
            val seen = (1..threads).map {
                pool.submit(
                    Callable {
                        ready.countDown()
                        go.await(5, TimeUnit.SECONDS)
                        SharedHttpClient.client
                    },
                )
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            val clients = seen.map { it.get(5, TimeUnit.SECONDS) }
            clients.forEach { assertSame(clients.first(), it) }
            assertEquals(1, SharedHttpClient.buildCount)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `timeouts and retry behaviour are the documented ones`() {
        val client = SharedHttpClient.client
        assertEquals(SharedHttpClient.CONNECT_TIMEOUT_SECONDS * 1000, client.connectTimeoutMillis.toLong())
        assertEquals(SharedHttpClient.READ_TIMEOUT_SECONDS * 1000, client.readTimeoutMillis.toLong())
        assertEquals(SharedHttpClient.WRITE_TIMEOUT_SECONDS * 1000, client.writeTimeoutMillis.toLong())
        assertTrue(client.retryOnConnectionFailure)
        // A per-call timeout would cap a large package transfer; the engine bounds it by cancelling.
        assertEquals(0, client.callTimeoutMillis)
    }

    @Test
    fun `the dispatcher and the pool belong to the shared client`() {
        val client = SharedHttpClient.client
        assertSame(client.dispatcher, SharedHttpClient.client.dispatcher)
        assertSame(client.connectionPool, SharedHttpClient.client.connectionPool)
    }

    @Test
    fun `two requests to the same host reuse one connection`() {
        KeepAliveServer().use { server ->
            repeat(2) {
                val response = SharedHttpClient.client
                    .newCall(Request.Builder().url(server.url()).build())
                    .execute()
                response.use {
                    assertEquals(200, it.code)
                    assertEquals("ok", it.body?.string())
                }
            }
            assertEquals(
                "the second request should ride the pooled connection instead of a new socket",
                1,
                server.accepts.get(),
            )
        }
    }

    /**
     * A minimal HTTP/1.1 keep-alive server: it counts accepted sockets and answers every request on
     * a connection with a fixed, length-delimited body, so the client can pool and reuse it.
     */
    private class KeepAliveServer : Closeable {
        private val server = ServerSocket(0)
        val accepts = AtomicInteger(0)

        init {
            thread(isDaemon = true, name = "keep-alive-server") {
                try {
                    while (!server.isClosed) {
                        val connection = server.accept()
                        accepts.incrementAndGet()
                        thread(isDaemon = true) { serve(connection) }
                    }
                } catch (_: IOException) {
                    // closed by close()
                }
            }
        }

        fun url(): String = "http://127.0.0.1:${server.localPort}/file"

        private fun serve(connection: Socket) {
            connection.use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                while (true) {
                    // Request line plus headers; every request in this test is a body-less GET.
                    val requestLine = readLine(input) ?: return
                    if (requestLine.isEmpty()) continue
                    while (true) {
                        val header = readLine(input) ?: return
                        if (header.isEmpty()) break
                    }
                    val body = "ok".toByteArray()
                    output.write(
                        ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: ${body.size}\r\n\r\n").toByteArray(),
                    )
                    output.write(body)
                    output.flush()
                }
            }
        }

        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0) return null
                if (byte == '\n'.code) {
                    if (line.isNotEmpty() && line.last() == '\r') line.setLength(line.length - 1)
                    return line.toString()
                }
                line.append(byte.toChar())
            }
        }

        override fun close() {
            server.close()
        }
    }
}

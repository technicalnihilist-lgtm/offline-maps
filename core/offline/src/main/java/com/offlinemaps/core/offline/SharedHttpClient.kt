package com.offlinemaps.core.offline

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one `OkHttpClient` the app transfers with (OPTIMIZATION.md C-04).
 *
 * A client owns its dispatcher and its connection pool, so a second client does not share either:
 * every client built for a request pays its own DNS lookup and TLS handshake for the same host and
 * leaves the previous client's idle connections to time out unused. The size probe (`HEAD`) and the
 * download of the same package therefore have to go through the same instance for the second one to
 * reuse the first one's connection - which is why the app has always kept a single client and why
 * that single client now lives here, in a class that builds it exactly once and counts the builds.
 *
 * The configuration is the one the app shipped with: 20 s connect, 60 s read, 60 s write, retries on
 * connection failure, and OkHttp's own pool and dispatcher (5 idle connections kept for 5 minutes).
 * They are named constants so the tests can pin them, and so a change has to be made on purpose.
 */
object SharedHttpClient {

    const val CONNECT_TIMEOUT_SECONDS = 20L
    const val READ_TIMEOUT_SECONDS = 60L
    const val WRITE_TIMEOUT_SECONDS = 60L

    /** OkHttp's default pool size and keep-alive, kept as-is but stated once. */
    const val POOL_MAX_IDLE_CONNECTIONS = 5
    const val POOL_KEEP_ALIVE_MINUTES = 5L

    private val builds = AtomicInteger(0)

    /**
     * How many clients this process has built. One is the whole point; the counter exists so a test
     * (and a future reader) can tell that nothing else builds one behind this holder's back.
     */
    val buildCount: Int get() = builds.get()

    val client: OkHttpClient by lazy {
        builds.incrementAndGet()
        OkHttpClient.Builder()
            .dispatcher(Dispatcher())
            .connectionPool(ConnectionPool(POOL_MAX_IDLE_CONNECTIONS, POOL_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

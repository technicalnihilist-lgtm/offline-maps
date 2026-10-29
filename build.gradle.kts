plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

/**
 * Robolectric downloads its Android runtime on first use, and some tests read fixtures from the
 * network. On networks that require an HTTP proxy, point the test JVM at it via HTTPS_PROXY.
 * OFFLINEMAPS_TRUSTSTORE lets CI supply a custom CA bundle; ROBOLECTRIC_DEPS points at a
 * pre-fetched Robolectric runtime for air-gapped builds; TEST_TMPDIR relocates test temp files.
 */
subprojects {
    tasks.withType<Test>().configureEach {
        System.getenv("HTTPS_PROXY")?.let { raw ->
            runCatching {
                val uri = java.net.URI(raw)
                uri.host?.let { host ->
                    systemProperty("https.proxyHost", host)
                    systemProperty("http.proxyHost", host)
                    systemProperty("https.proxyPort", uri.port.toString())
                    systemProperty("http.proxyPort", uri.port.toString())
                }
                uri.userInfo?.split(":", limit = 2)?.let { parts ->
                    if (parts.size == 2) {
                        systemProperty("https.proxyUser", parts[0])
                        systemProperty("https.proxyPassword", parts[1])
                        systemProperty("http.proxyUser", parts[0])
                        systemProperty("http.proxyPassword", parts[1])
                    }
                }
            }
        }
        // Unit tests exercise data and logic, not pixels, so legacy graphics is enough - and it
        // avoids loading native graphics libraries, which fail on some containerised runners.
        systemProperty("robolectric.graphicsMode", "LEGACY")
        System.getenv("TEST_TMPDIR")?.let { dir ->
            java.io.File(dir).mkdirs()
            systemProperty("java.io.tmpdir", dir)
        }
        System.getenv("ROBOLECTRIC_DEPS")?.let { dir ->
            systemProperty("robolectric.offline", "true")
            systemProperty("robolectric.dependency.dir", dir)
        }
        System.getenv("OFFLINEMAPS_TRUSTSTORE")?.let { store ->
            systemProperty("javax.net.ssl.trustStore", store)
            systemProperty("javax.net.ssl.trustStorePassword", System.getenv("OFFLINEMAPS_TRUSTSTORE_PASSWORD") ?: "changeit")
        }
        testLogging {
            events("passed", "failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        maxHeapSize = "1g"
    }
}

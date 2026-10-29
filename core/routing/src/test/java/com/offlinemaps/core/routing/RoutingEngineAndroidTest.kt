package com.offlinemaps.core.routing

import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.TravelProfile
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * The on-device path, run against the **real Android runtime** (Robolectric supplies android-all).
 *
 * Regression test for "app crashes after installing a region": the engine must unpack and load a
 * shipped graph on Android, and must return a message rather than throwing when anything is wrong. It
 * also guards against reintroducing a GraphHopper version whose OSM reader needs `javax.xml.stream`,
 * which Android does not implement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoutingEngineAndroidTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun unpackGraph(): File {
        val graphDir = File(temp.root, "graph")
        graphDir.mkdirs()
        val zip = temp.newFile("routing_andorra.zip")
        javaClass.classLoader!!.getResourceAsStream("routing_andorra.zip")!!.use { input ->
            zip.outputStream().use { output -> input.copyTo(output) }
        }
        ZipFile(zip).use { archive ->
            archive.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                val target = File(graphDir, entry.name)
                target.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return graphDir
    }

    @Test
    fun `loads a shipped graph under the android runtime`() {
        val engine = RoutingEngine(unpackGraph())
        val loaded = engine.load(listOf(TravelProfile.CAR))
        loaded.exceptionOrNull()?.printStackTrace()
        println("[android-runtime] load outcome: ${loaded.isSuccess} ${loaded.exceptionOrNull()?.message}")
        assertTrue("graph load failed on Android: ${loaded.exceptionOrNull()}", loaded.isSuccess)
        engine.close()
    }

    @Test
    fun `routes under the android runtime`() {
        val engine = RoutingEngine(unpackGraph())
        val loaded = engine.load(listOf(TravelProfile.CAR))
        if (loaded.isFailure) {
            loaded.exceptionOrNull()?.printStackTrace()
            throw AssertionError("load failed: ${loaded.exceptionOrNull()}")
        }
        val result = engine.route(GeoPoint(42.5069, 1.5212), GeoPoint(42.5090, 1.5340), TravelProfile.CAR)
        println("[android-runtime] route result: $result")
        assertTrue("routing failed on Android: $result", result is RouteResult.Success)
        engine.close()
    }

    @Test
    fun `a missing graph returns a failure instead of crashing`() {
        val engine = RoutingEngine(File(temp.root, "empty"))
        val loaded = engine.load()
        assertTrue("expected a failure, got $loaded", loaded.isFailure)
        engine.close()
    }
}

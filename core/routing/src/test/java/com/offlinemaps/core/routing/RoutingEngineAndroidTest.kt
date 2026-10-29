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

/**
 * Runs the routing engine under the **Android runtime** (Robolectric supplies the real
 * android-all class library), not a desktop JVM.
 *
 * This is the regression test for the on-device crash after installing a region: GraphHopper's
 * dependency graph references desktop-only classes (javax.xml.stream, java.awt, javax.imageio,
 * java.lang.instrument) that do not exist on Android, and any of them being touched during import
 * throws an Error that must not escape to the UI.
 *
 * The test prints the full failure so CI logs show the real cause even when it fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoutingEngineAndroidTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun andorraExtract(): File {
        val osm = temp.newFile("andorra.osm.pbf")
        javaClass.classLoader!!.getResourceAsStream("andorra.osm.pbf")!!.use { input ->
            osm.outputStream().use { output -> input.copyTo(output) }
        }
        return osm
    }

    @Test
    fun `imports an osm extract under the android runtime`() {
        val graphDir = File(temp.root, "graph")
        val engine = RoutingEngine(graphDir)
        val attempt = runCatching {
            engine.importOrLoad(andorraExtract(), listOf(TravelProfile.CAR)) { phase ->
                println("[android-runtime] import phase: $phase")
            }
        }
        attempt.exceptionOrNull()?.printStackTrace()
        println("[android-runtime] import outcome: ${attempt.isSuccess}")
        assertTrue(
            "GraphHopper import threw under the Android runtime: " +
                "${attempt.exceptionOrNull()?.javaClass?.name}: ${attempt.exceptionOrNull()?.message}",
            attempt.isSuccess,
        )
        engine.close()
    }

    @Test
    fun `routes after importing under the android runtime`() {
        val graphDir = File(temp.root, "graph-route")
        val engine = RoutingEngine(graphDir)
        val imported = runCatching { engine.importOrLoad(andorraExtract(), listOf(TravelProfile.CAR)) }
        if (imported.isFailure) {
            imported.exceptionOrNull()?.printStackTrace()
            throw AssertionError("import failed: ${imported.exceptionOrNull()}")
        }
        val result = engine.route(
            GeoPoint(42.5069, 1.5212),
            GeoPoint(42.5090, 1.5340),
            TravelProfile.CAR,
        )
        println("[android-runtime] route result: $result")
        assertTrue("routing failed under the Android runtime: $result", result is RouteResult.Success)
        engine.close()
    }
}

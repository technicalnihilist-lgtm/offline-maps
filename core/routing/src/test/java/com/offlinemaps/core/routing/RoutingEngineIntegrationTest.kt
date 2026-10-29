package com.offlinemaps.core.routing

import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.TravelProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * End-to-end routing over a **pre-built** graph, which is exactly what the app installs.
 *
 * The fixture `routing_andorra.zip` is the graph shipped in `app/src/main/assets` - built from a real
 * OpenStreetMap extract of Andorra (OpenStreetMap contributors, ODbL) with
 * `tools/build_routing_graph`. Nothing is imported here: the engine only unpacks and loads, which is
 * the only thing that can work on Android.
 */
class RoutingEngineIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var engine: RoutingEngine
    private lateinit var graphDir: File

    private val andorraLaVella = GeoPoint(42.5069, 1.5212)
    private val escaldes = GeoPoint(42.5090, 1.5340)

    private fun unpackGraph(destination: File) {
        destination.mkdirs()
        val zip = temp.newFile("routing_andorra.zip")
        javaClass.classLoader!!.getResourceAsStream("routing_andorra.zip")!!.use { input ->
            zip.outputStream().use { output -> input.copyTo(output) }
        }
        ZipFile(zip).use { archive ->
            archive.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                val target = File(destination, entry.name)
                target.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    @Before
    fun setUp() {
        graphDir = File(temp.root, "graph")
        unpackGraph(graphDir)
        engine = RoutingEngine(graphDir)
        val loaded = engine.load(listOf(TravelProfile.CAR, TravelProfile.FOOT, TravelProfile.BIKE))
        assertTrue("graph failed to load: ${loaded.exceptionOrNull()}", loaded.isSuccess)
    }

    @After
    fun tearDown() {
        engine.close()
    }

    @Test
    fun `shipped graph loads from disk`() {
        assertTrue("graph directory should not be empty", (graphDir.listFiles()?.size ?: 0) > 0)
        assertTrue(engine.isLoaded)
        assertEquals(3, engine.availableProfiles.size)
    }

    @Test
    fun `calculates a real car route with turn instructions`() {
        val result = engine.route(andorraLaVella, escaldes, TravelProfile.CAR)
        assertTrue("route failed: $result", result is RouteResult.Success)
        val plan = (result as RouteResult.Success).plan
        assertTrue(
            "distance should be a real road distance, was ${plan.distanceMeters}",
            plan.distanceMeters in 200.0..20_000.0,
        )
        assertTrue("duration should be positive", plan.durationSeconds > 0)
        assertTrue("geometry should have many points", plan.geometry.size > 10)
        assertTrue("route should have instructions", plan.maneuvers.size >= 3)
        assertEquals(ManeuverType.DEPART, plan.maneuvers.first().type)
        assertEquals(ManeuverType.ARRIVE, plan.maneuvers.last().type)
        assertTrue(
            "instructions should carry street names from OSM",
            plan.maneuvers.any { !it.streetName.isNullOrBlank() },
        )
        val indices = plan.maneuvers.map { it.geometryIndex }
        assertEquals(indices.sorted(), indices)
    }

    @Test
    fun `walking and cycling profiles both route`() {
        val foot = engine.route(andorraLaVella, escaldes, TravelProfile.FOOT)
        val bike = engine.route(andorraLaVella, escaldes, TravelProfile.BIKE)
        assertTrue("foot failed: $foot", foot is RouteResult.Success)
        assertTrue("bike failed: $bike", bike is RouteResult.Success)
        assertTrue(
            "walking should be slower than cycling",
            (foot as RouteResult.Success).plan.durationSeconds >
                (bike as RouteResult.Success).plan.durationSeconds,
        )
    }

    @Test
    fun `loading without a graph fails with a message instead of throwing`() {
        val empty = RoutingEngine(File(temp.root, "nothing-here"))
        val result = empty.load()
        assertTrue("expected a failure, got $result", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("No routing graph") == true)
        val route = empty.route(andorraLaVella, escaldes, TravelProfile.CAR)
        assertTrue(route is RouteResult.Failure)
        assertEquals(FailureKind.NO_ROUTING_DATA, (route as RouteResult.Failure).kind)
        empty.close()
    }

    @Test
    fun `a point far outside the graph is reported as outside coverage`() {
        val result = engine.route(andorraLaVella, GeoPoint(35.6812, 139.7671), TravelProfile.CAR)
        assertTrue("expected a failure, got $result", result is RouteResult.Failure)
        val kind = (result as RouteResult.Failure).kind
        assertTrue(
            "expected OUTSIDE_COVERAGE or NO_ROUTE, got $kind",
            kind == FailureKind.OUTSIDE_COVERAGE || kind == FailureKind.NO_ROUTE,
        )
    }
}

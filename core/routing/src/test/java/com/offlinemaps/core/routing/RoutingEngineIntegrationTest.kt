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

/**
 * End-to-end routing on a real OpenStreetMap extract.
 *
 * The fixture is `andorra.osm.pbf` - a small, real OSM extract ((c) OpenStreetMap contributors,
 * ODbL). This test imports it into a GraphHopper graph on the JVM, routes over it, and then reloads
 * the graph from disk to prove the offline-reopen path works.
 */
class RoutingEngineIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var engine: RoutingEngine
    private lateinit var graphDir: File

    private val andorraLaVella = GeoPoint(42.5069, 1.5212)
    private val escaldes = GeoPoint(42.5090, 1.5340)

    @Before
    fun setUp() {
        val osm = temp.newFile("andorra.osm.pbf")
        javaClass.classLoader!!.getResourceAsStream("andorra.osm.pbf")!!.use { input ->
            osm.outputStream().use { output -> input.copyTo(output) }
        }
        graphDir = File(temp.root, "graph")
        engine = RoutingEngine(graphDir)
        engine.importOrLoad(osm, listOf(TravelProfile.CAR, TravelProfile.FOOT, TravelProfile.BIKE))
    }

    @After
    fun tearDown() {
        engine.close()
    }

    @Test
    fun `graph is written to disk after import`() {
        assertTrue("graph directory should exist", graphDir.isDirectory)
        assertTrue("graph directory should not be empty", (graphDir.listFiles()?.size ?: 0) > 0)
        assertTrue(engine.isLoaded)
    }

    @Test
    fun `calculates a real car route with turn instructions`() {
        val result = engine.route(andorraLaVella, escaldes, TravelProfile.CAR)
        assertTrue("route failed: $result", result is RouteResult.Success)
        val plan = (result as RouteResult.Success).plan
        assertTrue("distance should be a real road distance, was ${plan.distanceMeters}", plan.distanceMeters in 500.0..20_000.0)
        assertTrue("duration should be positive", plan.durationSeconds > 0)
        assertTrue("geometry should have many points", plan.geometry.size > 20)
        assertTrue("route should have instructions", plan.maneuvers.size >= 3)
        assertEquals(ManeuverType.DEPART, plan.maneuvers.first().type)
        assertEquals(ManeuverType.ARRIVE, plan.maneuvers.last().type)
        assertTrue("instructions should carry street names from OSM", plan.maneuvers.any { !it.streetName.isNullOrBlank() })
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
            (foot as RouteResult.Success).plan.durationSeconds > (bike as RouteResult.Success).plan.durationSeconds,
        )
    }

    @Test
    fun `graph reloads from disk without the osm extract`() {
        engine.close()
        val reloaded = RoutingEngine(graphDir)
        reloaded.importOrLoad(osmFile = null, profiles = listOf(TravelProfile.CAR, TravelProfile.FOOT, TravelProfile.BIKE))
        assertTrue(
            "reloaded graph should still route",
            reloaded.route(andorraLaVella, escaldes, TravelProfile.CAR) is RouteResult.Success,
        )
        reloaded.close()
    }

    @Test
    fun `routing without a graph fails with a clear message`() {
        val empty = RoutingEngine(File(temp.root, "nothing-here"))
        val result = empty.route(andorraLaVella, escaldes, TravelProfile.CAR)
        assertTrue(result is RouteResult.Failure)
        val failure = result as RouteResult.Failure
        assertEquals(FailureKind.NO_ROUTING_DATA, failure.kind)
        assertTrue(failure.message.contains("No routing data"))
        empty.close()
    }

    @Test
    fun `a point far outside the extract is reported as outside coverage`() {
        val result = engine.route(andorraLaVella, GeoPoint(35.6812, 139.7671), TravelProfile.CAR)
        assertTrue("expected a failure, got $result", result is RouteResult.Failure)
        val kind = (result as RouteResult.Failure).kind
        assertTrue(
            "expected OUTSIDE_COVERAGE or NO_ROUTE, got $kind",
            kind == FailureKind.OUTSIDE_COVERAGE || kind == FailureKind.NO_ROUTE,
        )
    }
}

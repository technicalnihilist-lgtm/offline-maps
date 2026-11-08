package com.offlinemaps.core.routing

import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.TravelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * The graph is closed on a memory trim and read back from disk on demand (OPTIMIZATION.md E-01).
 *
 * Before this, [RoutingEngine.close] was final: the only caller of `load` is the installer, so a
 * released graph - or a graph that had never been loaded in this process at all - meant every
 * route failed with "No routing data is installed" until the app was reinstalled or restarted.
 *
 * Uses the same pre-built Andorra graph the app ships, unpacked exactly as the installer does.
 */
class RoutingEngineLazyReloadTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val andorraLaVella = GeoPoint(42.5069, 1.5212)
    private val escaldes = GeoPoint(42.5090, 1.5340)

    private fun unpackGraph(destination: File): File {
        destination.mkdirs()
        val zip = File(temp.root, "routing_andorra.zip")
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
        return destination
    }

    @Test
    fun `close releases the graph and the next route reads it back from disk`() {
        val engine = RoutingEngine(unpackGraph(File(temp.root, "graph")))
        assertTrue(engine.load().isSuccess)
        assertEquals(1, engine.loadAttempts)

        engine.close()
        assertFalse("close must free the graph", engine.isLoaded)
        assertTrue("no profile is available while released", engine.availableProfiles.isEmpty())

        val result = engine.route(andorraLaVella, escaldes, TravelProfile.CAR)
        assertTrue("route after a trim should reload the graph, got $result", result is RouteResult.Success)
        assertTrue(engine.isLoaded)
        assertEquals("the reload reuses the full profile set", 3, engine.availableProfiles.size)
        assertEquals(2, engine.loadAttempts)
        engine.close()
    }

    @Test
    fun `a fresh process routes without an explicit load`() {
        val engine = RoutingEngine(unpackGraph(File(temp.root, "graph")))
        assertFalse(engine.isLoaded)
        val result = engine.route(andorraLaVella, escaldes, TravelProfile.CAR)
        assertTrue("first route in a new process should load the graph, got $result", result is RouteResult.Success)
        assertEquals(1, engine.loadAttempts)
        engine.close()
    }

    @Test
    fun `the reload keeps the profile set of the last load`() {
        val engine = RoutingEngine(unpackGraph(File(temp.root, "graph")))
        assertTrue(engine.load(listOf(TravelProfile.CAR)).isSuccess)
        engine.close()

        // The reload brings back only the profiles that were asked for, so foot is still refused -
        // with the same failure the engine gives for a graph that lacks the profile.
        val foot = engine.route(andorraLaVella, escaldes, TravelProfile.FOOT)
        assertTrue("expected a failure, got $foot", foot is RouteResult.Failure)
        assertEquals(FailureKind.NO_ROUTING_DATA, (foot as RouteResult.Failure).kind)
        assertEquals(listOf(TravelProfile.CAR), engine.availableProfiles)

        val car = engine.route(andorraLaVella, escaldes, TravelProfile.CAR)
        assertTrue("car should route on the reloaded graph, got $car", car is RouteResult.Success)
        engine.close()
    }

    @Test
    fun `close is idempotent and safe before any load`() {
        val engine = RoutingEngine(unpackGraph(File(temp.root, "graph")))
        engine.close()
        engine.close()
        assertFalse(engine.isLoaded)
        assertEquals(0, engine.loadAttempts)
        assertTrue(engine.route(andorraLaVella, escaldes, TravelProfile.CAR) is RouteResult.Success)
        engine.close()
    }

    @Test
    fun `a graph that will not load is not retried on every route`() {
        // A directory that exists (so hasGraph() is true) but holds nothing GraphHopper can import.
        val broken = File(temp.root, "broken").apply { mkdirs() }
        File(broken, "readme.txt").writeText("not a routing graph")
        val engine = RoutingEngine(broken)

        val first = engine.route(andorraLaVella, escaldes, TravelProfile.CAR)
        assertTrue("expected a failure, got $first", first is RouteResult.Failure)
        assertEquals(FailureKind.NO_ROUTING_DATA, (first as RouteResult.Failure).kind)
        assertEquals(1, engine.loadAttempts)

        val second = engine.route(andorraLaVella, escaldes, TravelProfile.CAR)
        assertTrue("expected a failure, got $second", second is RouteResult.Failure)
        assertEquals(
            "a failed reload must not be attempted again for every fix",
            1,
            engine.loadAttempts,
        )
        assertFalse(engine.isLoaded)
        engine.close()
    }

    @Test
    fun `an explicit load clears a failed lazy reload`() {
        val broken = File(temp.root, "recoverable").apply { mkdirs() }
        File(broken, "readme.txt").writeText("not a routing graph")
        val engine = RoutingEngine(broken)
        assertTrue(engine.route(andorraLaVella, escaldes, TravelProfile.CAR) is RouteResult.Failure)
        assertEquals(1, engine.loadAttempts)

        // What an install does: put a real graph in place and call load() explicitly.
        broken.deleteRecursively()
        unpackGraph(broken)
        assertTrue(engine.load().isSuccess)
        assertTrue(engine.route(andorraLaVella, escaldes, TravelProfile.CAR) is RouteResult.Success)
        engine.close()
    }

    @Test
    fun `no graph on disk still fails without touching the loader`() {
        val engine = RoutingEngine(File(temp.root, "nothing-here"))
        val result = engine.route(andorraLaVella, escaldes, TravelProfile.CAR)
        assertTrue(result is RouteResult.Failure)
        assertEquals(FailureKind.NO_ROUTING_DATA, (result as RouteResult.Failure).kind)
        assertEquals(0, engine.loadAttempts)
        engine.close()
    }
}

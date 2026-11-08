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
 * A graph is only re-imported when the profile set really changed (OPTIMIZATION.md E-02).
 *
 * Loading a pre-built graph reads hundreds of megabytes from disk and holds them in memory, so the
 * shortcut in [RoutingEngine.load] matters: the second `load` for the same profiles must return
 * without closing the live graph. The shortcut is judged on the *set* of profiles, so the same
 * profiles in another order - or repeated - are still the same graph, and only a genuinely different
 * set pays for a reload.
 *
 * Runs against the pre-built Andorra graph the app ships, unpacked exactly as the installer does.
 */
class RoutingEngineProfileSetTest {

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

    private fun engine(): RoutingEngine = RoutingEngine(unpackGraph(File(temp.root, "graph")))

    // --- the pure canonical form -----------------------------------------------------------------

    @Test
    fun `canonical removes duplicates and sorts into declaration order`() {
        assertEquals(
            listOf(TravelProfile.CAR, TravelProfile.BIKE, TravelProfile.FOOT),
            ProfileSet.canonical(
                listOf(
                    TravelProfile.FOOT,
                    TravelProfile.CAR,
                    TravelProfile.BIKE,
                    TravelProfile.CAR,
                ),
            ),
        )
        assertEquals(
            listOf(TravelProfile.BIKE, TravelProfile.FOOT),
            ProfileSet.canonical(listOf(TravelProfile.FOOT, TravelProfile.BIKE)),
        )
        assertEquals(emptyList<TravelProfile>(), ProfileSet.canonical(emptyList()))
        assertEquals(
            TravelProfile.entries.toList(),
            ProfileSet.canonical(TravelProfile.entries.toList()),
        )
    }

    @Test
    fun `matches compares the set, not the order or the repetition`() {
        assertTrue(ProfileSet.matches(listOf("car", "bike", "foot"), TravelProfile.entries.toList()))
        assertTrue(
            ProfileSet.matches(
                listOf("foot", "car", "bike"),
                listOf(TravelProfile.CAR, TravelProfile.BIKE, TravelProfile.FOOT),
            ),
        )
        assertTrue(
            "a repeated profile is the same request",
            ProfileSet.matches(listOf("car"), listOf(TravelProfile.CAR, TravelProfile.CAR)),
        )
        assertFalse(
            "a missing profile is a different graph",
            ProfileSet.matches(listOf("car", "bike"), listOf(TravelProfile.CAR)),
        )
        assertFalse(
            "an extra profile is a different graph",
            ProfileSet.matches(listOf("car"), listOf(TravelProfile.CAR, TravelProfile.FOOT)),
        )
        assertFalse(ProfileSet.matches(emptyList(), listOf(TravelProfile.CAR)))
        assertFalse(ProfileSet.matches(listOf("car"), emptyList()))
    }

    // --- the engine shortcut ---------------------------------------------------------------------

    @Test
    fun `a second load for the same profiles does not import the graph again`() {
        val engine = engine()
        val firstProgress = ArrayList<String>()
        assertTrue(engine.load(TravelProfile.entries.toList()) { firstProgress += it }.isSuccess)
        assertEquals(1, engine.loadAttempts)
        assertTrue("a real load reports its phases", firstProgress.contains("Loading routing graph"))

        val secondProgress = ArrayList<String>()
        assertTrue(engine.load(TravelProfile.entries.toList()) { secondProgress += it }.isSuccess)
        assertEquals("the same profile set must not reload", 1, engine.loadAttempts)
        assertTrue("a skipped load does no work to report", secondProgress.isEmpty())
        assertEquals(TravelProfile.entries.toList(), engine.availableProfiles)
        assertTrue(engine.isLoaded)

        // The graph that survived the skip is the live one, not a released handle.
        val route = engine.route(andorraLaVella, escaldes, TravelProfile.CAR)
        assertTrue("route after a skipped load should succeed, got $route", route is RouteResult.Success)
        engine.close()
    }

    @Test
    fun `reordering the requested profiles does not reload`() {
        val engine = engine()
        assertTrue(
            engine.load(listOf(TravelProfile.CAR, TravelProfile.BIKE)).isSuccess,
        )
        assertEquals(1, engine.loadAttempts)

        assertTrue(
            engine.load(listOf(TravelProfile.BIKE, TravelProfile.CAR)).isSuccess,
        )
        assertEquals("order alone is not a change", 1, engine.loadAttempts)
        assertEquals(
            "the served set is reported in declaration order",
            listOf(TravelProfile.CAR, TravelProfile.BIKE),
            engine.availableProfiles,
        )
        engine.close()
    }

    @Test
    fun `a repeated profile is neither a reload nor a duplicate`() {
        val engine = engine()
        assertTrue(engine.load(listOf(TravelProfile.CAR, TravelProfile.CAR)).isSuccess)
        assertEquals(1, engine.loadAttempts)
        assertEquals(
            "the same profile asked for twice is one profile",
            listOf(TravelProfile.CAR),
            engine.availableProfiles,
        )

        assertTrue(engine.load(listOf(TravelProfile.CAR)).isSuccess)
        assertEquals(1, engine.loadAttempts)
        engine.close()
    }

    @Test
    fun `a wider profile set does reload and serves the new profile`() {
        val engine = engine()
        assertTrue(engine.load(listOf(TravelProfile.CAR)).isSuccess)
        assertEquals(1, engine.loadAttempts)

        assertTrue(engine.load(listOf(TravelProfile.CAR, TravelProfile.FOOT)).isSuccess)
        assertEquals("a wider set is a real change", 2, engine.loadAttempts)
        assertEquals(
            listOf(TravelProfile.CAR, TravelProfile.FOOT),
            engine.availableProfiles,
        )
        val foot = engine.route(andorraLaVella, escaldes, TravelProfile.FOOT)
        assertTrue("foot should route now, got $foot", foot is RouteResult.Success)
        engine.close()
    }

    @Test
    fun `a narrower profile set does reload and drops the profile`() {
        val engine = engine()
        assertTrue(engine.load(TravelProfile.entries.toList()).isSuccess)
        assertEquals(1, engine.loadAttempts)

        assertTrue(engine.load(listOf(TravelProfile.CAR)).isSuccess)
        assertEquals("a narrower set is a real change", 2, engine.loadAttempts)
        assertEquals(listOf(TravelProfile.CAR), engine.availableProfiles)

        val foot = engine.route(andorraLaVella, escaldes, TravelProfile.FOOT)
        assertTrue("expected a failure, got $foot", foot is RouteResult.Failure)
        assertEquals(FailureKind.NO_ROUTING_DATA, (foot as RouteResult.Failure).kind)
        assertTrue(engine.route(andorraLaVella, escaldes, TravelProfile.CAR) is RouteResult.Success)
        engine.close()
    }

    @Test
    fun `the default profile set matches an explicit request for all profiles`() {
        val engine = engine()
        assertTrue(engine.load().isSuccess)
        assertEquals(1, engine.loadAttempts)

        assertTrue(engine.load(TravelProfile.entries.toList()).isSuccess)
        assertEquals("the default is the full set, so nothing changes", 1, engine.loadAttempts)
        engine.close()
    }
}

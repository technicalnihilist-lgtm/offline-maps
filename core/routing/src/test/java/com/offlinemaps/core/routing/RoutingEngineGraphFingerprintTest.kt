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
 * Reinstalling a routing package for a region replaces the graph the engine is serving
 * (OPTIMIZATION.md J-10).
 *
 * `PackageInstaller.installRouting` unpacks the new package into a staging directory, renames it
 * over the graph root and then calls `load` with the same profile set as before. The shortcut in
 * `load` used to be decided on that profile set alone, so the install reported success and the
 * engine carried on routing from the graph it had read out of the files that had just been deleted -
 * a re-downloaded package (a corrected graph, a newer region build) had no effect until the process
 * restarted.
 *
 * Runs against the pre-built Andorra graph the app ships, unpacked exactly as the installer does, and
 * swapped the same way: a fresh directory renamed into the graph root.
 */
class RoutingEngineGraphFingerprintTest {

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

    /**
     * Replaces the graph at [root] with a freshly unpacked copy, the way
     * [GraphInstallTransaction.stage] publishes an install: the new tree is built beside the old one
     * and renamed into place.
     *
     * The files are then stamped with one fixed, clearly past timestamp. A real reinstall writes them
     * at "now", which a test can reproduce but not guarantee to differ from the first unpack - and the
     * point of the test is that a package reinstalled with the *same* bytes is still noticed.
     */
    private fun reinstallOver(root: File) {
        val staged = File(root.parentFile, "${root.name}${GraphInstallTransaction.STAGING_SUFFIX}")
        staged.deleteRecursively()
        unpackGraph(staged)
        staged.listFiles()!!.forEach { it.setLastModified(REINSTALL_TIME_MILLIS) }
        val previous = File(root.parentFile, "${root.name}${GraphInstallTransaction.PREVIOUS_SUFFIX}")
        previous.deleteRecursively()
        assertTrue("could not move the old graph aside", root.renameTo(previous))
        assertTrue("could not publish the new graph", staged.renameTo(root))
        previous.deleteRecursively()
    }

    @Test
    fun `a reinstalled package is imported even though the profile set did not change`() {
        val graphDir = unpackGraph(File(temp.root, "graph"))
        val engine = RoutingEngine(graphDir)
        assertTrue(engine.load().isSuccess)
        assertEquals("the first load imports once", 1, engine.loadAttempts)

        reinstallOver(graphDir)

        val reloaded = engine.load()
        assertTrue("load after an install failed: ${reloaded.exceptionOrNull()}", reloaded.isSuccess)
        assertEquals(
            "the graph on disk was replaced, so it must be imported again",
            2,
            engine.loadAttempts,
        )
        assertTrue(engine.isLoaded)
        assertEquals(3, engine.availableProfiles.size)
        val route = engine.route(andorraLaVella, escaldes, TravelProfile.CAR)
        assertTrue("routing must work from the reinstalled graph, got $route", route is RouteResult.Success)
        engine.close()
    }

    @Test
    fun `a second load of untouched files still skips the import`() {
        val engine = RoutingEngine(unpackGraph(File(temp.root, "graph")))
        assertTrue(engine.load().isSuccess)
        assertTrue(engine.load().isSuccess)
        assertTrue(engine.load().isSuccess)
        assertEquals(
            "the fingerprint must not drift, or every load would re-import the graph",
            1,
            engine.loadAttempts,
        )
        engine.close()
    }

    @Test
    fun `a profile change still reloads after the fingerprint was recorded`() {
        val engine = RoutingEngine(unpackGraph(File(temp.root, "graph")))
        assertTrue(engine.load(listOf(TravelProfile.CAR)).isSuccess)
        assertTrue(engine.load(listOf(TravelProfile.CAR, TravelProfile.FOOT)).isSuccess)
        assertEquals("a different profile set is a different graph to import", 2, engine.loadAttempts)
        assertEquals(2, engine.availableProfiles.size)
        engine.close()
    }

    @Test
    fun `an emptied graph directory keeps the graph already in memory`() {
        val graphDir = unpackGraph(File(temp.root, "graph"))
        val engine = RoutingEngine(graphDir)
        assertTrue(engine.load().isSuccess)

        // Nothing on disk to import: closing the live graph would only leave routing dead.
        assertTrue(graphDir.deleteRecursively())
        assertTrue(engine.load().isSuccess)
        assertEquals(1, engine.loadAttempts)
        assertTrue(engine.isLoaded)
        assertFalse("the region now reports no graph on disk", engine.hasGraph())
        engine.close()
    }

    private companion object {
        /** 2001-09-09T01:46:40Z - unmistakably older than anything the test just wrote. */
        const val REINSTALL_TIME_MILLIS = 1_000_000_000_000L
    }
}

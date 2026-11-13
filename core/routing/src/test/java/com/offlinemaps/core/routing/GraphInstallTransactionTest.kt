package com.offlinemaps.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Pins the staged-then-swapped routing install and the recovery that settles a swap the process did
 * not finish (OPTIMIZATION.md I-07).
 *
 * The interesting property is not that a good package lands in place - it did before - but that the
 * graph already on disk survives every way the install can be interrupted or refused. The kill
 * windows are reproduced by building the on-disk state each one leaves behind and asking
 * [GraphInstallTransaction.recover] to settle it, because a unit test cannot kill its own process
 * between two renames.
 */
class GraphInstallTransactionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun graphRoot(): File = File(temp.root, "routing/current")

    private fun graphAt(directory: File, vararg files: String): File {
        directory.mkdirs()
        files.forEach { File(directory, it).writeText("graph:$it") }
        return directory
    }

    private fun filesIn(directory: File): Set<String> =
        directory.listFiles()?.map { it.name }?.toSet() ?: emptySet()

    /** Writes a package body that stands in for an unpacked graph. */
    private fun unpackBody(vararg files: String): (File) -> Result<Unit> = { staging ->
        runCatching { files.forEach { File(staging, it).writeText("graph:$it") } }
    }

    @Test
    fun `a fresh install publishes the staged graph and leaves no debris`() {
        val root = graphRoot()

        val result = GraphInstallTransaction.stage(root, unpackBody("nodes", "edges"))

        assertEquals(GraphInstallTransaction.StageResult.Published, result)
        assertEquals(setOf("nodes", "edges"), filesIn(root))
        assertFalse(GraphInstallTransaction.stagingFor(root).exists())
        assertFalse(GraphInstallTransaction.previousFor(root).exists())
    }

    @Test
    fun `an update replaces the graph that was live`() {
        val root = graphAt(graphRoot(), "nodes", "edges", "properties")

        val result = GraphInstallTransaction.stage(root, unpackBody("nodes", "edges", "geometry"))

        assertEquals(GraphInstallTransaction.StageResult.Published, result)
        assertEquals(setOf("nodes", "edges", "geometry"), filesIn(root))
        assertEquals("graph:geometry", File(root, "geometry").readText())
        assertFalse(GraphInstallTransaction.previousFor(root).exists())
    }

    @Test
    fun `a rejected package leaves the working graph exactly as it was`() {
        val root = graphAt(graphRoot(), "nodes", "edges", "properties")

        val result = GraphInstallTransaction.stage(root) { staging ->
            File(staging, "half-written").writeText("partial")
            Result.failure(IllegalStateException("not a graph"))
        }

        assertTrue(result is GraphInstallTransaction.StageResult.Rejected)
        assertEquals("not a graph", (result as GraphInstallTransaction.StageResult.Rejected).reason.message)
        assertEquals(setOf("nodes", "edges", "properties"), filesIn(root))
        assertFalse(GraphInstallTransaction.stagingFor(root).exists())
    }

    @Test
    fun `a package body that throws is a rejection, not a crash`() {
        val root = graphAt(graphRoot(), "nodes")

        val result = GraphInstallTransaction.stage(root) { throw IOException("disk full") }

        assertTrue(result is GraphInstallTransaction.StageResult.Rejected)
        assertEquals("disk full", (result as GraphInstallTransaction.StageResult.Rejected).reason.message)
        assertEquals(setOf("nodes"), filesIn(root))
    }

    @Test
    fun `recovery discards a staging directory left by a kill during the unpack`() {
        val root = graphAt(graphRoot(), "nodes", "edges")
        graphAt(GraphInstallTransaction.stagingFor(root), "nodes", "half")

        val recovery = GraphInstallTransaction.recover(root)

        assertTrue(recovery.discardedStaging)
        assertTrue(recovery.changed)
        assertFalse(GraphInstallTransaction.stagingFor(root).exists())
        assertEquals(setOf("nodes", "edges"), filesIn(root))
    }

    @Test
    fun `recovery puts the graph back when the swap died between its two renames`() {
        val root = graphRoot()
        // The state a kill leaves after `current` -> `current.previous` and before `.staging` ->
        // `current`: the working graph is parked under `.previous` and nothing is live.
        graphAt(GraphInstallTransaction.previousFor(root), "nodes", "edges", "properties")
        graphAt(GraphInstallTransaction.stagingFor(root), "nodes", "edges", "properties", "new")

        val recovery = GraphInstallTransaction.recover(root)

        assertTrue(recovery.restoredPrevious)
        assertTrue(recovery.discardedStaging)
        assertEquals(setOf("nodes", "edges", "properties"), filesIn(root))
        assertFalse(GraphInstallTransaction.previousFor(root).exists())
        assertFalse(GraphInstallTransaction.stagingFor(root).exists())
    }

    @Test
    fun `recovery treats an empty live directory as missing and restores the previous graph`() {
        val root = graphRoot()
        root.mkdirs()
        graphAt(GraphInstallTransaction.previousFor(root), "nodes", "edges")

        val recovery = GraphInstallTransaction.recover(root)

        assertTrue(recovery.restoredPrevious)
        assertEquals(setOf("nodes", "edges"), filesIn(root))
    }

    @Test
    fun `recovery drops a stale previous directory when the swap had already finished`() {
        val root = graphAt(graphRoot(), "nodes", "edges", "geometry")
        graphAt(GraphInstallTransaction.previousFor(root), "nodes", "edges")

        val recovery = GraphInstallTransaction.recover(root)

        assertTrue(recovery.discardedPrevious)
        assertFalse(GraphInstallTransaction.previousFor(root).exists())
        assertEquals(setOf("nodes", "edges", "geometry"), filesIn(root))
    }

    @Test
    fun `recovery on a healthy device changes nothing`() {
        val root = graphAt(graphRoot(), "nodes", "edges")

        val recovery = GraphInstallTransaction.recover(root)

        assertFalse(recovery.changed)
        assertEquals(setOf("nodes", "edges"), filesIn(root))
    }

    @Test
    fun `an install after an interrupted one still publishes`() {
        val root = graphRoot()
        graphAt(GraphInstallTransaction.previousFor(root), "nodes", "old")
        graphAt(GraphInstallTransaction.stagingFor(root), "nodes", "half")

        // stage() settles the interrupted swap itself before it stages anything, so the graph the
        // recovery restored cannot be lost to the next install.
        val result = GraphInstallTransaction.stage(root, unpackBody("nodes", "edges", "new"))

        assertEquals(GraphInstallTransaction.StageResult.Published, result)
        assertEquals(setOf("nodes", "edges", "new"), filesIn(root))
        assertNotNull(root.listFiles())
    }
}

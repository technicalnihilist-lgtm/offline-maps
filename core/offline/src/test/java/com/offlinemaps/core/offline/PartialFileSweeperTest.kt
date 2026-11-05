package com.offlinemaps.core.offline

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The startup sweep that removes partial files nothing can resume (OPTIMIZATION.md C-06). */
class PartialFileSweeperTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `removes a part and its sidecar when no record claims them`() = runBlocking {
        val dir = temp.newFolder("packages")
        val part = File(dir, "region-A.osm.pbf.part").apply { writeText("half a file") }
        val meta = File(dir, "region-A.osm.pbf.meta").apply { writeText("etag\n\n100") }

        val deleted = PartialFileSweeper.sweep(dir) { false }

        assertEquals(setOf(part.name, meta.name), deleted.toSet())
        assertFalse(part.exists())
        assertFalse(meta.exists())
    }

    @Test
    fun `keeps the partial of a download a record still claims`() = runBlocking {
        val dir = temp.newFolder("packages")
        val paused = File(dir, "region-B.tiles.part").apply { writeText("paused bytes") }
        val orphan = File(dir, "region-C.tiles.part").apply { writeText("cancelled bytes") }

        val deleted = PartialFileSweeper.sweep(dir) { it == "region-B.tiles" }

        assertEquals(listOf(orphan.name), deleted)
        assertTrue("a paused download must keep its bytes", paused.exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun `leaves installed packages and unrelated files alone`() = runBlocking {
        val dir = temp.newFolder("packages")
        val installed = File(dir, "region-D.osm.pbf").apply { writeText("installed") }
        val notes = File(dir, "notes.txt").apply { writeText("not ours") }

        val deleted = PartialFileSweeper.sweep(dir) { false }

        assertEquals(emptyList<String>(), deleted)
        assertTrue(installed.exists())
        assertTrue(notes.exists())
    }

    @Test
    fun `a missing directory is not an error`() = runBlocking {
        val missing = File(temp.root, "never-created")

        assertEquals(emptyList<String>(), PartialFileSweeper.sweep(missing) { false })
    }

    @Test
    fun `directories are never deleted even when they look partial`() = runBlocking {
        val dir = temp.newFolder("packages")
        val nested = File(dir, "region-E.tiles.part").apply { mkdirs() }

        assertEquals(emptyList<String>(), PartialFileSweeper.sweep(dir) { false })
        assertTrue(nested.isDirectory)
    }
}

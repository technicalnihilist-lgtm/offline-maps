package com.offlinemaps.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The graph-directory fingerprint that lets [RoutingEngine.load] tell a second `load` for the same
 * files apart from a `load` after an install replaced them (OPTIMIZATION.md J-10).
 *
 * The two directions both matter. It has to *change* when the files are replaced - a reinstall
 * unpacks the same package over the same path, so only the timestamps differ, which is exactly the
 * case that used to be missed. And it has to *not* change when nothing was touched, because a
 * fingerprint that drifted would close and re-import a healthy graph on every `load`.
 */
class GraphFingerprintTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun graphDir(): File = temp.newFolder("graph").apply {
        File(this, "properties").writeText("graph.flag_encoders=car,bike,foot")
        File(this, "nodes").writeBytes(ByteArray(64) { 1 })
        File(this, "edges").writeBytes(ByteArray(32) { 2 })
    }

    private fun markWrittenAt(directory: File, millis: Long) {
        directory.listFiles()!!.forEach { it.setLastModified(millis) }
    }

    @Test
    fun `the same untouched directory fingerprints the same`() {
        val dir = graphDir()
        assertEquals(GraphFingerprint.of(dir), GraphFingerprint.of(dir))
    }

    @Test
    fun `a rewritten file changes the fingerprint`() {
        val dir = graphDir()
        val before = GraphFingerprint.of(dir)
        // A reinstall writes the same bytes at a later time; only the timestamp differs.
        markWrittenAt(dir, 1_000_000_000_000L)
        val after = GraphFingerprint.of(dir)
        assertNotEquals("a replacement must not look like the graph already loaded", before, after)
        assertEquals(
            "every entry should carry the new time",
            before!!.entries.size,
            after!!.entries.count { it.lastModified == 1_000_000_000_000L },
        )
    }

    @Test
    fun `a resized or added file changes the fingerprint`() {
        val dir = graphDir()
        val before = GraphFingerprint.of(dir)
        File(dir, "nodes").writeBytes(ByteArray(128) { 1 })
        markWrittenAt(dir, 1_000_000_000_000L)
        val resized = GraphFingerprint.of(dir)
        assertNotEquals(before, resized)

        File(dir, "location_index").writeBytes(ByteArray(8))
        markWrittenAt(dir, 1_000_000_000_000L)
        assertNotEquals(resized, GraphFingerprint.of(dir))
    }

    @Test
    fun `entries are ordered by name so the same files always agree`() {
        val dir = graphDir()
        val fingerprint = GraphFingerprint.of(dir)!!
        assertEquals(listOf("edges", "nodes", "properties"), fingerprint.entries.map { it.name })
        assertTrue("lengths are part of the fingerprint", fingerprint.entries.all { it.length > 0 })
    }

    @Test
    fun `a missing or empty directory has no fingerprint`() {
        assertNull(GraphFingerprint.of(File(temp.root, "nothing-here")))
        val empty = temp.newFolder("empty")
        assertNull("an empty directory is not a graph", GraphFingerprint.of(empty))
        val file = temp.newFile("not-a-directory")
        assertNull(GraphFingerprint.of(file))
    }

    @Test
    fun `a fingerprint does not match the one of another directory`() {
        val first = graphDir()
        val second = File(temp.newFolder("other"), "graph").apply { mkdirs() }
        first.listFiles()!!.forEach { it.copyTo(File(second, it.name), overwrite = true) }
        markWrittenAt(first, 1_000_000_000_000L)
        markWrittenAt(second, 1_000_000_000_000L)
        assertEquals("identical names, sizes and times are the same fingerprint", GraphFingerprint.of(first), GraphFingerprint.of(second))
        File(second, "edges").writeBytes(ByteArray(32) { 9 })
        assertFalse(GraphFingerprint.of(first) == GraphFingerprint.of(second))
    }
}

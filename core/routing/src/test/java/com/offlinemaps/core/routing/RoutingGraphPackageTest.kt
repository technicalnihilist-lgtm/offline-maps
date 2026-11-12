package com.offlinemaps.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * Pins the checks in [RoutingGraphPackage] and the sentences a rejected routing package produces
 * (OPTIMIZATION.md I-05).
 *
 * The fixture `routing_andorra.zip` is the graph shipped in `app/src/main/assets`, so the "a real
 * package passes" case is checked against the bytes the app actually installs, not against a
 * hand-written stub.
 */
class RoutingGraphPackageTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A directory holding the seven files of a real GraphHopper 2.4 graph, unpacked. */
    private fun unpackRealGraph(destination: File = File(temp.root, "graph")): File {
        destination.mkdirs()
        val zip = temp.newFile("routing_andorra.zip")
        javaClass.classLoader!!.getResourceAsStream("routing_andorra.zip")!!.use { input ->
            zip.outputStream().use { output -> input.copyTo(output) }
        }
        ZipFile(zip).use { archive ->
            archive.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                val target = File(destination, entry.name)
                archive.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return destination
    }

    /** A directory with the required file names present but no graph behind them. */
    private fun fakeGraph(
        properties: String? = "graph.flag_encoders=car|speed_factor=5.0,bike|version=2,foot|version=5\n",
    ): File {
        val dir = File(temp.root, "fake-${System.nanoTime()}").apply { mkdirs() }
        for (name in RoutingGraphPackage.REQUIRED_FILES) {
            val file = File(dir, name)
            when (name) {
                "properties" -> if (properties == null) continue else file.writeText(properties)
                else -> file.writeText("placeholder")
            }
        }
        return dir
    }

    private fun problemOf(dir: File): RoutingGraphPackage.Problem {
        val verdict = RoutingGraphPackage.inspect(dir)
        assertTrue("expected $dir to be rejected, got $verdict", verdict is RoutingGraphPackage.Verdict.Unusable)
        return (verdict as RoutingGraphPackage.Verdict.Unusable).problem
    }

    @Test
    fun `the graph the app ships is usable`() {
        assertEquals(RoutingGraphPackage.Verdict.Usable, RoutingGraphPackage.inspect(unpackRealGraph()))
    }

    @Test
    fun `a directory that was never unpacked is reported as missing`() {
        assertEquals(
            RoutingGraphPackage.Problem.MISSING_DIRECTORY,
            problemOf(File(temp.root, "nothing-here")),
        )
    }

    @Test
    fun `a package with no properties file is incomplete`() {
        val dir = fakeGraph()
        assertTrue(File(dir, "properties").delete())
        assertEquals(RoutingGraphPackage.Problem.MISSING_FILE, problemOf(dir))
    }

    @Test
    fun `a package missing one graph file is incomplete`() {
        val dir = fakeGraph()
        assertTrue(File(dir, "nodes").delete())
        assertEquals(RoutingGraphPackage.Problem.MISSING_FILE, problemOf(dir))
    }

    @Test
    fun `a half-written graph file is reported as empty`() {
        val dir = fakeGraph()
        File(dir, "edges").writeText("")
        assertEquals(RoutingGraphPackage.Problem.EMPTY_FILE, problemOf(dir))
    }

    @Test
    fun `a directory of unrelated files is not a graph`() {
        // The shape a re-zipped tile archive has: it unzips, but holds nothing GraphHopper reads.
        val dir = File(temp.root, "junk").apply { mkdirs() }
        File(dir, "readme.txt").writeText("not a routing graph")
        assertEquals(RoutingGraphPackage.Problem.MISSING_FILE, problemOf(dir))
    }

    @Test
    fun `a properties file without encoders is not a graph`() {
        assertEquals(
            RoutingGraphPackage.Problem.NO_ENCODERS,
            problemOf(fakeGraph(properties = "graph.byte_order=LITTLE_ENDIAN\nnodes.version=5\n")),
        )
    }

    @Test
    fun `a graph built for another vehicle set is rejected`() {
        // Built with car only: loading it as car,bike,foot is what used to reach GraphHopper and come
        // back as "Encoders do not match ...".
        val dir = fakeGraph(properties = "graph.flag_encoders=car|speed_factor=5.0|version=2\n")
        assertEquals(RoutingGraphPackage.Problem.WRONG_FLAGS, problemOf(dir))
    }

    @Test
    fun `encoder order and parameters do not matter`() {
        val dir = fakeGraph(
            properties = "graph.flag_encoders=foot|speed_factor=1.0|version=5," +
                "car|speed_factor=5.0|version=2,bike|version=2\n",
        )
        assertEquals(RoutingGraphPackage.Verdict.Usable, RoutingGraphPackage.inspect(dir))
    }

    @Test
    fun `the key is found behind the binary header StorableProperties writes`() {
        // Real graph files start with a binary header, so the key does not sit at the start of a line.
        val dir = fakeGraph(
            properties = "\u0000\u0002GH\u0000\u0000\u0000\u0000graph.encoded_values=road_class|version=1\n" +
                "graph.flag_encoders=car,bike,foot\n",
        )
        assertEquals(RoutingGraphPackage.Verdict.Usable, RoutingGraphPackage.inspect(dir))
        assertEquals(
            setOf("car", "bike", "foot"),
            RoutingGraphPackage.encodersIn(File(dir, "properties")),
        )
    }

    @Test
    fun `encodersIn reports nothing for a file it cannot read a key from`() {
        assertNull(RoutingGraphPackage.encodersIn(File(temp.root, "absent")))
        val blank = temp.newFile("blank-properties").apply { writeText("") }
        assertNull(RoutingGraphPackage.encodersIn(blank))
        assertNotNull(RoutingGraphPackage.encodersIn(File(unpackRealGraph(), "properties")))
    }

    @Test
    fun `every rejection has its own actionable sentence`() {
        val sentences = RoutingGraphPackage.Problem.entries.map { RoutingGraphPackage.describe(it) }
        assertEquals("one sentence per problem", RoutingGraphPackage.Problem.entries.size, sentences.toSet().size)
        for ((problem, sentence) in RoutingGraphPackage.Problem.entries.zip(sentences)) {
            assertTrue("$problem has no sentence", sentence.isNotBlank())
            // What a user must never see in place of a message.
            assertFalse("$problem leaks a class name", sentence.contains("Exception"))
            assertFalse("$problem leaks an encoder list", sentence.contains("flag_encoders"))
            assertFalse("$problem leaks a file path", sentence.contains("/"))
        }
        assertTrue(
            "a wrong-vehicle package should say so",
            RoutingGraphPackage.describe(RoutingGraphPackage.Problem.WRONG_FLAGS).contains("vehicle"),
        )
        assertTrue(
            "an incomplete package should say what to do",
            RoutingGraphPackage.describe(RoutingGraphPackage.Problem.MISSING_FILE).contains("download it again"),
        )
    }

    @Test
    fun `the engine rejects a non-graph package with the same sentence`() {
        val dir = File(temp.root, "junk-engine").apply { mkdirs() }
        File(dir, "readme.txt").writeText("not a routing graph")
        val engine = RoutingEngine(dir)

        val result = engine.load()

        assertTrue("expected a failure, got $result", result.isFailure)
        assertEquals(
            RoutingGraphPackage.describe(RoutingGraphPackage.Problem.MISSING_FILE),
            result.exceptionOrNull()?.message,
        )
        assertFalse(engine.isLoaded)
        // Counted as an attempt, so a broken package is not retried on every route.
        assertEquals(1, engine.loadAttempts)
    }

    @Test
    fun `the engine rejects a graph built for another vehicle set before loading it`() {
        val dir = fakeGraph(properties = "graph.flag_encoders=car|version=2\n")
        val engine = RoutingEngine(dir)

        val result = engine.load()

        assertTrue("expected a failure, got $result", result.isFailure)
        assertEquals(
            RoutingGraphPackage.describe(RoutingGraphPackage.Problem.WRONG_FLAGS),
            result.exceptionOrNull()?.message,
        )
        assertFalse(engine.isLoaded)
        assertEquals(1, engine.loadAttempts)
    }
}

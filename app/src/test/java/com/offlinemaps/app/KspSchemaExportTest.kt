package com.offlinemaps.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two variant KSP tasks in `:core:data` cannot export Room's schema at the same time
 * (OPTIMIZATION.md J-09).
 *
 * `kspDebugKotlin` and `kspReleaseKotlin` both write `AppDatabase` into `core/data/schemas`, and
 * `org.gradle.parallel=true` runs them concurrently: one task reads `1.json` while the other
 * rewrites it, and the build fails with `IllegalStateException: Empty schema file`. It hit twice
 * (2026-11-03 and 2026-11-12) and both times a rerun was green, which is exactly how a race hides -
 * a green rerun looks like an infrastructure hiccup, not a defect, and the next run's red build is
 * read the same way.
 *
 * The fix is one ordering constraint in the build script, and nothing in the app or in CI would
 * notice if it were deleted: the build stays green until the two tasks happen to collide again. So
 * it is held here - the real build script must order the two tasks against each other, and the
 * scanner must report a script that does not, otherwise a checker that reports nothing could pass.
 */
class KspSchemaExportTest {

    @Test
    fun `the two variant KSP tasks cannot export the Room schema at the same time`() {
        val script = dataBuildScript()
        val text = script.readText()

        assertEquals(
            "the variant KSP tasks in ${script.path} are not ordered against each other, so " +
                "kspDebugKotlin and kspReleaseKotlin can rewrite core/data/schemas at the same " +
                "time (OPTIMIZATION.md J-09):\n" +
                unordered(text).joinToString("\n"),
            0,
            unordered(text).size,
        )
    }

    @Test
    fun `the build still exports the Room schema into a shared directory`() {
        // The ordering above is only the right fix while both variants share one export directory.
        // If the directory is ever split per variant this test should be replaced, not deleted.
        val text = dataBuildScript().readText()
        assertTrue(
            "${dataBuildScript().path} no longer sets room.schemaLocation, so there is no shared " +
                "schema export for the ordering to protect (OPTIMIZATION.md J-09)",
            text.contains("room.schemaLocation"),
        )
    }

    // ---- the scanner must bite ---------------------------------------------------------------

    @Test
    fun `a build that exports the schema without ordering the variants is reported`() {
        val script = """
            ksp { arg("room.schemaLocation", "${'$'}projectDir/schemas") }
            dependencies { ksp(libs.androidx.room.compiler) }
        """.trimIndent()

        assertEquals(
            listOf(
                "kspDebugKotlin and kspReleaseKotlin both export the Room schema and are not " +
                    "ordered against each other",
            ),
            unordered(script),
        )
    }

    @Test
    fun `a build that orders the two variants reports nothing`() {
        val script = """
            ksp { arg("room.schemaLocation", "${'$'}projectDir/schemas") }
            tasks.matching { it.name == "kspReleaseKotlin" }.configureEach {
                mustRunAfter(tasks.matching { it.name == "kspDebugKotlin" })
            }
        """.trimIndent()

        assertEquals(emptyList<String>(), unordered(script))
    }

    @Test
    fun `an ordering that is commented out does not count`() {
        val script = """
            ksp { arg("room.schemaLocation", "${'$'}projectDir/schemas") }
            // tasks.matching { it.name == "kspReleaseKotlin" }.configureEach {
            //     mustRunAfter(tasks.matching { it.name == "kspDebugKotlin" })
            // }
        """.trimIndent()

        assertEquals(
            listOf(
                "kspDebugKotlin and kspReleaseKotlin both export the Room schema and are not " +
                    "ordered against each other",
            ),
            unordered(script),
        )
    }

    // ---- the scanner -------------------------------------------------------------------------

    /**
     * Every `mustRunAfter` edge the script declares, as (subject task, task it waits for). The
     * subject is the task the enclosing `tasks.matching { it.name == "..." }` / `tasks.named("...")`
     * selects, and comments are stripped first so a commented-out constraint is not a constraint.
     */
    private fun orderingEdges(text: String): Set<Pair<String, String>> {
        val edges = mutableSetOf<Pair<String, String>>()
        var subject: String? = null
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore("//").trim()
            if (line.contains("mustRunAfter")) {
                // The subject is whatever `tasks.matching`/`tasks.named` selects on this line, or,
                // for the usual two-line form, the selection on the line above. Only the argument
                // side is scanned, so `tasks.named("a") { mustRunAfter("b") }` reads as a -> b.
                val from = SUBJECT.find(line.substringBefore("mustRunAfter"))
                    ?.groupValues
                    ?.get(1)
                    ?: subject
                if (from != null) {
                    TASK_NAME.findAll(line.substringAfter("mustRunAfter"))
                        .forEach { edges += from to it.groupValues[1] }
                }
            } else {
                SUBJECT.find(line)?.let { subject = it.groupValues[1] }
            }
        }
        return edges
    }

    private fun unordered(text: String): List<String> {
        val edges = orderingEdges(text)
        val ordered = edges.any { (from, to) ->
            (from == DEBUG_TASK && to == RELEASE_TASK) || (from == RELEASE_TASK && to == DEBUG_TASK)
        }
        return if (ordered) {
            emptyList()
        } else {
            listOf("$DEBUG_TASK and $RELEASE_TASK both export the Room schema and are not ordered against each other")
        }
    }

    private fun dataBuildScript(): File {
        val file = File(repoRoot(), "core/data/build.gradle.kts")
        assertTrue("no build script at ${file.path}", file.isFile)
        return file
    }

    private fun repoRoot(): File {
        val starts = listOfNotNull(
            File("").absoluteFile,
            runCatching {
                val location = javaClass.protectionDomain?.codeSource?.location
                    ?: return@runCatching null
                File(location.toURI()).absoluteFile
            }.getOrNull(),
        )
        for (start in starts) {
            var dir: File? = start
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile && File(dir, "app/build.gradle.kts").isFile) {
                    return dir
                }
                dir = dir.parentFile
            }
        }
        throw IllegalStateException("repo root not found from $starts")
    }

    private companion object {
        /** The two tasks that export `AppDatabase` into `core/data/schemas`. */
        const val DEBUG_TASK = "kspDebugKotlin"
        const val RELEASE_TASK = "kspReleaseKotlin"

        /** The task a following `mustRunAfter` belongs to. */
        val SUBJECT = Regex("""tasks\.(?:matching\s*\{\s*it\.name\s*==\s*|named\()\s*"([A-Za-z0-9]+)"""")

        /** A quoted task name on the argument side of `mustRunAfter`. */
        val TASK_NAME = Regex("""["']([A-Za-z][A-Za-z0-9]*)["']""")
    }
}

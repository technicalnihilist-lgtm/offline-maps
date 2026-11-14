package com.offlinemaps.app

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `README.md` and `docs/` are read before the code by anyone deciding whether to trust this project,
 * so the numbers in them have to be the numbers in the tree (OPTIMIZATION.md J-06).
 *
 * The four claims checked here are the ones that had already drifted:
 *
 *  * **Test totals.** Both `README.md` and `docs/TESTING.md` said "99 tests, 0 failures" while the
 *    tree held several hundred, and `docs/TESTING.md`'s own table added up to a third number.
 *  * **Asset paths.** The data-sources table named `assets/sample_andorra.osm.pbf`, which is not
 *    shipped; the bundled graph is `assets/routing_andorra.zip`.
 *  * **The GraphHopper version.** `README.md` said "GraphHopper 10" in two places and 2.4 in two
 *    others; the build resolves one of them.
 *  * **The region count.** The catalogue ships sixteen regions; the sentence that lists them named
 *    fifteen.
 *
 * Prose is left to review. These are the claims that can be checked mechanically, and they are the
 * ones that go stale silently because nothing compiles them. A stated total is compared with the
 * number of `@Test` methods in the tree, so a commit that adds tests has to update the two totals -
 * that is the point.
 */
class DocsConsistencyTest {

    @Test
    fun `documented test totals match the tests in the tree`() {
        val root = repoRoot()
        val counted = countTests(root)
        // A scan that silently found nothing would pass while checking nothing at all.
        assertTrue(
            "only $counted tests found under $root - the scan is not seeing the tree",
            counted > 500,
        )

        val problems = totalDocs(root).flatMap { doc ->
            staleTotals(doc.readText(), counted).map { "${doc.name}: $it" }
        }
        assertEquals(
            "test totals that no longer match the tree (OPTIMIZATION.md J-06):\n" +
                problems.joinToString("\n"),
            0,
            problems.size,
        )
    }

    @Test
    fun `every asset path the docs name is shipped`() {
        val root = repoRoot()
        val shipped = File(root, ASSETS).listFiles().orEmpty().map { it.name }.toSet()
        assertTrue("no assets found under $ASSETS - the scan is not seeing the tree", shipped.size >= 3)

        val named = docFiles(root).sumOf { referencedAssets(it.readText()).size }
        assertTrue("the docs name no bundled asset at all - the scan is not reading them", named > 0)

        val problems = docFiles(root).flatMap { doc ->
            missingAssets(doc.readText(), shipped).map { "${doc.relativeTo(root).path}: assets/$it" }
        }
        assertEquals(
            "asset paths the docs name but the app does not ship (OPTIMIZATION.md J-06):\n" +
                problems.joinToString("\n"),
            0,
            problems.size,
        )
    }

    @Test
    fun `the documented GraphHopper version is the one the build resolves`() {
        val root = repoRoot()
        val resolved = Regex("(?m)^\\s*graphhopper\\s*=\\s*\"([^\"]+)\"")
            .find(File(root, "gradle/libs.versions.toml").readText())
            ?.groupValues
            ?.get(1)
        assertTrue("no graphhopper version in gradle/libs.versions.toml", resolved != null)

        val stated = versionedDocs(root).sumOf { statedVersions(it.readText()).size }
        assertTrue("the docs name no GraphHopper version at all - the scan is not reading them", stated > 0)

        val problems = versionedDocs(root).flatMap { doc ->
            statedVersions(doc.readText())
                .filter { it != resolved }
                .map {
                    "${doc.relativeTo(root).path}: says GraphHopper $it, the build resolves $resolved"
                }
        }
        assertEquals(
            "documented GraphHopper versions that the build does not resolve (OPTIMIZATION.md J-06):\n" +
                problems.joinToString("\n"),
            0,
            problems.size,
        )
    }

    @Test
    fun `the documented region count is the catalogue's own`() {
        val root = repoRoot()
        val catalogue = File(root, "$ASSETS/regions.json").readText()
        val regions = Json.parseToJsonElement(catalogue).jsonObject["regions"]?.jsonArray?.size ?: 0
        assertTrue("regions.json parsed to $regions regions - the scan is not reading it", regions > 0)

        val readme = File(root, "README.md").readText()
        val stated = NUMBER_WORDS.indexOfFirst { word ->
            Regex("""\b$word\s+regions\b""", RegexOption.IGNORE_CASE).containsMatchIn(readme)
        }
        assertEquals(
            "README.md's region count is not the catalogue's $regions",
            regions,
            stated,
        )
    }

    // ---- the scanners must bite -------------------------------------------------------------

    @Test
    fun `a stale test total is reported`() {
        assertEquals(
            listOf("says 99 tests, the tree holds 645"),
            staleTotals("Total: **99 tests, 0 failures**, executed on GitHub Actions.", 645),
        )
    }

    @Test
    fun `a doc that states no test total is reported`() {
        assertEquals(listOf("states no test total"), staleTotals("no numbers here", 645))
    }

    @Test
    fun `an asset path that is not shipped is reported`() {
        assertEquals(
            listOf("sample_andorra.osm.pbf"),
            missingAssets("`assets/sample_andorra.osm.pbf`", setOf("routing_andorra.zip")),
        )
    }

    // ---- the scanners -----------------------------------------------------------------------

    private fun staleTotals(text: String, counted: Int): List<String> {
        val stated = STATED_TOTAL.findAll(text).map { it.groupValues[1].replace(",", "").toInt() }.toList()
        if (stated.isEmpty()) return listOf("states no test total")
        return stated.filter { it != counted }.map { "says $it tests, the tree holds $counted" }
    }

    private fun referencedAssets(text: String): List<String> =
        ASSET_PATH.findAll(text).map { it.groupValues[1] }.toList()

    private fun missingAssets(text: String, shipped: Set<String>): List<String> =
        referencedAssets(text).filter { it !in shipped }.distinct()

    private fun statedVersions(text: String): List<String> =
        GRAPHHOPPER_VERSION.findAll(text).map { it.groupValues[1] }.toList()

    private fun countTests(root: File): Int = testSources(root).sumOf { file ->
        TEST_METHOD.findAll(file.readText()).count()
    }

    private fun testSources(root: File): List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" }
        .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/test/") }
        .toList()

    /** The two files that state a suite total; both are quoted by hand in the README. */
    private fun totalDocs(root: File): List<File> =
        listOf(File(root, "README.md"), File(root, "docs/TESTING.md")).filter { it.isFile }

    /** Every markdown file under `docs/` plus the README. */
    private fun docFiles(root: File): List<File> =
        listOf(File(root, "README.md")) + File(root, "docs").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "md" }
            .sortedBy { it.name }

    /**
     * The docs whose GraphHopper version is a statement about this build. `CRASH-FIX-v1.0.1.md` is
     * excluded: it is a historical write-up, and "GraphHopper 4+ builds its weighting through janino"
     * is a fact about that project's history, not about the version pinned here.
     */
    private fun versionedDocs(root: File): List<File> =
        docFiles(root).filter { it.name != "CRASH-FIX-v1.0.1.md" }

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
        const val ASSETS = "app/src/main/assets"

        /** A test method, as the JUnit runner sees one. */
        val TEST_METHOD = Regex("""(?m)^\s*@Test\b""")

        /** "99 tests, 0 failures" - the shape both docs use for a suite total. */
        val STATED_TOTAL = Regex("""(\d[\d,]*)\s+tests,\s*0\s+failures""")

        /** A bundled asset, however the docs spell the path around it. */
        val ASSET_PATH = Regex("""(?:app/src/main/)?assets/([A-Za-z0-9_.-]+)""")

        /** "GraphHopper 2.4", "GraphHopper 10" - a version the docs claim the build uses. */
        val GRAPHHOPPER_VERSION = Regex("""GraphHopper\s+(\d+(?:\.\d+)*)""")

        /** Region counts are written in words; the index is the number. */
        val NUMBER_WORDS = listOf(
            "no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty",
        )
    }
}

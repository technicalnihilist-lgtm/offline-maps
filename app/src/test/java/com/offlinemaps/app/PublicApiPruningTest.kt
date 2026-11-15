package com.offlinemaps.app

import com.offlinemaps.core.offline.DownloadProgress
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six declarations J-04 found unused and J-13 decided on (OPTIMIZATION.md): each one was a name
 * that appeared exactly once in the tree, at its own declaration, and each decision was to delete
 * it rather than keep an entry point nothing calls.
 *
 *  * `LoadingOverlay` and `defaultTheme()` in `core:ui` - a composable no screen renders and a
 *    theme resolver the app never consults (`MainActivity` takes the theme from settings, which
 *    defaults to light), so both were UI surface that could not be reached.
 *  * `PermissionRationale.BACKGROUND_TITLE` / `BACKGROUND_BODY` / `STORAGE_BODY` - copy for a
 *    rationale the app does not show. The location dialog keeps the two constants it uses.
 *  * `DownloadProgress.etaSeconds` - written on every progress tick and read by nobody, so the
 *    engine was computing an ETA for a field no screen displays.
 *
 * Deleting them changes nothing a user can see; this test is what keeps the tree that way, because
 * the compiler is silent about an unused public declaration and the next reader would have to
 * rediscover the evidence. A name that comes back has to come back with a caller.
 *
 * Two kinds of test, the same shape as `UnusedImportAuditTest`: the real tree must be clean, and
 * the scanners themselves must bite.
 */
class PublicApiPruningTest {

    @Test
    fun `the declarations J-13 decided on are gone from main sources`() {
        val root = repoRoot()
        val sources = mainSources(root)
        // A scan that silently found nothing would pass while checking nothing at all.
        assertTrue(
            "only ${sources.size} main sources found under $root - the scan is not seeing the tree",
            sources.size > 50,
        )

        val reintroduced = sources.flatMap { file ->
            deadNames(file.readText()).map { "${file.relativeTo(root).path}: $it" }
        }
        assertEquals(
            "declarations J-13 deleted that are back with no caller (OPTIMIZATION.md J-13):\n" +
                reintroduced.joinToString("\n"),
            0,
            reintroduced.size,
        )
    }

    @Test
    fun `DownloadProgress carries the four fields the screens read`() {
        val fields = DownloadProgress::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(
            "DownloadProgress gained or lost a component; an ETA field came back with no reader " +
                "(OPTIMIZATION.md J-13)",
            setOf("packageKey", "bytesDownloaded", "bytesTotal", "bytesPerSecond"),
            fields,
        )
    }

    @Test
    fun `every PermissionRationale constant has a caller outside its own file`() {
        val root = repoRoot()
        val declaration = File(
            root,
            "core/location/src/main/java/com/offlinemaps/core/location/PermissionRationale.kt",
        )
        assertTrue("PermissionRationale.kt is missing from the tree", declaration.isFile)

        val constants = constantNames(declaration.readText())
        assertTrue(
            "no constants found in PermissionRationale.kt - the scan is not reading it",
            constants.size >= 2,
        )

        val others = mainSources(root).filter { it != declaration }
        val orphans = constants.filter { name ->
            others.none { file -> Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(file.readText()) }
        }
        assertEquals(
            "permission copy with no screen showing it (OPTIMIZATION.md J-13):\n" +
                orphans.joinToString("\n"),
            0,
            orphans.size,
        )
    }

    // ---- the scanners must bite -------------------------------------------------------------

    @Test
    fun `a declaration that comes back is reported`() {
        val source = "package p\n\n@Composable\nfun LoadingOverlay(visible: Boolean) = Unit\n"
        assertEquals(listOf("LoadingOverlay"), deadNames(source))
    }

    @Test
    fun `the constant scan reads declarations and not prose`() {
        val source = "object P {\n    const val A = \"a\"\n    // const val B = \"b\"\n}\n"
        assertEquals(listOf("A"), constantNames(source))
    }

    // ---- the scanners -----------------------------------------------------------------------

    /** Names J-13 deleted, reported when they appear anywhere in a source, comments included. */
    private fun deadNames(source: String): List<String> =
        DELETED.filter { name -> Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(source) }

    /** The `const val` names an object declares, ignoring commented-out lines. */
    private fun constantNames(source: String): List<String> = source.lineSequence()
        .map { it.substringBefore("//") }
        .flatMap { line -> CONSTANT.findAll(line).map { it.groupValues[1] } }
        .toList()

    private fun mainSources(root: File): List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" }
        .filter {
            it.isFile &&
                it.extension == "kt" &&
                it.invariantSeparatorsPath.contains("/src/main/")
        }
        .toList()

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
        /** What J-13 decided to delete. */
        val DELETED = listOf(
            "LoadingOverlay",
            "defaultTheme",
            "BACKGROUND_TITLE",
            "BACKGROUND_BODY",
            "STORAGE_BODY",
            "etaSeconds",
        )

        val CONSTANT = Regex("""^\s*const\s+val\s+([A-Za-z_][A-Za-z0-9_]*)""")
    }
}

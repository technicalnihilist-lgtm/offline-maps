package com.offlinemaps.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest used to declare `WAKE_LOCK` and nothing in the tree acquired one (OPTIMIZATION.md
 * J-14): the screen-off guidance case is carried by the location foreground service, so the
 * declaration bought nothing and showed up as an unexplained permission in the store listing.
 *
 * Removing the line is easy to undo by accident, and a permission is the kind of thing that goes
 * stale silently - nothing compiles the README's table. So the removal is held in place by three
 * checks: no `WAKE_LOCK` in the manifest, no wake lock acquired by any main source, and the README
 * permission table and the manifest naming exactly the same set, in both directions. The scanners
 * are exercised on synthetic input below, so one that reports nothing cannot pass as clean.
 */
class PermissionDeclarationTest {

    @Test
    fun `the manifest declares no wake lock permission`() {
        val declared = declaredPermissions(repoRoot())
        // A scan that found nothing would satisfy the assertion below while checking nothing.
        assertTrue(
            "the manifest declares no permissions at all - the scan is not reading it: $declared",
            declared.size >= 5,
        )
        assertTrue(
            "WAKE_LOCK is declared again, and no code acquires one (OPTIMIZATION.md J-14): $declared",
            "WAKE_LOCK" !in declared,
        )
    }

    @Test
    fun `no main source acquires a wake lock`() {
        val root = repoRoot()
        val sources = mainSources(root)
        assertTrue("no main sources found under $root - the scan is not seeing the tree", sources.size > 50)

        val acquisitions = wakeLockAcquisitions(root)
        assertEquals(
            "wake locks acquired with no WAKE_LOCK declared (OPTIMIZATION.md J-14):\n" +
                acquisitions.joinToString("\n"),
            0,
            acquisitions.size,
        )
    }

    @Test
    fun `the README permission table and the manifest agree`() {
        val root = repoRoot()
        val declared = declaredPermissions(root)
        val documented = documentedPermissions(root)
        assertTrue(
            "the README names no permission at all - the scan is not reading the table: $documented",
            documented.size >= 5,
        )

        assertEquals(
            "permissions the manifest declares that the README table does not list",
            emptySet<String>(),
            missingFromTable(declared, documented),
        )
        assertEquals(
            "permissions the README table lists that the manifest does not declare",
            emptySet<String>(),
            notDeclared(declared, documented),
        )
    }

    // ---- the scanners must bite -------------------------------------------------------------

    @Test
    fun `a wake lock acquisition is reported`() {
        assertEquals(
            listOf("newWakeLock", "PowerManager"),
            wakeLockUses("val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)"),
        )
    }

    @Test
    fun `a comment that mentions the wake lock API is not an acquisition`() {
        assertEquals(
            emptyList<String>(),
            wakeLockUses(
                """
                /** The app holds no wake lock: there is no `PowerManager` call anywhere in it. */
                // and none in the newWakeLock sense either
                val tag = "PowerManager" // only a string, not a call
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a permission the manifest declares but the table omits is reported`() {
        assertEquals(
            setOf("POST_NOTIFICATIONS"),
            missingFromTable(setOf("INTERNET", "POST_NOTIFICATIONS"), setOf("INTERNET")),
        )
    }

    @Test
    fun `a README row for a permission the manifest does not declare is reported`() {
        assertEquals(
            setOf("WAKE_LOCK"),
            notDeclared(setOf("INTERNET"), setOf("INTERNET", "WAKE_LOCK")),
        )
    }

    @Test
    fun `the table scan reads permission names and not prose`() {
        assertEquals(
            setOf("INTERNET", "ACCESS_NETWORK_STATE"),
            documentedIn("| `INTERNET`, `ACCESS_NETWORK_STATE` | optional downloads | always declared |"),
        )
    }

    // ---- the scanners -----------------------------------------------------------------------

    /** Every permission the main manifests declare, without the `android.permission.` prefix. */
    private fun declaredPermissions(root: File): Set<String> =
        mainManifests(root).flatMap { declaredIn(it.readText()) }.toSet()

    private fun mainManifests(root: File): List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" }
        .filter {
            it.isFile &&
                it.name == "AndroidManifest.xml" &&
                it.invariantSeparatorsPath.contains("/src/main/")
        }
        .toList()

    private fun declaredIn(manifest: String): List<String> =
        DECLARED_PERMISSION.findAll(manifest).map { it.groupValues[1] }.toList()

    /** The permission names in the README's `## Permissions` table, in either cell order. */
    private fun documentedPermissions(root: File): Set<String> =
        documentedIn(File(root, "README.md").readText().substringAfter("## Permissions", ""))

    private fun missingFromTable(declared: Set<String>, documented: Set<String>): Set<String> =
        declared - documented

    private fun notDeclared(declared: Set<String>, documented: Set<String>): Set<String> =
        documented - declared

    private fun documentedIn(text: String): Set<String> = text.lineSequence()
        .filter { it.trimStart().startsWith("|") }
        .flatMap { line -> DOCUMENTED_PERMISSION.findAll(line).map { it.groupValues[1] } }
        .toSet()

    /**
     * Wake lock API use in main sources, as `path: line` - or the API name alone when the input is a
     * snippet rather than a file. Comments are stripped first: the code base explains *that* it
     * holds no wake lock, and that prose is not an acquisition.
     */
    private fun wakeLockAcquisitions(root: File): List<String> = mainSources(root).flatMap { file ->
        wakeLockUses(file.readText()).map { "${file.relativeTo(root).path}: $it" }
    }

    private fun wakeLockUses(source: String): List<String> =
        WAKE_LOCK_API.findAll(codeOnly(source)).map { it.value }.distinct().toList()

    private fun mainSources(root: File): List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" }
        .filter {
            it.isFile &&
                (it.extension == "kt" || it.extension == "java") &&
                it.invariantSeparatorsPath.contains("/src/main/")
        }
        .toList()

    /**
     * The source with comments dropped and string contents blanked, so a doc comment that names
     * `PowerManager` and a string that spells it are neither of them a wake lock.
     */
    private fun codeOnly(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var inBlock = false
        var inString = false
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                inBlock -> {
                    if (c == '*' && next == '/') {
                        inBlock = false
                        i++
                    } else if (c == '\n') {
                        out.append('\n')
                    }
                    i++
                }

                inString -> {
                    if (c == '\\') {
                        // The escaped character is string content too: skip it without copying.
                        i++
                    } else if (c == '"') {
                        inString = false
                        out.append(c)
                    }
                    i++
                }

                c == '/' && next == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }

                c == '/' && next == '*' -> {
                    inBlock = true
                    i += 2
                }

                c == '"' -> {
                    inString = true
                    out.append(c)
                    i++
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
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
        /** `<uses-permission android:name="android.permission.INTERNET" />` */
        val DECLARED_PERMISSION =
            Regex("""<uses-permission[^>]*android:name="android\.permission\.([A-Z][A-Z0-9_]*)"""")

        /** A permission as the README writes it: in backticks, all caps, no dots. */
        val DOCUMENTED_PERMISSION = Regex("""`([A-Z][A-Z0-9_]{2,})`""")

        /** Any use of the wake lock API, which needs `WAKE_LOCK` to be declared. */
        val WAKE_LOCK_API = Regex("""\bPowerManager\b|\bnewWakeLock\b""")
    }
}

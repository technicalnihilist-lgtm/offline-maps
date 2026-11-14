package com.offlinemaps.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CI workflow cannot report a green run while a test failed or a module's tests never ran
 * (OPTIMIZATION.md J-07).
 *
 * `./gradlew test` itself is loud about a failing test - Gradle exits non-zero and the step fails -
 * so the risk is not the runner, it is the workflow file drifting away from that guarantee:
 *
 *  * **A masked failure.** `continue-on-error: true` or a `|| true` on the test command turns a red
 *    suite into a green job, and the release that follows it ships anyway.
 *  * **A silent skip.** Gradle is happy when a module's test task runs nothing, so the run has to be
 *    checked against the module list rather than trusted. `tools/verify_test_results.sh` does that,
 *    and it must be wired into the workflow - a guard nobody runs is not a guard.
 *  * **A hardcoded module list.** The guard reads `settings.gradle.kts`, so a module added tomorrow
 *    is covered without editing the guard; a copy of the module names inside the script would go
 *    stale the same way a skipped module does.
 *
 * Same shape as the other static audits in this package: the real tree must pass, and the scanner
 * must bite, so a checker that never reports anything cannot pass either.
 */
class CiWorkflowTest {

    @Test
    fun `the workflow runs the tests without masking a failure`() {
        val workflow = workflow()
        val text = workflow.readText()

        assertTrue(
            "${workflow.name} no longer runs ./gradlew test",
            text.contains("./gradlew test"),
        )
        assertEquals(
            "a masked failure in ${workflow.name} (OPTIMIZATION.md J-07):\n" +
                masking(workflow.readText()).joinToString("\n"),
            0,
            masking(text).size,
        )
    }

    @Test
    fun `the workflow verifies that every module's tests ran`() {
        val workflow = workflow()
        val text = workflow.readText()

        assertTrue(
            "${workflow.name} does not run tools/verify_test_results.sh, so a module whose tests " +
                "never ran would leave the job green (OPTIMIZATION.md J-07)",
            text.contains("tools/verify_test_results.sh"),
        )
        assertTrue(
            "${workflow.name} runs the guard before ./gradlew test, when there is nothing to check",
            text.indexOf("tools/verify_test_results.sh") > text.indexOf("./gradlew test"),
        )
        assertTrue(
            "tools/verify_test_results.sh is missing from the tree",
            guardScript().isFile,
        )
    }

    @Test
    fun `the guard takes its module list from settings, not from a copy`() {
        val script = guardScript().readText()
        assertTrue(
            "tools/verify_test_results.sh does not read settings.gradle.kts, so its module list " +
                "would go stale as modules are added (OPTIMIZATION.md J-07)",
            script.contains("settings.gradle.kts"),
        )
        assertTrue(
            "tools/verify_test_results.sh does not look at build/test-results, so it cannot tell " +
                "a module that ran tests from one that did not",
            script.contains("test-results"),
        )
    }

    // ---- the scanner must bite -------------------------------------------------------------

    @Test
    fun `a continue-on-error and a swallowed exit code are both reported`() {
        assertEquals(
            listOf(
                "line 3: continue-on-error: true",
                "line 4: run: ./gradlew test || true",
            ),
            masking("steps:\n  - name: Unit tests\n    continue-on-error: true\n    run: ./gradlew test || true\n"),
        )
    }

    @Test
    fun `a clean workflow reports nothing`() {
        assertEquals(emptyList<String>(), masking("steps:\n  - name: Unit tests\n    run: ./gradlew test\n"))
    }

    // ---- the scanners -----------------------------------------------------------------------

    /** Anything that would let a failing test step pass the job anyway. */
    private fun masking(text: String): List<String> {
        val found = mutableListOf<String>()
        text.lineSequence().forEachIndexed { index, line ->
            val number = index + 1
            val code = line.substringBefore('#').trimEnd()
            when {
                code.isBlank() -> Unit
                CONTINUE_ON_ERROR.containsMatchIn(code) ->
                    found += "line $number: ${code.trim()}"
                // Only a gradle command matters here: the SDK setup step deliberately tolerates a
                // failure from sdkmanager, and the build fails later if a package is really missing.
                code.contains("gradlew") && SWALLOWED_EXIT.containsMatchIn(code) ->
                    found += "line $number: ${code.trim()}"
            }
        }
        return found
    }

    private fun workflow(): File {
        val file = File(repoRoot(), ".github/workflows/android.yml")
        assertTrue("no CI workflow at ${file.path}", file.isFile)
        return file
    }

    private fun guardScript(): File = File(repoRoot(), "tools/verify_test_results.sh")

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
        /** `continue-on-error: true` - GitHub's own way to make a failed step non-fatal. */
        val CONTINUE_ON_ERROR = Regex("""continue-on-error:\s*true""")

        /** A failing command whose exit code is discarded on the same line. */
        val SWALLOWED_EXIT = Regex("""\|\|\s*(true|:|exit\s+0)\s*$""")
    }
}

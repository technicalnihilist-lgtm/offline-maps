package com.offlinemaps.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No Kotlin source in this repository may carry an import it does not use (OPTIMIZATION.md J-04).
 *
 * The compiler reports these as warnings, and a warning is what a green test run never looks at,
 * so the tree accumulated them: three had survived by hand - two `material3` navigation imports in
 * `AppScaffold` and GraphHopper's `EncodingManager` in `RoutingEngine` - and six more became unused
 * once the dead declarations in the same run were removed. This test is the guard that holds the
 * count at zero without anyone reading a warning log.
 *
 * Two kinds of test here, the same shape as the style-spec suite in `core/map`: the real tree must
 * be clean, and the scanner itself must bite - a checker that never reports anything would make
 * the first half worthless.
 *
 * What counts as a use: the import's simple name appearing anywhere in the file outside an import
 * line and outside a block comment. A KDoc link does not count, because the compiler does not
 * resolve one either - that is how `PlacesPack` kept a `GeoPoint` import that only its KDoc named.
 * Line comments are left in the body on purpose: stripping them would mean parsing string literals
 * for `//`, and a name mentioned in a line comment is a far cheaper thing to miss than a URL cut
 * in half.
 *
 * Operator imports are exempt unconditionally: `getValue`/`setValue` are resolved through `by` and
 * their names never appear in the source that uses them, which is exactly why a naive scan calls
 * them unused.
 */
class UnusedImportAuditTest {

    @Test
    fun `the repository carries no unused imports`() {
        val root = repoRoot()
        val sources = kotlinSources(root)
        // A scan that silently found nothing would pass while checking nothing at all.
        assertTrue(
            "only ${sources.size} Kotlin files found under $root - the scan is not seeing the tree",
            sources.size > 100,
        )

        val violations = sources.flatMap { file ->
            unusedImports(file.readText()).map { "${file.relativeTo(root).path}:$it" }
        }
        assertEquals(
            "unused imports (OPTIMIZATION.md J-04):\n" + violations.joinToString("\n"),
            0,
            violations.size,
        )
    }

    // ---- the scanner must bite -------------------------------------------------------------

    @Test
    fun `an import the file never uses is reported`() {
        val source = "package p\n\nimport a.b.Unused\n\nclass C\n"
        assertEquals(listOf("line 3: a.b.Unused"), unusedImports(source))
    }

    @Test
    fun `an import the file uses is accepted`() {
        val source = "package p\n\nimport a.b.Used\n\nclass C { val u = Used() }\n"
        assertTrue(unusedImports(source).isEmpty())
    }

    @Test
    fun `an import only a block comment names is reported`() {
        val source = "package p\n\nimport a.b.Used\n\n/**\n * See [Used].\n */\nclass C\n"
        assertEquals(listOf("line 3: a.b.Used"), unusedImports(source))
    }

    @Test
    fun `an import a line comment names is accepted`() {
        val source = "package p\n\nimport a.b.Used\n\n// Used once lived here.\nclass C\n"
        assertTrue(unusedImports(source).isEmpty())
    }

    @Test
    fun `a delegate operator import is not reported`() {
        val source = "package p\n\nimport androidx.compose.runtime.getValue\n\n" +
            "class C { val v by lazy { 1 } }\n"
        assertTrue(unusedImports(source).isEmpty())
    }

    @Test
    fun `an aliased import is checked under its alias`() {
        val used = "package p\n\nimport a.b.Long as Short\n\nclass C { val u = Short() }\n"
        val unused = "package p\n\nimport a.b.Long as Short\n\nclass C\n"
        assertTrue(unusedImports(used).isEmpty())
        assertEquals(listOf("line 3: a.b.Long as Short"), unusedImports(unused))
    }

    // ---- the scanner ------------------------------------------------------------------------

    private fun unusedImports(text: String): List<String> {
        val lines = text.lines()
        val body = withoutBlockComments(
            lines.filterNot { it.trim().startsWith("import ") }.joinToString("\n"),
        )
        val unused = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("import ")) return@forEachIndexed
            val spec = trimmed.removePrefix("import ").trim()
            // A wildcard import names no symbol to look for.
            if (spec.endsWith("*")) return@forEachIndexed
            val name = spec.substringAfterLast(" as ").substringAfterLast('.')
            if (name in IMPLICIT_OPERATORS) return@forEachIndexed
            if (!mentioned(body, name)) unused += "line ${index + 1}: $spec"
        }
        return unused
    }

    private fun mentioned(body: String, name: String): Boolean =
        Regex("(?<![\\w])" + Regex.escape(name) + "(?![\\w])").containsMatchIn(body)

    /** Drops block comments, whose contents the compiler does not read as a use. */
    private fun withoutBlockComments(text: String): String {
        val kept = mutableListOf<String>()
        var inComment = false
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (inComment) {
                if (trimmed.contains("*/")) inComment = false
                continue
            }
            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains("*/")) inComment = true
                continue
            }
            kept += line
        }
        return kept.joinToString("\n")
    }

    private fun repoRoot(): File {
        val starts = listOfNotNull(
            File("").absoluteFile,
            runCatching {
                File(javaClass.protectionDomain.codeSource.location.toURI()).absoluteFile
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
        throw AssertionError("no repository root above ${starts.joinToString()}")
    }

    private fun kotlinSources(root: File): List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" }
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private companion object {
        /**
         * Names the compiler resolves without the source naming them: delegate accessors and the
         * arithmetic and collection operators, all of which are called through syntax.
         */
        val IMPLICIT_OPERATORS = setOf(
            "getValue", "setValue", "provideDelegate",
            "component1", "component2", "component3", "component4", "component5",
            "invoke", "get", "set", "contains", "iterator", "next", "hasNext",
            "plus", "minus", "times", "div", "rem", "unaryMinus", "unaryPlus",
            "not", "inc", "dec", "compareTo", "rangeTo",
        )
    }
}

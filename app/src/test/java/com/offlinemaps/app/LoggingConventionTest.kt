package com.offlinemaps.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logging in main sources follows one convention (OPTIMIZATION.md J-05).
 *
 * Two rules, both about main sources only - a test's `println` is a measurement channel and is
 * left alone:
 *
 *  * **One tag per class, named for the class.** A file may declare a single log-tag constant, and
 *    its value must be a top-level declaration name in that file, so a logcat line names the file
 *    it came from. Two tags in one class means the same feature logs under two filters, which is
 *    how `ServiceLocator` grew a second tag (`SearchPrewarm`) for its search timings; a tag that
 *    is not the class name means `OfflineRegionController` logged as `OfflineRegion`.
 *  * **No stdout.** `println`, `print`, `System.out`, `System.err` and `printStackTrace` in a main
 *    source are invisible to logcat, unbuffered in release and unstrippable; a `Log` call with a
 *    literal tag is the same problem in a different shape, so it is reported too.
 *
 * Same shape as `UnusedImportAuditTest`: the real tree must be clean, and the scanner must bite,
 * so a checker that never reports anything cannot pass.
 */
class LoggingConventionTest {

    @Test
    fun `main sources log under one tag named for their class and never write to stdout`() {
        val root = repoRoot()
        val sources = mainSources(root)
        // A scan that silently found nothing would pass while checking nothing at all.
        assertTrue(
            "only ${sources.size} main Kotlin files found under $root - the scan is not seeing the tree",
            sources.size > 60,
        )

        val violations = sources.flatMap { file ->
            loggingViolations(file.readText()).map { "${file.relativeTo(root).path}:$it" }
        }
        assertEquals(
            "logging convention violations (OPTIMIZATION.md J-05):\n" + violations.joinToString("\n"),
            0,
            violations.size,
        )
    }

    // ---- the scanner must bite -------------------------------------------------------------

    @Test
    fun `a println in a main source is reported`() {
        val source = "class C {\n    fun f() { println(\"x\") }\n}\n"
        assertEquals(listOf("line 2: println( in a main source"), loggingViolations(source))
    }

    @Test
    fun `a printStackTrace and a System err write are reported`() {
        val source = "class C {\n    fun f(e: Throwable) { e.printStackTrace() }\n" +
            "    fun g() { System.err.print(\"x\") }\n}\n"
        assertEquals(2, loggingViolations(source).size)
    }

    @Test
    fun `a println inside a comment or a string is not reported`() {
        val source = "class C {\n" +
            "    // println(\"gone\") is what this used to be\n" +
            "    /* println(\"gone\") */\n" +
            "    val message = \"println(1)\"\n" +
            "}\n"
        assertTrue(loggingViolations(source).isEmpty())
    }

    @Test
    fun `two log tags in one file are reported`() {
        val source = "class C {\n" +
            "    companion object {\n" +
            "        private const val TAG = \"C\"\n" +
            "        private const val SEARCH_TAG = \"Search\"\n" +
            "    }\n}\n"
        val found = loggingViolations(source)
        // Two tags is one violation, and the second tag's value is a second one: it names no class.
        assertEquals(2, found.size)
        assertTrue(found.toString(), found.any { it.contains("2 log tags in one file") })
        assertTrue(found.toString(), found.any { it.contains("tag \"Search\" is not a class name") })
    }

    @Test
    fun `a tag that is not the class name is reported`() {
        val source = "class C {\n    companion object {\n        const val TAG = \"OfflineRegion\"\n    }\n}\n"
        val found = loggingViolations(source)
        assertEquals(1, found.size)
        assertTrue(found.single(), found.single().contains("\"OfflineRegion\" is not a class name"))
    }

    @Test
    fun `a tag named after a nested class is not accepted`() {
        val source = "class C {\n    data class Nested(val v: Int)\n" +
            "    companion object {\n        const val TAG = \"Nested\"\n    }\n}\n"
        assertEquals(1, loggingViolations(source).size)
    }

    @Test
    fun `a log call with a literal tag is reported`() {
        val source = "class C {\n    companion object {\n        const val TAG = \"C\"\n    }\n" +
            "    fun f() { Log.d(\"C\", \"x\") }\n}\n"
        val found = loggingViolations(source)
        assertEquals(1, found.size)
        assertTrue(found.single(), found.single().contains("literal tag"))
    }

    @Test
    fun `a conforming source is accepted`() {
        val source = "class C {\n" +
            "    fun f() { if (true) Log.d(TAG, \"x\") }\n" +
            "    companion object {\n        private const val TAG = \"C\"\n    }\n}\n"
        assertTrue(loggingViolations(source).joinToString("\n"), loggingViolations(source).isEmpty())
    }

    // ---- the scanner ------------------------------------------------------------------------

    private fun loggingViolations(text: String): List<String> {
        val (noComments, code) = masks(text)
        val found = mutableListOf<String>()

        // One violation per line: `System.err.print(` is both a stderr write and a print.
        val printed = sortedMapOf<Int, MutableList<String>>()
        PRINT_PATTERNS.forEach { pattern ->
            pattern.findAll(code).forEach { match ->
                printed.getOrPut(lineOf(code, match.range.first)) { mutableListOf() }
                    .add(match.value.trim())
            }
        }
        printed.forEach { (line, hits) ->
            found += "line $line: ${hits.joinToString(", ")} in a main source"
        }

        LOG_WITH_LITERAL_TAG.findAll(noComments).forEach { match ->
            found += "line ${lineOf(noComments, match.range.first)}: Log call with a literal tag - " +
                "use the class's TAG constant"
        }

        val declarations = topLevelNames(code)
        val tags = TAG_DECLARATION.findAll(noComments)
            .map { Tag(it.groupValues[1], it.groupValues[2], lineOf(noComments, it.range.first)) }
            .filter { it.name.endsWith("TAG") }
            .toList()
        if (tags.size > 1) {
            found += "line ${tags[1].line}: ${tags.size} log tags in one file - one tag per class " +
                "(${tags.joinToString { it.name }})"
        }
        tags.forEach { tag ->
            if (tag.value !in declarations) {
                found += "line ${tag.line}: tag \"${tag.value}\" is not a class name in this file (" +
                    declarations.joinToString().ifEmpty { "no top-level declaration" } + ")"
            }
        }
        return found
    }

    private data class Tag(val name: String, val value: String, val line: Int)

    /** Names declared at file level - a nested class is not what a log tag may be named after. */
    private fun topLevelNames(code: String): Set<String> {
        val depths = depths(code)
        return DECLARATION.findAll(code)
            .filter { depths[it.range.first] == 0 }
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun depths(code: String): IntArray {
        val depths = IntArray(code.length)
        var depth = 0
        code.forEachIndexed { index, c ->
            depths[index] = depth
            when (c) {
                '{' -> depth++
                '}' -> depth--
            }
        }
        return depths
    }

    /**
     * Two views of the same text, same length so offsets still point at the original line: comments
     * blanked (strings intact, so a tag's value can be read), and comments plus string contents
     * blanked (so a `println` inside either is not a hit, and the braces left are real ones).
     */
    private fun masks(text: String): Pair<String, String> {
        val noComments = StringBuilder(text)
        val code = StringBuilder(text)
        fun blank(index: Int) {
            if (text[index] != '\n') {
                noComments.setCharAt(index, ' ')
                code.setCharAt(index, ' ')
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> while (i < text.length && text[i] != '\n') blank(i++)
                c == '/' && next == '*' -> {
                    while (i < text.length && !(text[i] == '*' && text.getOrNull(i + 1) == '/')) blank(i++)
                    repeat(2) { if (i < text.length) blank(i++) }
                }
                text.startsWith("\"\"\"", i) -> {
                    var end = i + 3
                    while (end < text.length && !text.startsWith("\"\"\"", end)) end++
                    for (k in i + 1 until minOf(end, text.length)) code.setCharAt(k, ' ')
                    i = minOf(end + 3, text.length)
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    var end = i + 1
                    while (end < text.length && text[end] != quote && text[end] != '\n') {
                        if (text[end] == '\\') end++
                        end++
                    }
                    for (k in i + 1 until minOf(end, text.length)) code.setCharAt(k, ' ')
                    i = minOf(end + 1, text.length)
                }
                else -> i++
            }
        }
        return noComments.toString() to code.toString()
    }

    private fun lineOf(text: String, offset: Int): Int =
        1 + text.take(offset).count { it == '\n' }

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

    /** Main sources only: what ships, and what a test's own `println` never reaches. */
    private fun mainSources(root: File): List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" }
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.parentFile.path.replace(File.separatorChar, '/').contains("/src/main") }
        .toList()

    private companion object {
        val PRINT_PATTERNS = listOf(
            Regex("\\bprintln\\s*\\("),
            Regex("\\bprint\\s*\\("),
            Regex("\\bSystem\\.out\\b"),
            Regex("\\bSystem\\.err\\b"),
            Regex("\\bprintStackTrace\\s*\\("),
        )

        val LOG_WITH_LITERAL_TAG = Regex("\\bLog\\.[dweivwtf]\\s*\\(\\s*\"")

        /** Any `val NAME = "value"`; the ones ending in `TAG` are the log tags. */
        val TAG_DECLARATION = Regex("\\bval\\s+([A-Za-z_]\\w*)\\s*=\\s*\"([^\"]*)\"")

        val DECLARATION = Regex("\\b(?:class|object|interface)\\s+([A-Za-z_]\\w*)")
    }
}

package com.offlinemaps.core.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * OPTIMIZATION.md D-07: proves that whatever the user types, `SearchIndex.toFtsTerms` hands SQLite a
 * syntactically valid FTS4 MATCH expression.
 *
 * The property under test is structural: the expression is either empty or one to six space
 * separated terms, each a run of letters/digits followed by the prefix star. That shape cannot be
 * read as a phrase (`"`), a column filter (`name:`), a grouping (`(`/`)`), a negation (`-`/`NOT`
 * operator) or an operator (`AND`/`OR`/`NEAR`), because every one of those characters is replaced
 * with a space before the terms are built. The tests below also execute the expression against the
 * real `places_fts` table, so a shape that looked right but was rejected by SQLite would fail here
 * rather than in the search field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FtsQuerySyntaxTest {

    private lateinit var context: Context
    private lateinit var index: SearchIndex

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("offline_search.db")
        index = SearchIndex(context)
        index.importPack(
            "andorra",
            writePack(
                listOf(
                    PlaceRecord("n1", "Andorra la Vella", "town", 42.5069, 1.5212),
                    PlaceRecord("n2", "Escaldes-Engordany", "town", 42.5090, 1.5340),
                    PlaceRecord("n3", "Ordino", "town", 42.5562, 1.5335),
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        index.close()
        context.deleteDatabase("offline_search.db")
    }

    private fun writePack(records: List<PlaceRecord>): File {
        val file = File.createTempFile("places", ".jsonl.gz")
        GZIPOutputStream(file.outputStream()).bufferedWriter().use { writer ->
            records.forEach { record ->
                writer.write(
                    """{"id":"${record.id}","name":"${record.name}","type":"${record.type}",""" +
                        """"lat":${record.lat},"lon":${record.lon}}""",
                )
                writer.write("\n")
            }
        }
        return file
    }

    /** A single term: a run of letters or digits, followed by the FTS4 prefix star. */
    private val termPattern = Regex("""^[\p{L}\p{N}]+\*$""")

    /** Asserts the structural property and returns the expression for the caller to execute. */
    private fun assertWellFormed(query: String): String {
        val terms = SearchIndex.toFtsTerms(query)
        if (terms.isEmpty()) return terms
        assertFalse("expression starts with a space: <$terms>", terms.startsWith(" "))
        assertFalse("expression ends with a space: <$terms>", terms.endsWith(" "))
        val parts = terms.split(" ")
        assertTrue("more than six terms for <$query>: $parts", parts.size <= 6)
        parts.forEach { part ->
            assertTrue("term <$part> is not a bare prefix term (query <$query>)", termPattern.matches(part))
        }
        return terms
    }

    /** Runs the expression through the real FTS4 table; a syntax error fails the test. */
    private fun assertSqliteAccepts(terms: String, query: String) {
        if (terms.isEmpty()) return
        try {
            index.readableDatabase
                .rawQuery("SELECT rowid FROM places_fts WHERE places_fts MATCH ?", arrayOf(terms))
                .use { cursor -> cursor.count }
        } catch (error: Exception) {
            fail("SQLite rejected <$terms> built from <$query>: ${error.message}")
        }
    }

    private fun assertQueryIsSafe(query: String) {
        val terms = assertWellFormed(query)
        assertSqliteAccepts(terms, query)
        // The public entry point must be safe too, including the empty-expression short circuit.
        index.search(query)
    }

    @Test
    fun `quotes stars and operators never reach the fts expression`() {
        assertEquals("Ordino*", SearchIndex.toFtsTerms("\"Ordino\""))
        assertEquals("Ordino*", SearchIndex.toFtsTerms("Ordino*"))
        assertEquals("andorra*", SearchIndex.toFtsTerms("andorra***"))
        assertEquals("", SearchIndex.toFtsTerms("\"*()"))
        assertEquals("", SearchIndex.toFtsTerms("*"))
        assertEquals("", SearchIndex.toFtsTerms("\"\"\""))
        assertEquals("", SearchIndex.toFtsTerms("()():-^"))
    }

    @Test
    fun `operator words become literal terms instead of operators`() {
        // `NEAR`, `AND`, `OR` and `NOT` are letters, so cleaning leaves them in place. Android's
        // SQLite has no enhanced query syntax, so they are searched as words - valid, just literal.
        assertEquals("NEAR*", SearchIndex.toFtsTerms("NEAR"))
        assertEquals("a* NEAR* b*", SearchIndex.toFtsTerms("a NEAR b"))
        assertEquals("AND* OR* NOT*", SearchIndex.toFtsTerms("AND OR NOT"))
        // The `/` of NEAR/n is stripped, so the operator and its distance become two more terms.
        assertEquals("andorra* NEAR* 2* vella*", SearchIndex.toFtsTerms("andorra NEAR/2 vella"))
    }

    @Test
    fun `column filters and phrase grouping cannot be expressed`() {
        assertEquals("name* andorra*", SearchIndex.toFtsTerms("name:andorra"))
        assertEquals("a* b* c*", SearchIndex.toFtsTerms("a(b)c"))
        assertEquals("not* ordino*", SearchIndex.toFtsTerms("-not ordino"))
        assertEquals("Ordino*", SearchIndex.toFtsTerms("Ordino^"))
    }

    @Test
    fun `weird input is always a valid fts expression`() {
        val inputs = listOf(
            "\"Ordino",
            "Ordino*",
            "AND OR NOT",
            "Ordino; DROP TABLE places;",
            "\"andorra la vella\"",
            "NEAR",
            "NEAR/2",
            "andorra NEAR/2 vella",
            "a* b* c*",
            "-andorra",
            "+andorra",
            "andorra^",
            "name:andorra",
            "name:(andorra vella)",
            "places_fts MATCH 'x'",
            "\\",
            "%%_%",
            "{}[]<>|~`@#$&",
            "\u0000andorra",
            "andorra\u0000vella",
            "\u200b\u200candorra",
            "\ufeffandorra",
            "andorra\u00a0vella",
            "café",
            "अंदर",
            "アンドラ",
            "İstanbul",
            "①Ⅻ²",
            "ﬁ𝔸",
            "😀🗺️",
            "\t\n\r ",
            "'",
            "\"",
            "?",
            ".",
            ",,",
            "a".repeat(5000),
            "andorra ".repeat(400),
            "a* ".repeat(2000),
            "\"a\" NEAR/1 \"b\"",
        )
        inputs.forEach { assertQueryIsSafe(it) }
    }

    @Test
    fun `a very long query is cut to six terms`() {
        val terms = SearchIndex.toFtsTerms((1..40).joinToString(" ") { "word$it" })
        assertEquals("word1* word2* word3* word4* word5* word6*", terms)
    }

    @Test
    fun `real queries still match after cleaning`() {
        assertEquals("Andorra la Vella", index.search("\"andorra\" la").first().name)
        assertEquals("Ordino", index.search("ordino;").first().name)
        assertEquals("Andorra la Vella", index.search("ANDORRA*").first().name)
    }
}

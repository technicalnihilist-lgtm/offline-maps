package com.offlinemaps.core.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure tests for the resume validator sidecar and the decision that reads it. No server involved:
 * these pin down the rule that a partial file is only kept when it can be validated.
 */
class ResumeMetaTest {

    @Test
    fun `round trips through the on-disk layout`() {
        val meta = ResumeMeta(etag = "\"abc\"", lastModified = "Wed, 21 Oct 2015 07:28:00 GMT", totalBytes = 4096L)
        assertEquals(meta, ResumeMeta.parse(ResumeMeta.format(meta)))
    }

    @Test
    fun `reads a sidecar written by an earlier build`() {
        // The three positional lines the engine has always written.
        val parsed = ResumeMeta.parse("\"abc\"\nWed, 21 Oct 2015 07:28:00 GMT\n1234")
        assertEquals("\"abc\"", parsed.etag)
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", parsed.lastModified)
        assertEquals(1234L, parsed.totalBytes)
    }

    @Test
    fun `missing or blank sidecars degrade to empty`() {
        assertEquals(ResumeMeta.EMPTY, ResumeMeta.parse(null))
        assertEquals(ResumeMeta.EMPTY, ResumeMeta.parse(""))
        assertEquals(ResumeMeta.EMPTY, ResumeMeta.parse("   \n\n"))
    }

    @Test
    fun `an unreadable sidecar never throws and never invents a size`() {
        val garbage = ResumeMeta.parse("garbage")
        assertNull(garbage.lastModified)
        assertEquals(0L, garbage.totalBytes)
    }

    @Test
    fun `blank lines are treated as absent values`() {
        val parsed = ResumeMeta.parse("\n\n2048")
        assertNull(parsed.etag)
        assertNull(parsed.lastModified)
        assertEquals(2048L, parsed.totalBytes)
        assertNull(parsed.validator)
    }

    @Test
    fun `carriage returns from a windows line ending do not leak into the validator`() {
        val parsed = ResumeMeta.parse("\"abc\"\r\nWed, 21 Oct 2015 07:28:00 GMT\r\n99")
        assertEquals("\"abc\"", parsed.etag)
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", parsed.lastModified)
        assertEquals(99L, parsed.totalBytes)
    }

    @Test
    fun `a newline inside a value cannot break the line structure`() {
        val text = ResumeMeta.format(ResumeMeta(etag = "\"a\nb\"", lastModified = null, totalBytes = 7L))
        assertEquals(3, text.split('\n').size)
        assertEquals("\"a b\"", ResumeMeta.parse(text).etag)
        assertEquals(7L, ResumeMeta.parse(text).totalBytes)
    }

    @Test
    fun `the validator prefers the etag and falls back to the date`() {
        assertEquals("\"abc\"", ResumeMeta(etag = "\"abc\"", lastModified = "date").validator)
        assertEquals("date", ResumeMeta(etag = null, lastModified = "date").validator)
        assertEquals("date", ResumeMeta(etag = "  ", lastModified = "date").validator)
        assertNull(ResumeMeta.EMPTY.validator)
    }

    @Test
    fun `a partial is kept only when it can be validated`() {
        val validated = ResumeMeta(etag = "\"v1\"", totalBytes = 4096L)
        assertEquals(ResumeDecision.RESUME, ResumePolicy.decide(1024L, validated))
        assertEquals(ResumeDecision.RESUME, ResumePolicy.decide(4096L, validated))
        // No validator recorded: a resume would be a guess.
        assertEquals(ResumeDecision.RESTART, ResumePolicy.decide(1024L, ResumeMeta.EMPTY))
        // Nothing on disk.
        assertEquals(ResumeDecision.RESTART, ResumePolicy.decide(0L, validated))
        assertEquals(ResumeDecision.RESTART, ResumePolicy.decide(-5L, validated))
        // Longer than the file it claims to be a prefix of.
        assertEquals(ResumeDecision.RESTART, ResumePolicy.decide(5000L, validated))
    }

    @Test
    fun `a date-only validator is enough to resume`() {
        val dateOnly = ResumeMeta(etag = null, lastModified = "Wed, 21 Oct 2015 07:28:00 GMT", totalBytes = 4096L)
        assertEquals(ResumeDecision.RESUME, ResumePolicy.decide(1024L, dateOnly))
    }

    @Test
    fun `an unknown total size does not by itself force a restart`() {
        // A server that sent no Content-Length leaves totalBytes at 0; the validator still governs.
        val meta = ResumeMeta(etag = "\"v1\"", totalBytes = 0L)
        assertEquals(ResumeDecision.RESUME, ResumePolicy.decide(999_999L, meta))
    }
}

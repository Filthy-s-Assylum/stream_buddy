package com.streambuddy.app.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRangeTest {

    @Test
    fun `blank header means full resource`() {
        assertEquals(RangeParse.Full, HttpRange.parse(null, 100))
        assertEquals(RangeParse.Full, HttpRange.parse("  ", 100))
    }

    @Test
    fun `parses a bounded range`() {
        val parsed = HttpRange.parse("bytes=10-19", 100) as RangeParse.Single
        assertEquals(ByteRange(10, 19), parsed.range)
        assertEquals(10L, parsed.range.length)
        assertEquals("bytes 10-19/100", parsed.range.contentRange(100))
    }

    @Test
    fun `clamps an end beyond the resource`() {
        val parsed = HttpRange.parse("bytes=90-500", 100) as RangeParse.Single
        assertEquals(ByteRange(90, 99), parsed.range)
    }

    @Test
    fun `open ended range runs to the last byte`() {
        val parsed = HttpRange.parse("bytes=42-", 100) as RangeParse.Single
        assertEquals(ByteRange(42, 99), parsed.range)
    }

    @Test
    fun `suffix range returns the tail`() {
        val parsed = HttpRange.parse("bytes=-25", 100) as RangeParse.Single
        assertEquals(ByteRange(75, 99), parsed.range)
    }

    @Test
    fun `suffix larger than the resource returns everything`() {
        val parsed = HttpRange.parse("bytes=-500", 100) as RangeParse.Single
        assertEquals(ByteRange(0, 99), parsed.range)
    }

    @Test
    fun `rejects malformed and unsafe ranges`() {
        val invalid = listOf(
            "bytes=abc-def",
            "bytes=50-10",
            "bytes=100-200",
            "bytes=0-1,4-5",
            "bytes=-0",
            "items=0-1",
            "bytes=",
            "bytes=5",
            "bytes=--5",
        )
        invalid.forEach { header ->
            assertTrue("$header should be invalid", HttpRange.parse(header, 100) is RangeParse.Invalid)
        }
    }

    @Test
    fun `empty resource is unsatisfiable`() {
        assertTrue(HttpRange.parse("bytes=0-0", 0) is RangeParse.Invalid)
    }

    @Test
    fun `unit name is case insensitive`() {
        val parsed = HttpRange.parse("BYTES=0-0", 1) as RangeParse.Single
        assertEquals(ByteRange(0, 0), parsed.range)
    }
}

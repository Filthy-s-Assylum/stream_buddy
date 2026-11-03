package com.streambuddy.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {

    @Test
    fun `formats byte sizes`() {
        assertEquals("0 B", Format.bytes(0))
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.0 KB", Format.bytes(1024))
        assertEquals("1.5 MB", Format.bytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.0 GB", Format.bytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `formats durations`() {
        assertEquals("0:42", Format.duration(42_000))
        assertEquals("12:07", Format.duration(727_000))
        assertEquals("1:02:03", Format.duration(3_723_000))
        assertEquals("0:00", Format.duration(-5))
    }

    @Test
    fun `formats percentages`() {
        assertEquals(50, Format.percent(50, 100))
        assertEquals(100, Format.percent(200, 100))
        assertNull(Format.percent(10, 0))
    }

    @Test
    fun `shortens long messages`() {
        assertEquals("short", Format.shortMessage("short"))
        assertNull(Format.shortMessage("   "))
        assertEquals(11, Format.shortMessage("0123456789abcdef", maxLength = 11)?.length)
    }
}

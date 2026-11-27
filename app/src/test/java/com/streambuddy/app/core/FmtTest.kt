package com.streambuddy.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FmtTest {

    @Test
    fun `formats bytes across units`() {
        assertEquals("0 B", Fmt.bytes(-5))
        assertEquals("512 B", Fmt.bytes(512))
        assertEquals("1.5 KB", Fmt.bytes(1536))
        assertEquals("2.0 MB", Fmt.bytes(2L * 1024 * 1024))
    }

    @Test
    fun `formats bitrates durations percents and decimals`() {
        assertEquals("—", Fmt.rate(0.0))
        assertEquals("—", Fmt.rate(Double.NaN))
        assertEquals("500 bps", Fmt.rate(500.0))
        assertEquals("250 kbps", Fmt.rate(250_000.0))
        assertEquals("4.50 Mbps", Fmt.rate(4_500_000.0))
        assertEquals("1.25 Gbps", Fmt.rate(1_250_000_000.0))

        assertEquals("—", Fmt.duration(-1.0))
        assertEquals("1:05", Fmt.duration(65.0))
        assertEquals("1:01:05", Fmt.duration(3665.0))

        assertEquals("75%", Fmt.percent(0.75))
        assertEquals("100%", Fmt.percent(1.5))
        assertEquals("8.4", Fmt.oneDecimal(8.42))
    }
}

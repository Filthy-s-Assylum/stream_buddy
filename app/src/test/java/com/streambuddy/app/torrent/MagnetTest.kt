package com.streambuddy.app.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagnetTest {

    private val hexHash = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun `parses a standard magnet with trackers`() {
        val link = Magnet.parse(
            "magnet:?xt=urn:btih:$hexHash&dn=Example%20Movie%202024&xl=1234" +
                "&tr=udp%3A%2F%2Ftracker.example%3A6969%2Fannounce" +
                "&tr=https%3A%2F%2Ftracker.example%2Fannounce",
        ).getOrThrow()

        assertEquals(hexHash, link.infoHashHex)
        assertEquals("Example Movie 2024", link.displayName)
        assertEquals(1234L, link.exactLength)
        assertEquals(2, link.trackers.size)
        assertTrue(link.trackers.any { it.startsWith("udp://") })
    }

    @Test
    fun `accepts uppercase hash and normalises it`() {
        val link = Magnet.parse("magnet:?xt=urn:btih:${hexHash.uppercase()}").getOrThrow()
        assertEquals(hexHash, link.infoHashHex)
    }

    @Test
    fun `decodes a base32 info hash`() {
        // Base32 of the 20 bytes 0x01..0x14.
        val base32 = "AEBAGBAFAYDQQCIKBMGA2DQPCAIREEYU"
        val link = Magnet.parse("magnet:?xt=urn:btih:$base32").getOrThrow()
        assertEquals("0102030405060708090a0b0c0d0e0f1011121314", link.infoHashHex)
    }

    @Test
    fun `rejects links without a btih hash`() {
        assertTrue(Magnet.parse("magnet:?dn=NoHash").isFailure)
        assertTrue(Magnet.parse("magnet:?xt=urn:sha1:abc").isFailure)
        assertTrue(Magnet.parse("magnet:?xt=urn:btih:nothex").isFailure)
    }

    @Test
    fun `rejects non magnet input`() {
        assertTrue(Magnet.parse("https://example.com/video.mp4").isFailure)
        assertTrue(Magnet.parse("magnet:").isFailure)
        assertTrue(Magnet.parse("magnet:?").isFailure)
    }

    @Test
    fun `drops unsafe tracker schemes and control characters`() {
        val link = Magnet.parse(
            "magnet:?xt=urn:btih:$hexHash" +
                "&tr=javascript%3Aalert(1)" +
                "&tr=file%3A%2F%2F%2Fetc%2Fpasswd" +
                "&tr=http%3A%2F%2Fok.example%2Fa%0Ainjected",
        ).getOrThrow()
        // javascript/file are not tracker schemes, and the http tracker embeds
        // a newline which must be rejected rather than handed to libtorrent.
        assertTrue(link.trackers.isEmpty())
    }

    @Test
    fun `bounds display names and tracker lists`() {
        val trackers = (0 until 60).joinToString("&") { "tr=http%3A%2F%2Ft$it.example%2Fa" }
        val link = Magnet.parse("magnet:?xt=urn:btih:$hexHash&dn=${"a".repeat(1000)}&$trackers")
            .getOrThrow()
        assertEquals(300, link.displayName?.length)
        assertEquals(40, link.trackers.size)
    }

    @Test
    fun `detects magnet links case insensitively`() {
        assertTrue(Magnet.isMagnet(" MAGNET:?xt=urn:btih:$hexHash"))
        assertFalse(Magnet.isMagnet("https://example.com"))
    }

    @Test
    fun `unknown parameters are ignored`() {
        val link = Magnet.parse("magnet:?xt=urn:btih:$hexHash&so=0-1&unknown=value").getOrThrow()
        assertEquals(hexHash, link.infoHashHex)
        assertNull(link.displayName)
    }
}

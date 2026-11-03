package com.streambuddy.app.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagnetLinkTest {

    private val hexHash = "c12fe1c06bba254a9dc9178d2d8b4b45a3f0f3c9"

    @Test
    fun parsesHexInfoHash() {
        val magnet = MagnetLink.parse("magnet:?xt=urn:btih:$hexHash")
        assertNotNull(magnet)
        assertEquals(hexHash, magnet!!.infoHashHex)
        assertNull(magnet.displayName)
        assertTrue(magnet.trackers.isEmpty())
    }

    @Test
    fun uppercasesHexAreNormalised() {
        val magnet = MagnetLink.parse("magnet:?xt=urn:btih:${hexHash.uppercase()}")
        assertEquals(hexHash, magnet!!.infoHashHex)
    }

    @Test
    fun parsesBase32InfoHash() {
        // Base32 of the same 20 bytes as hexHash.
        val magnet = MagnetLink.parse("magnet:?xt=urn:btih:YEX6DQDLXISUVHOJC6GS3C2LIWR7B46J")
        assertNotNull(magnet)
        assertEquals(hexHash, magnet!!.infoHashHex)
    }

    @Test
    fun parsesNameAndTrackers() {
        val magnet = MagnetLink.parse(
            "magnet:?xt=urn:btih:$hexHash" +
                "&dn=Big%20Buck%20Bunny%201080p" +
                "&tr=udp%3A%2F%2Ftracker.example.com%3A80%2Fannounce" +
                "&tr=https%3A%2F%2Ftracker.example.org%2Fa",
        )
        assertEquals("Big Buck Bunny 1080p", magnet!!.displayName)
        assertEquals(2, magnet.trackers.size)
        assertTrue(magnet.trackers[0].startsWith("udp://tracker.example.com"))
        assertTrue(magnet.trackers[1].startsWith("https://tracker.example.org"))
    }

    @Test
    fun keepsTrackersInOrderAndDropsDuplicates() {
        val magnet = MagnetLink.parse(
            "magnet:?xt=urn:btih:$hexHash&tr=udp://a:1&tr=udp://a:1&tr=udp://b:2",
        )
        assertEquals(listOf("udp://a:1", "udp://b:2"), magnet!!.trackers)
    }

    @Test
    fun nonHttpTrackersAreDropped() {
        val magnet = MagnetLink.parse("magnet:?xt=urn:btih:$hexHash&tr=javascript:alert(1)")
        assertTrue(magnet!!.trackers.isEmpty())
    }

    @Test
    fun parsesExactLength() {
        val magnet = MagnetLink.parse("magnet:?xt=urn:btih:$hexHash&xl=1234567")
        assertEquals(1234567L, magnet!!.exactLengthBytes)
    }

    @Test
    fun rejectsThingsThatAreNotMagnets() {
        assertNull(MagnetLink.parse("https://example.com/x.m3u8"))
        assertNull(MagnetLink.parse("magnet:?"))
        assertNull(MagnetLink.parse("magnet:?dn=NoHashHere"))
        assertNull(MagnetLink.parse("magnet:?xt=urn:sha1:abc"))
        assertNull(MagnetLink.parse("magnet:?xt=urn:btih:tooshort"))
        assertNull(MagnetLink.parse(""))
    }

    @Test
    fun looksLikeMagnetIsCheapAndCaseInsensitive() {
        assertTrue(MagnetLink.looksLikeMagnet("MAGNET:?xt=urn:btih:$hexHash"))
        assertFalse(MagnetLink.looksLikeMagnet("https://example.com"))
        assertFalse(MagnetLink.looksLikeMagnet("magnetism"))
    }

    @Test
    fun playbackUriRoundTrips() {
        val magnet = MagnetLink.parse("magnet:?xt=urn:btih:$hexHash")!!
        val uri = magnet.playbackUri(3)
        assertEquals("streambuddy-torrent://$hexHash/3", uri)
        assertTrue(uri.startsWith(MagnetLink.SCHEME))
    }

    @Test
    fun shortHashIsForDisplay() {
        val magnet = MagnetLink.parse("magnet:?xt=urn:btih:$hexHash")!!
        assertEquals(8, magnet.shortHash.length)
    }

    @Test
    fun base32RoundTripMatchesHex() {
        // A second independent pair, to make sure the decoder is not accidentally right once.
        val another = "0123456789abcdef0123456789abcdef01234567"
        val base32 = "AERUKZ4JVPG66AJDIVTYTK6N54ASGRLH"
        assertEquals(another, MagnetLink.normalizeInfoHash(base32))
    }

    @Test
    fun invalidBase32IsRejected() {
        assertNull(MagnetLink.normalizeInfoHash("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"))
        assertNull(MagnetLink.normalizeInfoHash("tooshort"))
    }
}

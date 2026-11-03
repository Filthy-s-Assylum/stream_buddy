package com.streambuddy.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HlsPlaylistParserTest {

    @Test
    fun `parses master playlist variants`() {
        val parsed = HlsPlaylistParser.parse(MASTER, "https://cdn.example.com/vod/master.m3u8")

        assertTrue(parsed is HlsPlaylist.Master)
        val variants = (parsed as HlsPlaylist.Master).variants

        assertEquals(3, variants.size)
        assertEquals("https://cdn.example.com/vod/low/index.m3u8", variants[0].url)
        assertEquals(2_400_000L, variants[1].bandwidth)
        assertEquals(720, variants[1].height)
        assertEquals("avc1.640028,mp4a.40.2", variants[1].codecs)
        assertEquals("https://other.cdn.example/x/1080.m3u8", variants[2].url)
        assertEquals(1080, variants[2].height)
    }

    @Test
    fun `parses media playlist durations and urls`() {
        val playlist = (HlsPlaylistParser.parse(MEDIA, BASE) as HlsPlaylist.Media).playlist

        assertEquals(3, playlist.segments.size)
        assertEquals(9_009L, playlist.segments[0].durationMs)
        assertEquals(3_500L, playlist.segments[2].durationMs)
        assertEquals(9_009L + 9_009L + 3_500L, playlist.totalDurationMs)
        assertEquals(10_000L, playlist.targetDurationMs)
        assertEquals(7, playlist.mediaSequence)
        assertFalse(playlist.isLive)
        assertEquals("$BASE_DIR/seg1.ts", playlist.segments[1].url)
        assertNull(playlist.initializationSegment)
    }

    @Test
    fun `live playlist has no end list`() {
        val playlist = (
            HlsPlaylistParser.parse(
                "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\na.ts\n",
                BASE,
            ) as HlsPlaylist.Media
            ).playlist

        assertTrue(playlist.isLive)
    }

    @Test
    fun `vod playlist type implies not live`() {
        val playlist = (
            HlsPlaylistParser.parse(
                "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:4.0,\na.ts\n",
                BASE,
            ) as HlsPlaylist.Media
            ).playlist

        assertFalse(playlist.isLive)
    }

    @Test
    fun `parses byte ranges including implicit offsets`() {
        val playlist = (HlsPlaylistParser.parse(BYTERANGE, BASE) as HlsPlaylist.Media).playlist

        val init = playlist.initializationSegment
        assertEquals("$BASE_DIR/init.mp4", init?.url)
        assertEquals(0L, init?.byteRangeOffset)
        assertEquals(720L, init?.byteRangeLength)

        assertEquals(720L, playlist.segments[0].byteRangeOffset)
        assertEquals(1_000L, playlist.segments[0].byteRangeLength)
        assertEquals(1_720L, playlist.segments[1].byteRangeOffset)
        assertEquals(2_000L, playlist.segments[1].byteRangeLength)

        assertNull(playlist.segments[2].byteRangeOffset)
        assertNull(playlist.segments[2].byteRangeLength)
    }

    @Test
    fun `parses byte range helper`() {
        assertEquals(HlsByteRange(1_000L, 720L), HlsPlaylistParser.parseByteRange("1000@720"))
        assertEquals(HlsByteRange(1_000L, null), HlsPlaylistParser.parseByteRange("1000"))
        assertEquals(HlsByteRange(720L, 0L), HlsPlaylistParser.parseByteRange("\"720@0\""))
        assertNull(HlsPlaylistParser.parseByteRange("nope"))
        assertNull(HlsPlaylistParser.parseByteRange("0"))
    }

    @Test
    fun `resolves relative urls against the playlist url`() {
        val base = "https://cdn.example.com/vod/playlist.m3u8"

        assertEquals("https://cdn.example.com/vod/seg.ts", HlsPlaylistParser.resolveUrl(base, "seg.ts"))
        assertEquals("https://cdn.example.com/vod/a/b.ts", HlsPlaylistParser.resolveUrl(base, "a/b.ts"))
        assertEquals("https://cdn.example.com/other/b.ts", HlsPlaylistParser.resolveUrl(base, "../other/b.ts"))
        assertEquals("https://cdn.example.com/root.ts", HlsPlaylistParser.resolveUrl(base, "/root.ts"))
        assertEquals("http://x.test/y.ts", HlsPlaylistParser.resolveUrl(base, "http://x.test/y.ts"))
        assertEquals("https://other.test/y.ts", HlsPlaylistParser.resolveUrl(base, "//other.test/y.ts"))
        assertEquals(
            "https://cdn.example.com/vod/seg.ts?token=1",
            HlsPlaylistParser.resolveUrl(base, "seg.ts?token=1"),
        )
    }

    @Test
    fun `resolves urls the java uri parser rejects`() {
        val awkward = "https://cdn.example.com/vod path/master.m3u8?token=a|b"

        assertEquals("https://cdn.example.com/vod path/seg.ts", HlsPlaylistParser.resolveUrl(awkward, "seg.ts"))
        assertEquals("https://cdn.example.com/root.ts", HlsPlaylistParser.resolveUrl(awkward, "/root.ts"))
        assertEquals("https://z.test/s.ts", HlsPlaylistParser.resolveUrl(awkward, "https://z.test/s.ts"))
    }

    @Test
    fun `rejects non playlist payloads`() {
        try {
            HlsPlaylistParser.parse("<html>403 Forbidden</html>", BASE)
            fail("Expected PlaylistParseException")
        } catch (_: PlaylistParseException) {
            // expected
        }
    }

    @Test
    fun `rejects empty playlist`() {
        try {
            HlsPlaylistParser.parse("#EXTM3U\n", BASE)
            fail("Expected PlaylistParseException")
        } catch (_: PlaylistParseException) {
            // expected
        }
    }

    @Test
    fun `handles bom and crlf line endings`() {
        val playlist = (
            HlsPlaylistParser.parse(
                "\uFEFF#EXTM3U\r\n#EXTINF:6.0,\r\na.ts\r\n#EXT-X-ENDLIST\r\n",
                BASE,
            ) as HlsPlaylist.Media
            ).playlist

        assertEquals(1, playlist.segments.size)
        assertEquals(6_000L, playlist.segments[0].durationMs)
        assertEquals("$BASE_DIR/a.ts", playlist.segments[0].url)
        assertFalse(playlist.isLive)
    }

    @Test
    fun `parses attribute lists with quoted commas`() {
        val attributes = HlsAttributes.parse(
            """BANDWIDTH=1280000,CODECS="avc1.4d401e,mp4a.40.2",RESOLUTION=1280x720""",
        )

        assertEquals("1280000", attributes["BANDWIDTH"])
        assertEquals("avc1.4d401e,mp4a.40.2", attributes["CODECS"])
        assertEquals("1280x720", attributes["RESOLUTION"])
        assertEquals("v", HlsAttributes.parse("method=v")["METHOD"])
        assertEquals("", HlsAttributes.parse("FOO")["FOO"])
    }

    private companion object {
        const val BASE_DIR = "https://cdn.example.com/vod/media"
        const val BASE = "$BASE_DIR/index.m3u8"

        const val MASTER = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
low/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720,CODECS="avc1.640028,mp4a.40.2"
high/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
https://other.cdn.example/x/1080.m3u8
"""

        const val MEDIA = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:10
#EXT-X-MEDIA-SEQUENCE:7
#EXT-X-PLAYLIST-TYPE:VOD
#EXTINF:9.009,
seg0.ts
#EXTINF:9.009,
seg1.ts
#EXTINF:3.5,some title
seg2.ts
#EXT-X-ENDLIST
"""

        const val BYTERANGE = """#EXTM3U
#EXT-X-VERSION:4
#EXT-X-TARGETDURATION:10
#EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
#EXTINF:6.0,
#EXT-X-BYTERANGE:1000@720
main.mp4
#EXTINF:6.0,
#EXT-X-BYTERANGE:2000
main.mp4
#EXTINF:6.0,
main2.mp4
#EXT-X-ENDLIST
"""
    }
}

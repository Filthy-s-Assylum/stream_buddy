package com.streambuddy.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlTest {

    @Test
    fun `accepts https url and detects kind`() {
        assertEquals(
            StreamUrl.Result.Valid("https://cdn.test/a.m3u8", StreamKind.HLS),
            StreamUrl.validate("https://cdn.test/a.m3u8"),
        )
    }

    @Test
    fun `adds a missing scheme`() {
        val result = StreamUrl.validate("cdn.test/a.mp4") as StreamUrl.Result.Valid
        assertEquals("https://cdn.test/a.mp4", result.url)
        assertEquals(StreamKind.PROGRESSIVE, result.kind)
    }

    @Test
    fun `rejects unusable input`() {
        assertTrue(StreamUrl.validate("   ") is StreamUrl.Result.Invalid)
        assertTrue(StreamUrl.validate("ftp://cdn.test/a.mp4") is StreamUrl.Result.Invalid)
        assertTrue(StreamUrl.validate("https://") is StreamUrl.Result.Invalid)
        assertTrue(StreamUrl.validate("not a url") is StreamUrl.Result.Invalid)
    }

    @Test
    fun `suggests a readable title`() {
        assertEquals("master", StreamUrl.suggestTitle("https://cdn.test/movies/big/master.m3u8?x=1"))
        assertEquals("cdn.test", StreamUrl.suggestTitle("https://cdn.test/"))
        assertEquals("", StreamUrl.suggestTitle(""))
    }

    @Test
    fun `detects stream kinds from urls`() {
        assertEquals(StreamKind.HLS, StreamKind.fromUrl("https://x.test/a.m3u8?token=1"))
        assertEquals(StreamKind.DASH, StreamKind.fromUrl("https://x.test/a.mpd"))
        assertEquals(StreamKind.PROGRESSIVE, StreamKind.fromUrl("https://x.test/a.MP4#t=10"))
        assertEquals(StreamKind.UNKNOWN, StreamKind.fromUrl("https://x.test/stream"))
    }

    @Test
    fun `maps content types`() {
        assertEquals(StreamKind.HLS, StreamKind.fromContentType("application/vnd.apple.mpegurl; charset=utf-8"))
        assertEquals(StreamKind.DASH, StreamKind.fromContentType("application/dash+xml"))
        assertEquals(StreamKind.PROGRESSIVE, StreamKind.fromContentType("video/mp4"))
        assertEquals(null, StreamKind.fromContentType(null))
        assertEquals(null, StreamKind.fromContentType("application/json"))
    }

    @Test
    fun `parses stored enum names tolerantly`() {
        assertEquals(StreamKind.HLS, StreamKind.fromName("hls"))
        assertEquals(StreamKind.UNKNOWN, StreamKind.fromName(null))
        assertEquals(StreamKind.UNKNOWN, StreamKind.fromName("legacy-value"))
        assertEquals(CacheState.CACHING, CacheState.fromName("CACHING"))
        assertEquals(CacheState.QUEUED, CacheState.fromName("bogus"))
    }
}

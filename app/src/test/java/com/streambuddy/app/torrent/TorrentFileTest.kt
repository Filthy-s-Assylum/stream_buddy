package com.streambuddy.app.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentFileTest {

    private fun file(path: String, size: Long = 1_000L, offset: Long = 0L) =
        TorrentFile(index = 0, path = path, sizeBytes = size, offsetInTorrent = offset)

    @Test
    fun nameAndExtensionComeFromThePath() {
        val video = file("Show/Season 1/Episode 01.mkv")
        assertEquals("Episode 01.mkv", video.name)
        assertEquals("mkv", video.extension)
        assertTrue(video.isVideo)
    }

    @Test
    fun extensionIsCaseInsensitive() {
        assertEquals("mp4", file("Movie.MP4").extension)
    }

    @Test
    fun nonVideoFilesAreDetected() {
        assertFalse(file("readme.txt").isVideo)
        assertFalse(file("subs/English.srt").isVideo)
        assertFalse(file("artwork.jpg").isVideo)
    }

    @Test
    fun samplesAndTrailersAreExtras() {
        assertTrue(file("Movie/sample-movie.mkv").isExtras)
        assertTrue(file("Movie/trailer.mp4").isExtras)
        assertTrue(file("Extras/featurette.mkv").isExtras)
        assertFalse(file("Movie/movie.2024.1080p.mkv").isExtras)
    }

    @Test
    fun extrasAreNotPlayable() {
        assertFalse(file("Movie/sample.mkv").isPlayable)
        assertTrue(file("Movie/movie.mkv").isPlayable)
    }

    @Test
    fun macOSResourceForksAreIgnored() {
        assertFalse(file("._movie.mkv").isPlayable)
    }

    @Test
    fun advertisedHeightReadsCommonTags() {
        assertEquals(1080, file("Movie.2024.1080p.BluRay.x264.mkv").advertisedHeight)
        assertEquals(720, file("Movie.720P.mkv").advertisedHeight)
        assertEquals(2160, file("Movie.2160p.HDR.mkv").advertisedHeight)
        assertEquals(2160, file("Movie.4K.HDR.mkv").advertisedHeight)
        assertEquals(4320, file("Movie.8k.mkv").advertisedHeight)
        assertEquals(1440, file("Movie.2k.mkv").advertisedHeight)
    }

    @Test
    fun advertisedHeightIsNullWithoutATag() {
        assertEquals(null, file("Movie.mkv").advertisedHeight)
        assertEquals(null, file("Movie.2024.x264.mkv").advertisedHeight)
    }

    @Test
    fun codecAndRangeFlags() {
        assertTrue(file("Movie.x265.1080p.mkv").isHevc)
        assertTrue(file("Movie.HEVC.mkv").isHevc)
        assertTrue(file("Movie.h.265.mkv").isHevc)
        assertFalse(file("Movie.x264.mkv").isHevc)

        assertTrue(file("Movie.1080p.HDR.mkv").isHdr)
        assertTrue(file("Movie.Dolby.Vision.mkv").isHdr)
        assertFalse(file("Movie.SDR.mkv").isHdr)

        assertTrue(file("Movie.1080p.REMUX.mkv").isRemuxOrRaw)
        assertFalse(file("Movie.WEB-DL.mkv").isRemuxOrRaw)
    }
}

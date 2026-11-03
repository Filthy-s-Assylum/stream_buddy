package com.streambuddy.app.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualitySelectorTest {

    private val phone = QualitySelector.DeviceProfile(
        screenWidthPx = 1080,
        screenHeightPx = 2400,
        maxDecodeWidth = 3840,
        maxDecodeHeight = 2160,
        supportsHevc = true,
        supportsHdr = false,
        availableStorageBytes = 64L * 1024 * 1024 * 1024,
    )

    private val wifi = QualitySelector.NetworkProfile(metered = false)

    private fun file(
        index: Int,
        name: String,
        sizeBytes: Long,
        offset: Long = index * sizeBytes,
    ) = TorrentFile(index = index, path = name, sizeBytes = sizeBytes, offsetInTorrent = offset)

    private val mkv1080 = file(0, "Movie.2024.1080p.BluRay.x264.mkv", 8_000_000_000L)
    private val mkv2160 = file(1, "Movie.2024.2160p.HDR.x265.mkv", 40_000_000_000L)
    private val mp4_720 = file(2, "Movie.2024.720p.mp4", 1_200_000_000L)
    private val sample = file(3, "Movie.2024.sample.mkv", 80_000_000L)

    @Test
    fun screenHeightIsTheShortSideSoAPhoneIsNotJudgedAs4K() {
        assertEquals(1080, phone.effectiveHeight)
    }

    @Test
    fun decoderLimitWinsOverTheScreen() {
        val lowEnd = phone.copy(maxDecodeHeight = 720)
        assertEquals(720, lowEnd.effectiveHeight)
    }

    @Test
    fun picksTheFileThatMatchesTheScreenOnWiFi() {
        val selection = QualitySelector.select(
            files = listOf(mkv1080, mkv2160, mp4_720, sample),
            device = phone,
            network = wifi,
        )
        assertNotNull(selection)
        assertEquals(mkv1080.index, selection!!.chosen.file.index)
        assertTrue(selection.chosen.reasons.any { it.contains("matches this screen") })
    }

    @Test
    fun neverPicksSamplesOrNonVideoFiles() {
        val selection = QualitySelector.select(
            files = listOf(sample, file(4, "readme.nfo", 2_000L), mkv1080),
            device = phone,
            network = wifi,
        )
        assertEquals(mkv1080.index, selection!!.chosen.file.index)
    }

    @Test
    fun returnsNullWhenThereIsNoVideo() {
        val selection = QualitySelector.select(
            files = listOf(file(0, "track01.flac", 30_000_000L), file(1, "cover.jpg", 200_000L)),
            device = phone,
            network = wifi,
        )
        assertNull(selection)
    }

    @Test
    fun hevcIsSkippedWhenTheDeviceCannotDecodeIt() {
        val noHevc = phone.copy(supportsHevc = false)
        val hevc720 = file(5, "Movie.720p.x265.mkv", 800_000_000L)
        val selection = QualitySelector.select(
            files = listOf(hevc720, mp4_720),
            device = noHevc,
            network = wifi,
        )
        assertEquals(mp4_720.index, selection!!.chosen.file.index)
        assertTrue(selection.alternatives.none { it.file.index == hevc720.index })
    }

    @Test
    fun hevcIsFineWhenTheDeviceSupportsIt() {
        val hevc720 = file(5, "Movie.720p.x265.mkv", 800_000_000L)
        val selection = QualitySelector.select(
            files = listOf(hevc720, file(6, "Movie.480p.mp4", 400_000_000L)),
            device = phone,
            network = wifi,
        )
        assertEquals(hevc720.index, selection!!.chosen.file.index)
    }

    @Test
    fun fourKOnA1080pPhoneIsDownRankedButStillAnOption() {
        val selection = QualitySelector.select(
            files = listOf(mkv2160, mkv1080),
            device = phone,
            network = wifi,
        )
        assertEquals(mkv1080.index, selection!!.chosen.file.index)
        val uhd = selection.alternatives.first { it.file.index == mkv2160.index }
        assertTrue(uhd.reasons.any { it.contains("above this screen") })
    }

    @Test
    fun fourKWinsOnA4KScreen() {
        val tablet = phone.copy(screenWidthPx = 2160, screenHeightPx = 3840)
        val selection = QualitySelector.select(
            files = listOf(mkv2160, mkv1080),
            device = tablet,
            network = wifi,
        )
        assertEquals(mkv2160.index, selection!!.chosen.file.index)
    }

    @Test
    fun aFileLargerThanFreeStorageIsExcluded() {
        val small = phone.copy(availableStorageBytes = 2_000_000_000L)
        val selection = QualitySelector.select(
            files = listOf(mkv1080, mp4_720),
            device = small,
            network = wifi,
        )
        assertEquals(mp4_720.index, selection!!.chosen.file.index)
        assertTrue(selection.alternatives.none { it.file.index == mkv1080.index })
    }

    @Test
    fun meteredConnectionsAreToldWhenTheyGetAHeavyFile() {
        val selection = QualitySelector.select(
            files = listOf(mkv2160, mkv1080),
            device = phone,
            network = QualitySelector.NetworkProfile(metered = true, downstreamBps = 20_000_000L),
        )
        assertEquals(mkv1080.index, selection!!.chosen.file.index)
        assertTrue(selection.chosen.reasons.any { it.contains("metered") })
    }

    @Test
    fun slowConnectionsAreNoted() {
        val selection = QualitySelector.select(
            files = listOf(mp4_720),
            device = phone,
            network = QualitySelector.NetworkProfile(metered = false, downstreamBps = 900_000L),
        )
        assertTrue(selection!!.chosen.reasons.any { it.contains("slow connection") })
    }

    @Test
    fun hdrOnAnSdrScreenIsPenalisedNotBanned() {
        val hdr = file(7, "Movie.1080p.HDR.mkv", 9_000_000_000L)
        val sdr = file(8, "Movie.1080p.mkv", 9_000_000_000L)
        val selection = QualitySelector.select(
            files = listOf(hdr, sdr),
            device = phone,
            network = wifi,
        )
        assertEquals(sdr.index, selection!!.chosen.file.index)
    }

    @Test
    fun alternativesAreOrderedBestFirstAndIncludeTheChoice() {
        val selection = QualitySelector.select(
            files = listOf(mp4_720, mkv2160, mkv1080, sample),
            device = phone,
            network = wifi,
        )!!
        assertEquals(3, selection.alternatives.size)
        assertEquals(selection.chosen.file.index, selection.alternatives.first().file.index)
        val scores = selection.alternatives.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun downgradeGoesToASmallerFile() {
        val selection = QualitySelector.select(
            files = listOf(mkv2160, mkv1080, mp4_720),
            device = phone,
            network = wifi,
        )!!
        val next = QualitySelector.downgrade(selection.alternatives, selection.chosen)
        assertNotNull(next)
        assertTrue(next!!.file.sizeBytes < selection.chosen.file.sizeBytes)
    }

    @Test
    fun downgradeIsNullWhenNothingSmallerExists() {
        val only = QualitySelector.select(
            files = listOf(mp4_720),
            device = phone,
            network = wifi,
        )!!
        assertNull(QualitySelector.downgrade(only.alternatives, only.chosen))
    }
}

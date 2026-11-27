package com.streambuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `sanitized ensures bufferTargetSeconds is never less than startBufferSeconds`() {
        val raw = AppSettings(
            startBufferSeconds = 25,
            bufferTargetSeconds = 12,
        )
        val sanitized = raw.sanitized()
        assertEquals(25, sanitized.startBufferSeconds)
        assertEquals(25, sanitized.bufferTargetSeconds)
        assertTrue(sanitized.bufferTargetSeconds >= sanitized.startBufferSeconds)
    }

    @Test
    fun `sanitized clamps out-of-range fields and normalises country`() {
        val sanitized = AppSettings(
            prefetchMinutes = 999,
            playbackCacheMb = 1,
            startBufferSeconds = 0,
            bufferTargetSeconds = 5,
            prefetchSeconds = 1,
            torrentUploadLimitKbps = 0,
            motnCountry = "  GB123 ",
            tmdbApiKey = "  key123  ",
            motnApiKey = "  motn456  ",
        ).sanitized()

        assertEquals(AppSettings.MAX_PREFETCH_MINUTES, sanitized.prefetchMinutes)
        assertEquals(128, sanitized.playbackCacheMb)
        assertEquals(2, sanitized.startBufferSeconds)
        assertEquals(10, sanitized.bufferTargetSeconds)
        assertEquals(15, sanitized.prefetchSeconds)
        assertEquals(8, sanitized.torrentUploadLimitKbps)
        assertEquals("gb", sanitized.motnCountry)
        assertEquals("key123", sanitized.tmdbApiKey)
        assertEquals("motn456", sanitized.motnApiKey)
    }

    @Test
    fun `sanitized trims and caps preferredSubtitleLanguage`() {
        val sanitized = AppSettings(
            preferredSubtitleLanguage = "  en-US-something-too-long  ",
        ).sanitized()
        assertEquals("en-US-something-", sanitized.preferredSubtitleLanguage)
        assertEquals("es", AppSettings(preferredSubtitleLanguage = "  es  ").sanitized().preferredSubtitleLanguage)
    }

    @Test
    fun `encode and decode roundtrip preserves all settings`() {
        val original = AppSettings(
            prefetchMinutes = 10,
            preferHighestQuality = true,
            smartPrefetch = false,
            playbackCacheMb = 4096,
            startBufferSeconds = 6,
            bufferTargetSeconds = 60,
            prefetchSeconds = 120,
            maxVideoBitrateKbps = 8000,
            optimizerMode = OptimizerMode.ADAPTIVE,
            torrentDownloadLimitKbps = 5000,
            torrentUploadLimitKbps = 128,
            seedingEnabled = true,
            torrentStorageLimitMb = 8192,
            metadataTimeoutSeconds = 30,
            wifiOnlyDownloads = false,
            requireVpnForNetwork = true,
            malwareScanEnabled = true,
            blockScreenshots = true,
            tmdbApiKey = "tmdb-abc",
            motnApiKey = "motn-xyz",
            motnCountry = "ca",
            autoplayNextEpisode = true,
            preferredSubtitleLanguage = "en",
        )

        val decoded = AppSettings.decode(original.encode())
        assertEquals(original, decoded)
    }
}

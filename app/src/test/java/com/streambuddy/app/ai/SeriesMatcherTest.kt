package com.streambuddy.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesMatcherTest {

    @Test
    fun parsesSxxExx() {
        val parsed = SeriesMatcher.parse("The Bear S02E05")
        assertEquals("the bear", parsed.seriesKey)
        assertEquals("The Bear", parsed.displayTitle)
        assertEquals(2, parsed.season)
        assertEquals(5, parsed.episode)
    }

    @Test
    fun parsesSceneStyleNames() {
        val parsed = SeriesMatcher.parse("severance.s01e03.1080p.WEB-DL")
        assertEquals("severance", parsed.seriesKey)
        assertEquals(1, parsed.season)
        assertEquals(3, parsed.episode)
    }

    @Test
    fun parsesNxNnForm() {
        val parsed = SeriesMatcher.parse("Breaking Bad 5x14")
        assertEquals("breaking bad", parsed.seriesKey)
        assertEquals(14, parsed.episode)
    }

    @Test
    fun parsesEpisodeWordWithoutSeason() {
        val parsed = SeriesMatcher.parse("Some Podcast - Episode 12")
        assertEquals("some podcast", parsed.seriesKey)
        assertNull(parsed.season)
        assertEquals(12, parsed.episode)
    }

    @Test
    fun movieTitlesBecomeTheirOwnSeries() {
        val parsed = SeriesMatcher.parse("Big Buck Bunny")
        assertEquals("big buck bunny", parsed.seriesKey)
        assertEquals("Big Buck Bunny", parsed.displayTitle)
        assertFalse(parsed.hasEpisode)
    }

    @Test
    fun blankTitleIsHarmless() {
        val parsed = SeriesMatcher.parse("   ")
        assertEquals("", parsed.seriesKey)
        assertNull(parsed.episode)
    }

    @Test
    fun nextEpisodeInSameSeason() {
        assertTrue(
            SeriesMatcher.isNextEpisode(
                SeriesMatcher.parse("Show S01E02"),
                SeriesMatcher.parse("Show S01E01"),
            ),
        )
    }

    @Test
    fun sameEpisodeIsNotNext() {
        assertFalse(
            SeriesMatcher.isNextEpisode(
                SeriesMatcher.parse("Show S01E05"),
                SeriesMatcher.parse("Show S01E05"),
            ),
        )
    }

    @Test
    fun differentSeriesIsNotNext() {
        assertFalse(
            SeriesMatcher.isNextEpisode(
                SeriesMatcher.parse("Other Show S01E02"),
                SeriesMatcher.parse("Show S01E01"),
            ),
        )
    }

    @Test
    fun seasonPremiereFollowsSeasonFinaleOnlyWhenItIsEpisodeOne() {
        assertTrue(
            SeriesMatcher.isNextEpisode(
                SeriesMatcher.parse("Show S03E01"),
                SeriesMatcher.parse("Show S02E24"),
            ),
        )
        assertFalse(
            SeriesMatcher.isNextEpisode(
                SeriesMatcher.parse("Show S03E04"),
                SeriesMatcher.parse("Show S02E24"),
            ),
        )
    }

    @Test
    fun moviesAreNeverNextEpisodes() {
        assertFalse(
            SeriesMatcher.isNextEpisode(
                SeriesMatcher.parse("Big Buck Bunny"),
                SeriesMatcher.parse("Big Buck Bunny"),
            ),
        )
    }
}

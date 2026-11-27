package com.streambuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFiltersTest {

    private fun item(
        id: Long,
        title: String,
        mediaType: MediaType = MediaType.MOVIE,
        status: LibraryStatus = LibraryStatus.WATCHLIST,
        year: Int? = 2022,
        rating: Double? = 7.5,
        genres: List<String> = listOf("Drama"),
        network: String? = "Indie",
        progressMs: Long = 0L,
        durationMs: Long = 100_000L,
        addedAt: Long = id * 100L,
        lastWatchedAt: Long? = null,
    ) = LibraryItem(
        id = id,
        tmdbId = id.toInt(),
        mediaType = mediaType,
        title = title,
        overview = "Overview for $title",
        posterPath = null,
        backdropPath = null,
        year = year,
        genres = genres,
        network = network,
        rating = rating,
        runtimeMinutes = 100,
        seasons = null,
        status = status,
        sourceUri = "https://example.test/$id.mp4",
        sourceLabel = null,
        infoHash = null,
        progressMs = progressMs,
        durationMs = durationMs,
        addedAt = addedAt,
        updatedAt = addedAt,
        lastWatchedAt = lastWatchedAt,
    )

    @Test
    fun `filters by text media type genre network and status`() {
        val items = listOf(
            item(1, "Alpha One", MediaType.MOVIE, LibraryStatus.WATCHLIST, genres = listOf("Sci-Fi"), network = "A24"),
            item(2, "Beta Show", MediaType.TV, LibraryStatus.WATCHING, genres = listOf("Drama"), network = "HBO"),
            item(3, "Gamma Two", MediaType.TV, LibraryStatus.WATCHED, genres = listOf("Sci-Fi"), network = "HBO"),
        )

        val filtered = LibraryFilters.apply(
            items,
            LibraryQuery(
                text = "gamma",
                mediaType = MediaType.TV,
                genre = "sci-fi",
                network = "hbo",
                status = LibraryStatus.WATCHED,
            ),
        )
        assertEquals(listOf(3L), filtered.map { it.id })
    }

    @Test
    fun `sorts by title year rating and recently watched`() {
        val items = listOf(
            item(1, "Charlie", year = 2019, rating = 6.5, lastWatchedAt = 10L),
            item(2, "Alpha", year = 2024, rating = 9.1, lastWatchedAt = 30L),
            item(3, "Bravo", year = 2021, rating = 8.0, lastWatchedAt = null),
        )

        assertEquals(listOf(2L, 3L, 1L), LibraryFilters.apply(items, LibraryQuery(sort = LibrarySort.TITLE)).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), LibraryFilters.apply(items, LibraryQuery(sort = LibrarySort.YEAR)).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), LibraryFilters.apply(items, LibraryQuery(sort = LibrarySort.RATING)).map { it.id })
        assertEquals(listOf(2L, 1L, 3L), LibraryFilters.apply(items, LibraryQuery(sort = LibrarySort.RECENTLY_WATCHED)).map { it.id })
    }

    @Test
    fun `continueWatching excludes nearly finished and unwatched titles`() {
        val items = listOf(
            item(1, "Midway", status = LibraryStatus.WATCHING, progressMs = 30_000, durationMs = 100_000, lastWatchedAt = 500),
            item(2, "Almost Done", status = LibraryStatus.WATCHING, progressMs = 97_000, durationMs = 100_000, lastWatchedAt = 600),
            item(3, "Just Started", status = LibraryStatus.WATCHING, progressMs = 1_000, durationMs = 100_000, lastWatchedAt = 700),
            item(4, "Watched", status = LibraryStatus.WATCHED, progressMs = 50_000, durationMs = 100_000, lastWatchedAt = 800),
        )

        assertEquals(listOf(1L), LibraryFilters.continueWatching(items).map { it.id })
    }

    @Test
    fun `collects distinct sorted genres and networks`() {
        val items = listOf(
            item(1, "One", genres = listOf("Drama", "Action"), network = "HBO"),
            item(2, "Two", genres = listOf("drama", "Comedy"), network = "hbo"),
            item(3, "Three", genres = listOf(""), network = " "),
        )

        assertEquals(listOf("Action", "Comedy", "Drama"), LibraryFilters.genres(items))
        assertEquals(listOf("HBO"), LibraryFilters.networks(items))
    }
}

package com.streambuddy.app.data

import java.util.Locale

enum class MediaType {
    MOVIE, TV;

    val label: String get() = if (this == MOVIE) "Movie" else "TV show"
}

enum class LibraryStatus {
    WATCHLIST, WATCHING, WATCHED;

    val label: String
        get() = when (this) {
            WATCHLIST -> "Watchlist"
            WATCHING -> "Watching"
            WATCHED -> "Watched"
        }
}

enum class LibrarySort { RECENTLY_ADDED, RECENTLY_WATCHED, TITLE, YEAR, RATING }

/**
 * A title the user owns or is authorised to stream. Metadata comes from TMDB;
 * the playable source is always supplied by the user (a magnet, direct stream
 * URL or local file), never discovered by the app.
 */
data class LibraryItem(
    val id: Long,
    val tmdbId: Int?,
    val mediaType: MediaType,
    val title: String,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val year: Int?,
    val genres: List<String>,
    val network: String?,
    val rating: Double?,
    val runtimeMinutes: Int?,
    val seasons: Int?,
    val status: LibraryStatus,
    val sourceUri: String?,
    val sourceLabel: String?,
    val infoHash: String?,
    val progressMs: Long,
    val durationMs: Long,
    val addedAt: Long,
    val updatedAt: Long,
    val lastWatchedAt: Long?,
) {
    val hasSource: Boolean get() = !sourceUri.isNullOrBlank()
    val yearLabel: String get() = year?.toString() ?: "—"
    val progressFraction: Double
        get() = if (durationMs <= 0) 0.0 else (progressMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
    val continueWatching: Boolean
        get() = status == LibraryStatus.WATCHING &&
            progressMs >= 5_000 &&
            (durationMs <= 0 || progressFraction < 0.95)
    val genreLabel: String get() = genres.joinToString(", ")
    val displayYearAndType: String
        get() = listOfNotNull(year?.toString(), mediaType.label, network).joinToString(" · ")
}

/** A search/detail result before it is added to the library. */
data class MetadataCandidate(
    val tmdbId: Int,
    val mediaType: MediaType,
    val title: String,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val year: Int?,
    val genres: List<String>,
    val network: String?,
    val rating: Double?,
    val runtimeMinutes: Int?,
    val seasons: Int?,
) {
    fun toLibraryItem(): LibraryItem {
        val now = System.currentTimeMillis()
        return LibraryItem(
            id = 0,
            tmdbId = tmdbId,
            mediaType = mediaType,
            title = title,
            overview = overview,
            posterPath = posterPath,
            backdropPath = backdropPath,
            year = year,
            genres = genres,
            network = network,
            rating = rating,
            runtimeMinutes = runtimeMinutes,
            seasons = seasons,
            status = LibraryStatus.WATCHLIST,
            sourceUri = null,
            sourceLabel = null,
            infoHash = null,
            progressMs = 0,
            durationMs = 0,
            addedAt = now,
            updatedAt = now,
            lastWatchedAt = null,
        )
    }
}

data class LibraryQuery(
    val text: String = "",
    val mediaType: MediaType? = null,
    val genre: String? = null,
    val network: String? = null,
    val status: LibraryStatus? = null,
    val sort: LibrarySort = LibrarySort.RECENTLY_ADDED,
)

/** Pure filtering/sorting so it can be unit tested without Android. */
object LibraryFilters {

    fun matches(item: LibraryItem, query: LibraryQuery): Boolean {
        if (query.mediaType != null && item.mediaType != query.mediaType) return false
        if (query.status != null && item.status != query.status) return false
        if (query.genre != null && item.genres.none { it.equals(query.genre, ignoreCase = true) }) {
            return false
        }
        if (query.network != null && !item.network.equals(query.network, ignoreCase = true)) {
            return false
        }
        val text = query.text.trim()
        if (text.isNotEmpty()) {
            val haystack = buildString {
                append(item.title).append(' ')
                append(item.overview).append(' ')
                append(item.genres.joinToString(" ")).append(' ')
                append(item.network.orEmpty()).append(' ')
                append(item.year?.toString().orEmpty())
            }.lowercase(Locale.US)
            if (!haystack.contains(text.lowercase(Locale.US))) return false
        }
        return true
    }

    fun apply(items: List<LibraryItem>, query: LibraryQuery): List<LibraryItem> = items
        .asSequence()
        .filter { matches(it, query) }
        .sortedWith(comparatorFor(query.sort))
        .toList()

    fun continueWatching(items: List<LibraryItem>): List<LibraryItem> = items
        .filter { it.continueWatching }
        .sortedByDescending { it.lastWatchedAt ?: 0L }

    fun watchlist(items: List<LibraryItem>): List<LibraryItem> =
        items.filter { it.status == LibraryStatus.WATCHLIST }

    fun recentlyWatched(items: List<LibraryItem>): List<LibraryItem> = items
        .filter { it.lastWatchedAt != null }
        .sortedByDescending { it.lastWatchedAt }

    fun genres(items: List<LibraryItem>): List<String> =
        items.flatMap { it.genres }.map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.US) }.sorted()

    fun networks(items: List<LibraryItem>): List<String> =
        items.mapNotNull { it.network?.trim()?.takeIf(String::isNotEmpty) }
            .distinctBy { it.lowercase(Locale.US) }.sorted()

    private fun comparatorFor(sort: LibrarySort): Comparator<LibraryItem> = when (sort) {
        LibrarySort.RECENTLY_ADDED -> compareByDescending { it.addedAt }
        LibrarySort.RECENTLY_WATCHED -> compareByDescending<LibraryItem> { it.lastWatchedAt ?: 0L }
            .thenByDescending { it.addedAt }
        LibrarySort.TITLE -> compareBy { it.title.lowercase(Locale.US) }
        LibrarySort.YEAR -> compareByDescending { it.year ?: 0 }
        LibrarySort.RATING -> compareByDescending { it.rating ?: 0.0 }
    }
}

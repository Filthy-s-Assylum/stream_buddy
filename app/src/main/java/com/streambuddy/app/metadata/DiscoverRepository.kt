package com.streambuddy.app.metadata

import com.streambuddy.app.data.MediaType
import com.streambuddy.app.data.MetadataCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The latest TMDB rows shown on the Discover tab. */
data class DiscoverFeed(
    val nowPlaying: List<MetadataCandidate> = emptyList(),
    val upcoming: List<MetadataCandidate> = emptyList(),
    val onTheAir: List<MetadataCandidate> = emptyList(),
    val trending: List<MetadataCandidate> = emptyList(),
    val fetchedAt: Long = 0L,
) {
    val isEmpty: Boolean
        get() = nowPlaying.isEmpty() && upcoming.isEmpty() && onTheAir.isEmpty() && trending.isEmpty()

    val total: Int
        get() = nowPlaying.size + upcoming.size + onTheAir.size + trending.size
}

/**
 * Owns the "latest" catalogue. TMDB is the only metadata source; magnets are
 * resolved separately and only when the user explicitly asks for one.
 */
class DiscoverRepository(
    private val metadata: MetadataRepository,
    private val torrentIndex: TorrentIndexClient = TorrentIndexClient(),
) {
    private val _feed = MutableStateFlow<DiscoverFeed?>(null)
    val feed: StateFlow<DiscoverFeed?> = _feed.asStateFlow()

    private val refreshMutex = Mutex()

    val hasKey: Boolean get() = metadata.hasKey

    /**
     * Refreshes all four TMDB rows. A single failing endpoint does not hide the
     * others: the feed is only an error when nothing could be loaded.
     */
    suspend fun refresh(force: Boolean = false): Result<DiscoverFeed> = refreshMutex.withLock {
        val cached = _feed.value
        if (!force && cached != null && !cached.isEmpty &&
            System.currentTimeMillis() - cached.fetchedAt < CACHE_TTL_MS
        ) {
            return Result.success(cached)
        }

        val nowPlaying = metadata.nowPlaying()
        val upcoming = metadata.upcoming()
        val onTheAir = metadata.onTheAir()
        val trending = metadata.trending()

        val anySuccess = listOf(nowPlaying, upcoming, onTheAir, trending).any { it.isSuccess }
        if (!anySuccess) {
            val error = listOf(nowPlaying, upcoming, onTheAir, trending)
                .firstNotNullOfOrNull { it.exceptionOrNull() }
            return Result.failure(error ?: IllegalStateException("TMDB did not return any listings"))
        }

        val feed = DiscoverFeed(
            nowPlaying = dedupe(nowPlaying.getOrDefault(emptyList())),
            upcoming = dedupe(upcoming.getOrDefault(emptyList())),
            onTheAir = dedupe(onTheAir.getOrDefault(emptyList())),
            trending = dedupe(trending.getOrDefault(emptyList())),
            fetchedAt = System.currentTimeMillis(),
        )
        _feed.value = feed
        return Result.success(feed)
    }

    suspend fun details(candidate: MetadataCandidate): Result<MetadataCandidate> =
        metadata.enrich(candidate)

    /** Resolves the IMDb id once and remembers it on the returned copy. */
    suspend fun withImdbId(candidate: MetadataCandidate): MetadataCandidate {
        if (!candidate.imdbId.isNullOrBlank()) return candidate
        val imdb = metadata.imdbId(candidate).getOrNull() ?: return candidate
        return candidate.copy(imdbId = imdb)
    }

    suspend fun releases(candidate: MetadataCandidate): Result<List<TorrentRelease>> {
        val enriched = withImdbId(candidate)
        return torrentIndex.releases(enriched, enriched.imdbId)
    }

    fun browseUrl(candidate: MetadataCandidate): String = TorrentIndexClient.browseUrl(candidate)

    private fun dedupe(items: List<MetadataCandidate>): List<MetadataCandidate> =
        items.asSequence()
            .filter { it.tmdbId > 0 && it.title.isNotBlank() }
            .distinctBy { "${it.mediaType}-${it.tmdbId}" }
            .sortedWith(
                compareByDescending<MetadataCandidate> { it.releaseDate.orEmpty() }
                    .thenByDescending { it.rating ?: 0.0 },
            )
            .toList()

    private companion object {
        const val CACHE_TTL_MS = 15L * 60L * 1_000L
    }
}

/** Convenience label for a catalogue row. */
fun MetadataCandidate.catalogTypeLabel(): String = when (mediaType) {
    MediaType.MOVIE -> "Movie"
    MediaType.TV -> "Series"
}

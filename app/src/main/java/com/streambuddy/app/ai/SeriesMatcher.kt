package com.streambuddy.app.ai

/**
 * Parses season/episode markers out of a title so the queue can tell "next episode" apart from
 * "something else entirely".
 *
 * Handles the shapes people actually paste into a queue: `Show S02E05`, `show.s02e05.1080p`,
 * `Show 2x05`, `Show - Episode 5`. Titles without a marker (movies, live channels) are treated as
 * their own series so a rewatch still counts as a signal.
 */
object SeriesMatcher {

    /** `season`/`episode` are `null` for titles without a marker. */
    data class Parsed(
        val seriesKey: String,
        val displayTitle: String,
        val season: Int?,
        val episode: Int?,
    ) {
        val hasEpisode: Boolean get() = episode != null
    }

    private val SXXEXX = Regex("(?i)\\bS(\\d{1,2})\\s*[._\\- ]?\\s*E(\\d{1,3})\\b")
    private val NXNN = Regex("(?i)\\b(\\d{1,2})\\s*x\\s*(\\d{2,3})\\b")
    private val EPISODE_WORD = Regex("(?i)\\b(?:episode|ep)\\s*(\\d{1,3})\\b")

    private val PLACEHOLDERS = Regex("[._]+")
    private val WHITESPACE = Regex("\\s+")
    private const val TRAILING_SEPARATORS = " -_:|–—"

    fun parse(rawTitle: String): Parsed {
        val title = rawTitle.trim()
        if (title.isEmpty()) return Parsed("", "", null, null)

        val cleaned = normalize(title)

        SXXEXX.find(cleaned)?.let { match ->
            return build(
                title = title,
                cleaned = cleaned,
                match = match,
                season = match.groupValues[1].toIntOrNull(),
                episode = match.groupValues[2].toIntOrNull(),
            )
        }

        NXNN.find(cleaned)?.let { match ->
            return build(
                title = title,
                cleaned = cleaned,
                match = match,
                season = match.groupValues[1].toIntOrNull(),
                episode = match.groupValues[2].toIntOrNull(),
            )
        }

        EPISODE_WORD.find(cleaned)?.let { match ->
            return build(
                title = title,
                cleaned = cleaned,
                match = match,
                season = null,
                episode = match.groupValues[1].toIntOrNull(),
            )
        }

        // No episode marker: the whole title is the series (a movie, a channel, a song).
        return Parsed(
            seriesKey = cleaned.lowercase().trim(),
            displayTitle = title.trim(*TRAILING_SEPARATORS.toCharArray()),
            season = null,
            episode = null,
        )
    }

    /**
     * `true` when [candidate] is the episode that follows [previous] in the same series.
     *
     * A season premiere counts as "next" after any episode of the previous season: without the
     * season length there is no way to know whether the finale was E10 or E24, and the cost of a
     * wrong guess is a two-minute warm start.
     */
    fun isNextEpisode(candidate: Parsed, previous: Parsed): Boolean {
        if (candidate.seriesKey.isEmpty() || candidate.seriesKey != previous.seriesKey) return false
        val candidateEpisode = candidate.episode ?: return false
        val previousEpisode = previous.episode ?: return false

        val candidateSeason = candidate.season
        val previousSeason = previous.season
        if (candidateSeason == null || previousSeason == null) return candidateEpisode == previousEpisode + 1
        if (candidateSeason == previousSeason) return candidateEpisode == previousEpisode + 1
        return candidateSeason == previousSeason + 1 && candidateEpisode == 1
    }

    private fun build(
        title: String,
        cleaned: String,
        match: MatchResult,
        season: Int?,
        episode: Int?,
    ): Parsed {
        val prefix = cleaned.substring(0, match.range.first)
        val display = prefix.trim(*TRAILING_SEPARATORS.toCharArray()).ifBlank {
            cleaned.substring(match.range.last + 1).trim(*TRAILING_SEPARATORS.toCharArray())
        }
        return Parsed(
            seriesKey = display.lowercase(),
            displayTitle = display.ifBlank { title },
            season = season,
            episode = episode,
        )
    }

    private fun normalize(title: String): String =
        title.replace(PLACEHOLDERS, " ").replace(WHITESPACE, " ").trim()
}

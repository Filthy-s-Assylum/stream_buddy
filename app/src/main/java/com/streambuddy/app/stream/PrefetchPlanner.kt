package com.streambuddy.app.stream

/**
 * Decides which segments of a media playlist to pre-cache for a "pre-play" window.
 *
 * * VOD: cache from the start of the playlist (that is what playback reads first).
 * * Live: cache the most recent segments — the ones the player will request next.
 *
 * The initialization segment (`#EXT-X-MAP`, used by fMP4 playlists) always comes first so the
 * cached data is immediately decodable.
 */
object PrefetchPlanner {

    /** Safety valve: never queue a runaway number of requests for a very long playlist. */
    const val MAX_SEGMENTS = 900

    fun plan(
        playlist: HlsMediaPlaylist,
        depthMs: Long,
        maxSegments: Int = MAX_SEGMENTS,
    ): List<HlsSegment> {
        if (playlist.segments.isEmpty()) return emptyList()

        val limit = maxSegments.coerceAtLeast(1)
        val selection = if (playlist.isLive) {
            lastSegments(playlist.segments, depthMs, limit)
        } else {
            firstSegments(playlist.segments, depthMs, limit)
        }
        return listOfNotNull(playlist.initializationSegment) + selection
    }

    private fun firstSegments(segments: List<HlsSegment>, depthMs: Long, maxSegments: Int): List<HlsSegment> {
        val selected = ArrayList<HlsSegment>(minOf(segments.size, maxSegments))
        var duration = 0L
        for (segment in segments) {
            if (selected.size >= maxSegments) break
            selected += segment
            duration += segment.durationMs
            if (duration >= depthMs) break
        }
        return selected
    }

    private fun lastSegments(segments: List<HlsSegment>, depthMs: Long, maxSegments: Int): List<HlsSegment> {
        val selected = ArrayDeque<HlsSegment>()
        var duration = 0L
        for (segment in segments.asReversed()) {
            if (selected.size >= maxSegments) break
            selected.addFirst(segment)
            duration += segment.durationMs
            if (duration >= depthMs) break
        }
        return selected.toList()
    }
}

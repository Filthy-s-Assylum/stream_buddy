package com.streambuddy.app.stream

/** One entry of an HLS multivariant (master) playlist. */
data class HlsVariant(
    val url: String,
    val bandwidth: Long,
    val height: Int?,
    val codecs: String?,
)

/**
 * One media segment.
 *
 * [byteRangeOffset] is `null` for whole-resource segments; when it is non-null the segment lives
 * inside a shared resource (e.g. a single `.ts` file addressed with `#EXT-X-BYTERANGE`).
 */
data class HlsSegment(
    val url: String,
    val durationMs: Long,
    val byteRangeOffset: Long? = null,
    val byteRangeLength: Long? = null,
    val isInitialization: Boolean = false,
) {
    val isByteRange: Boolean get() = byteRangeLength != null
}

/** A parsed media playlist (the playlist that actually lists segments). */
data class HlsMediaPlaylist(
    val segments: List<HlsSegment>,
    val initializationSegment: HlsSegment?,
    val totalDurationMs: Long,
    val targetDurationMs: Long,
    val isLive: Boolean,
    val mediaSequence: Int = 0,
)

sealed interface HlsPlaylist {
    data class Master(val variants: List<HlsVariant>) : HlsPlaylist
    data class Media(val playlist: HlsMediaPlaylist) : HlsPlaylist
}

/** A `#EXT-X-BYTERANGE` value: `<length>[@<offset>]`. */
data class HlsByteRange(val length: Long, val offset: Long?)

class PlaylistParseException(message: String) : Exception(message)

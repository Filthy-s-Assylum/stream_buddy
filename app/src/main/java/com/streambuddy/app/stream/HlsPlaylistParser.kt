package com.streambuddy.app.stream

import java.net.URI
import kotlin.math.roundToLong

/**
 * Minimal, dependency-free parser for the parts of RFC 8216 (HTTP Live Streaming) that
 * pre-caching needs: variants of a master playlist, and the segment list of a media playlist.
 *
 * Deliberately not a full HLS implementation — encryption keys, `#EXT-X-DISCONTINUITY`
 * grouping and alternate renditions are ignored, because media playback is handled by ExoPlayer.
 */
object HlsPlaylistParser {

    private const val ENDLIST = "#EXT-X-ENDLIST"
    private const val TARGET_DURATION = "#EXT-X-TARGETDURATION:"
    private const val PLAYLIST_TYPE = "#EXT-X-PLAYLIST-TYPE:"
    private const val MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:"
    private const val BYTERANGE = "#EXT-X-BYTERANGE:"
    private const val MAP = "#EXT-X-MAP:"
    private const val EXTINF = "#EXTINF:"
    private const val STREAM_INF = "#EXT-X-STREAM-INF:"

    /** Parses [text] fetched from [baseUrl]. Throws [PlaylistParseException] on malformed input. */
    fun parse(text: String, baseUrl: String): HlsPlaylist {
        val lines = text.removePrefix("\uFEFF").lines()

        val header = lines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (!header.startsWith("#EXTM3U", ignoreCase = true)) {
            throw PlaylistParseException("Not an HLS playlist: missing #EXTM3U header")
        }

        val hasVariants = lines.any { it.trim().startsWith(STREAM_INF, ignoreCase = true) }
        return if (hasVariants) parseMaster(lines, baseUrl) else parseMedia(lines, baseUrl)
    }

    private fun parseMaster(lines: List<String>, baseUrl: String): HlsPlaylist.Master {
        val variants = mutableListOf<HlsVariant>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            index++
            if (!line.startsWith(STREAM_INF, ignoreCase = true)) continue

            val attributes = HlsAttributes.parse(line.substringAfter(':'))

            var uri: String? = null
            while (index < lines.size) {
                val candidate = lines[index].trim()
                index++
                if (candidate.isEmpty() || candidate.startsWith("#")) continue
                uri = candidate
                break
            }
            if (uri == null) continue

            val resolution = attributes["RESOLUTION"]
            variants += HlsVariant(
                url = resolveUrl(baseUrl, uri),
                bandwidth = attributes["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                height = resolution?.substringAfter('x', "")?.toIntOrNull(),
                codecs = attributes["CODECS"]?.takeIf { it.isNotBlank() },
            )
        }

        if (variants.isEmpty()) {
            throw PlaylistParseException("Master playlist does not contain any playable variant")
        }
        return HlsPlaylist.Master(variants)
    }

    private fun parseMedia(lines: List<String>, baseUrl: String): HlsPlaylist.Media {
        val segments = mutableListOf<HlsSegment>()
        var initialization: HlsSegment? = null
        var targetDurationMs = 0L
        var mediaSequence = 0
        var ended = false
        var vod = false

        var pendingDurationMs: Long? = null
        var pendingByteRange: HlsByteRange? = null
        var sawExtInf = false

        // Byte-range offset/end of the previously emitted segment, for `#EXT-X-BYTERANGE` forms
        // that omit the offset (the range continues the previously listed sub-range).
        var lastRangeUrl: String? = null
        var lastRangeEnd = 0L

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith(EXTINF, ignoreCase = true) -> {
                    // `#EXTINF:<duration>,[<title>]`
                    pendingDurationMs = secondsToMillis(line.substringAfter(':').substringBefore(','))
                    sawExtInf = true
                }

                line.startsWith(BYTERANGE, ignoreCase = true) -> {
                    pendingByteRange = parseByteRange(line.substringAfter(':'))
                }

                line.startsWith(MAP, ignoreCase = true) -> {
                    val attributes = HlsAttributes.parse(line.substringAfter(':'))
                    val uri = attributes["URI"]
                    if (!uri.isNullOrBlank()) {
                        val range = attributes["BYTERANGE"]?.let { parseByteRange(it) }
                        initialization = HlsSegment(
                            url = resolveUrl(baseUrl, uri),
                            durationMs = 0L,
                            byteRangeOffset = range?.offset,
                            byteRangeLength = range?.length,
                            isInitialization = true,
                        )
                    }
                }

                line.startsWith(TARGET_DURATION, ignoreCase = true) ->
                    targetDurationMs = secondsToMillis(line.substringAfter(':'))

                line.startsWith(MEDIA_SEQUENCE, ignoreCase = true) ->
                    mediaSequence = line.substringAfter(':').trim().toIntOrNull() ?: 0

                line.startsWith(PLAYLIST_TYPE, ignoreCase = true) ->
                    vod = line.substringAfter(':').trim().equals("VOD", ignoreCase = true)

                line.startsWith(ENDLIST, ignoreCase = true) -> ended = true

                line.startsWith("#") -> Unit // other tags are irrelevant here

                else -> {
                    val durationMs = pendingDurationMs ?: 0L
                    val url = resolveUrl(baseUrl, line)
                    val range = pendingByteRange

                    val offset: Long?
                    val length: Long?
                    if (range == null) {
                        offset = null
                        length = null
                    } else {
                        length = range.length
                        offset = when {
                            range.offset != null -> range.offset
                            lastRangeUrl == url -> lastRangeEnd
                            else -> 0L
                        }
                    }

                    segments += HlsSegment(
                        url = url,
                        durationMs = durationMs,
                        byteRangeOffset = offset,
                        byteRangeLength = length,
                    )

                    if (length != null) {
                        lastRangeUrl = url
                        lastRangeEnd = (offset ?: 0L) + length
                    }

                    pendingDurationMs = null
                    pendingByteRange = null
                }
            }
        }

        if (!sawExtInf && segments.isEmpty()) {
            throw PlaylistParseException("Playlist contains neither variants nor segments")
        }

        return HlsPlaylist.Media(
            HlsMediaPlaylist(
                segments = segments,
                initializationSegment = initialization,
                totalDurationMs = segments.sumOf { it.durationMs },
                targetDurationMs = targetDurationMs,
                isLive = !ended && !vod,
                mediaSequence = mediaSequence,
            ),
        )
    }

    /** Parses `<length>[@<offset>]`. */
    fun parseByteRange(raw: String): HlsByteRange? {
        val trimmed = raw.trim().trim('"')
        val lengthPart = trimmed.substringBefore('@').trim()
        val length = lengthPart.toLongOrNull() ?: return null
        if (length <= 0) return null
        val offset = if (trimmed.contains('@')) trimmed.substringAfter('@').trim().toLongOrNull() else null
        return HlsByteRange(length = length, offset = offset)
    }

    private fun secondsToMillis(raw: String): Long {
        val seconds = raw.trim().toDoubleOrNull() ?: return 0L
        if (seconds.isNaN() || seconds < 0) return 0L
        return (seconds * 1000.0).roundToLong()
    }

    /**
     * Resolves a playlist URI reference against the playlist URL, including the awkward cases
     * `java.net.URI` rejects (unescaped spaces, `|`, non-ASCII) where a manual fallback is needed.
     */
    fun resolveUrl(baseUrl: String, reference: String): String {
        val ref = reference.trim()
        if (ref.isEmpty()) return baseUrl

        if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) return ref

        try {
            val resolved = URI(baseUrl).resolve(ref)
            if (resolved != null) return resolved.toString()
        } catch (_: Exception) {
            // fall through to the manual resolver
        }

        return when {
            ref.startsWith("//") -> "${schemeOf(baseUrl)}:$ref"
            ref.startsWith("/") -> "${originOf(baseUrl)}$ref"
            else -> baseUrl.substringBeforeLast('/') + "/" + ref
        }
    }

    private fun schemeOf(url: String): String = url.substringBefore(':', "https")

    private fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url.substringBefore('/')
        val hostEnd = url.indexOf('/', schemeEnd + 3)
        return if (hostEnd < 0) url else url.substring(0, hostEnd)
    }
}

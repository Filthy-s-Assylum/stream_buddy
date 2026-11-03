package com.streambuddy.app.stream

/**
 * The container/protocol a stream URL points at.
 *
 * Detection is intentionally cheap (path extension based) because it runs on every list bind;
 * when the extension is inconclusive the pre-cache engine probes the response headers.
 */
enum class StreamKind {
    /** HLS (`.m3u8`). Segmented, cacheable, common for live and VOD. */
    HLS,

    /** MPEG-DASH (`.mpd`). Playback supported; pre-caching is not implemented. */
    DASH,

    /** A single progressive file (`.mp4`, `.mkv`, ...). */
    PROGRESSIVE,

    /** A `magnet:` link: resolved and streamed by the built-in BitTorrent engine. */
    TORRENT,

    /** Unknown extension; the engine sniffs the `Content-Type` before deciding. */
    UNKNOWN;

    val label: String
        get() = when (this) {
            HLS -> "HLS"
            DASH -> "DASH"
            PROGRESSIVE -> "File"
            TORRENT -> "Torrent"
            UNKNOWN -> "Unknown"
        }

    companion object {
        private val HLS_EXTENSIONS = setOf("m3u8", "m3u")
        private val DASH_EXTENSIONS = setOf("mpd")
        private val PROGRESSIVE_EXTENSIONS = setOf(
            "mp4", "m4v", "m4a", "mov", "mkv", "webm", "ts", "mts", "m2ts", "m4s",
            "mp3", "aac", "flac", "ogg", "opus", "wav", "avi",
        )

        /** Infers the kind from a URL's path, ignoring query strings and fragments. */
        fun fromUrl(url: String): StreamKind {
            val path = url.substringBefore('?').substringBefore('#').trim()
            val extension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return when {
                extension in HLS_EXTENSIONS -> HLS
                extension in DASH_EXTENSIONS -> DASH
                extension in PROGRESSIVE_EXTENSIONS -> PROGRESSIVE
                else -> UNKNOWN
            }
        }

        /** Resolves the enum from its stored (database) name, tolerant of `null`/legacy values. */
        fun fromName(name: String?): StreamKind =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN

        /** Maps an HTTP `Content-Type` (or file extension hint) onto a kind. */
        fun fromContentType(contentType: String?): StreamKind? {
            val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
            if (type.isEmpty()) return null
            return when {
                type.contains("mpegurl") -> HLS
                type.contains("dash+xml") -> DASH
                type.startsWith("video/") || type.startsWith("audio/") -> PROGRESSIVE
                else -> null
            }
        }
    }
}

/** Lifecycle of a queue entry's pre-cache job. Stored by name in Room. */
enum class CacheState {
    /** Added to the queue, no caching run yet. */
    QUEUED,

    /** A pre-cache run is in progress. */
    CACHING,

    /** The requested pre-play window is fully cached. */
    READY,

    /** Some data is cached; the run stopped early or partially failed. */
    PARTIAL,

    /** The run failed (network, parse, server). Retryable. */
    FAILED,

    /** Payback is possible but pre-caching is not supported (DASH, live-only, DRM). */
    UNSUPPORTED;

    val isRunning: Boolean get() = this == CACHING

    val label: String
        get() = when (this) {
            QUEUED -> "Queued"
            CACHING -> "Caching"
            READY -> "Ready"
            PARTIAL -> "Partial"
            FAILED -> "Failed"
            UNSUPPORTED -> "Playback only"
        }

    companion object {
        fun fromName(name: String?): CacheState =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: QUEUED
    }
}

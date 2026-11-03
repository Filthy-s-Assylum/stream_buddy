package com.streambuddy.app.data

enum class StreamKind {
    HLS, DASH, MP4, LOCAL, TORRENT;

    val isAdaptive: Boolean get() = this == HLS || this == DASH
    val isTorrent: Boolean get() = this == TORRENT
    val label: String
        get() = when (this) {
            HLS -> "HLS"
            DASH -> "DASH"
            MP4 -> "MP4"
            LOCAL -> "Local"
            TORRENT -> "Torrent"
        }
}

enum class PlaybackMode {
    WATCH_NOW, BUFFER_AHEAD, DOWNLOAD_FIRST;

    val label: String
        get() = when (this) {
            WATCH_NOW -> "Watch now"
            BUFFER_AHEAD -> "Buffer ahead"
            DOWNLOAD_FIRST -> "Download first"
        }
}

enum class OptimizerMode {
    RULES, ADAPTIVE;

    val label: String
        get() = when (this) {
            RULES -> "Rules baseline"
            ADAPTIVE -> "Adaptive model"
        }
}

enum class RiskLevel { LOW, MEDIUM, HIGH }

enum class PolicySource { RULES, MODEL, MODEL_FALLBACK }

data class StreamItem(
    val id: Long,
    val title: String,
    val uri: String,
    val kind: StreamKind,
    val mode: PlaybackMode,
    val addedAt: Long,
    val lastPlayedAt: Long?,
    val cachedBytes: Long,
    val downloadId: String?,
    val infoHash: String?,
)

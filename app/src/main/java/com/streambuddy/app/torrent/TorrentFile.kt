package com.streambuddy.app.torrent

/**
 * One file inside a torrent, described well enough to decide whether it is playable and how good
 * it is. Built from libtorrent's file storage once the metadata (BEP 9) arrives.
 */
data class TorrentFile(
    val index: Int,
    val path: String,
    val sizeBytes: Long,
    /** Byte offset of this file inside the torrent's global address space. */
    val offsetInTorrent: Long,
) {
    val name: String get() = path.substringAfterLast('/')

    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()

    val isVideo: Boolean get() = extension in VIDEO_EXTENSIONS

    /** Trailers, samples, featurettes: small files publishers add next to the main feature. */
    val isExtras: Boolean
        get() {
            val lower = name.lowercase()
            return EXTRA_MARKERS.any { lower.contains(it) }
        }

    /** True for files a phone video player can realistically open. */
    val isPlayable: Boolean get() = isVideo && !isExtras && !name.startsWith("._", ignoreCase = true)

    /** Height in pixels advertised in the name (`1080p`, `2160p`, …), or `null`. */
    val advertisedHeight: Int?
        get() = RESOLUTION_PATTERN.find(name)?.let { match ->
            val raw = match.groupValues[1].lowercase()
            when (raw) {
                "4k" -> 2160
                "8k" -> 4320
                "2k" -> 1440
                else -> raw.removeSuffix("p").toIntOrNull()?.takeIf { it in 240..4320 }
            }
        }

    val isHevc: Boolean
        get() {
            val lower = name.lowercase()
            return lower.contains("hevc") || lower.contains("h265") || lower.contains("h.265") ||
                lower.contains("x265")
        }

    val isHdr: Boolean
        get() {
            val lower = name.lowercase()
            return lower.contains("hdr") || lower.contains("dolby.vision") || lower.contains("dovi")
        }

    val isRemuxOrRaw: Boolean
        get() {
            val lower = name.lowercase()
            return lower.contains("remux") || lower.contains("bluray") || lower.contains("bdrip")
        }

    companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "ts", "m2ts")

        private val EXTRA_MARKERS = listOf("sample", "trailer", "featurette", "behind.the.scenes", "proof")
        private val RESOLUTION_PATTERN = Regex("(?i)(\\d{3,4}p|4k|8k|2k)")
    }
}

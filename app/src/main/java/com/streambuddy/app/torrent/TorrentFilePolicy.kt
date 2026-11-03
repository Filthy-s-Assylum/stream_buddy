package com.streambuddy.app.torrent

import java.util.Locale

/**
 * A torrent file as exposed by libtorrent. [path] is the raw metadata path and
 * is never used to build a filesystem path directly; [safePath] is validated
 * first and later resolved under the app's private torrent directory.
 */
data class SafeTorrentFile(
    val index: Int,
    val path: String,
    val size: Long,
    val safePath: String?,
    val kind: FileKind,
    val rejection: String? = null,
) {
    val playable: Boolean get() = rejection == null && (kind == FileKind.VIDEO || kind == FileKind.AUDIO)
    val fileName: String get() = safePath?.substringAfterLast('/') ?: path.substringAfterLast('/')
}

enum class FileKind { VIDEO, AUDIO, SUBTITLE, OTHER }

/**
 * Security policy for torrent metadata. A malicious torrent must not be able
 * to escape the app sandbox, overwrite files, exhaust path limits, or make the
 * player open executable content.
 */
object TorrentFilePolicy {

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "ts", "m2ts", "mts",
        "mpg", "mpeg", "ogv", "flv", "3gp", "wmv", "divx",
    )
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "oga")
    private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub", "idx")
    private val WINDOWS_RESERVED = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    private const val MAX_PATH_LENGTH = 900
    private const val MAX_SEGMENTS = 24
    private const val MAX_SEGMENT_LENGTH = 180

    fun classify(path: String): FileKind {
        val extension = path.substringAfterLast('.', "").lowercase(Locale.US)
        return when (extension) {
            in VIDEO_EXTENSIONS -> FileKind.VIDEO
            in AUDIO_EXTENSIONS -> FileKind.AUDIO
            in SUBTITLE_EXTENSIONS -> FileKind.SUBTITLE
            else -> FileKind.OTHER
        }
    }

    /**
     * Returns a sanitized relative path using '/' separators, or null with a
     * human-readable reason when the entry must not be written to disk.
     */
    fun sanitize(path: String, index: Int): SafeTorrentFile {
        val kind = classify(path)
        fun rejected(reason: String) = SafeTorrentFile(index, path, 0, null, kind, reason)

        if (path.isBlank()) return rejected("Empty path")
        if (path.length > MAX_PATH_LENGTH) return rejected("Path is too long")
        if (path.contains('\u0000') || path.any { it.code < 0x20 }) {
            return rejected("Path contains control characters")
        }
        if (path.startsWith('/') || path.startsWith('\\')) return rejected("Absolute paths are rejected")
        if (path.contains('\\')) return rejected("Backslash paths are rejected")
        if (Regex("^[A-Za-z]:").containsMatchIn(path)) return rejected("Drive-letter paths are rejected")

        val segments = path.split('/')
        if (segments.size > MAX_SEGMENTS) return rejected("Path is nested too deeply")
        for (segment in segments) {
            if (segment.isEmpty()) return rejected("Path contains an empty segment")
            if (segment == "." || segment == "..") return rejected("Path traversal is rejected")
            if (segment.length > MAX_SEGMENT_LENGTH) return rejected("File name is too long")
            val base = segment.substringBeforeLast('.', segment)
            if (segment.endsWith(' ') || segment.endsWith('.') || base.endsWith(' ') || base.endsWith('.')) {
                return rejected("File name ends with a space or dot")
            }
            val base = segment.substringBeforeLast('.')
            if (base.endsWith(' ') || base.endsWith('.')) {
                return rejected("File name has a trailing space or dot before the extension")
            }
            if (segment.any { it in ILLEGAL_CHARACTERS }) return rejected("File name has illegal characters")
            val stem = segment.substringBefore('.').lowercase(Locale.US)
            if (stem in WINDOWS_RESERVED) return rejected("Reserved file name")
        }
        return SafeTorrentFile(index, path, 0, segments.joinToString("/"), kind)
    }

    /**
     * Chooses the main video: the largest video file, ignoring obvious sample
     * clips unless nothing else exists. Audio-only torrents are supported as a
     * fallback so podcasts and music are not rejected.
     */
    fun chooseMainFile(files: List<SafeTorrentFile>): SafeTorrentFile? {
        val playable = files.filter { it.playable && it.rejection == null }
        val videos = playable.filter { it.kind == FileKind.VIDEO }
        if (videos.isEmpty()) return playable.maxByOrNull { it.size }
        val nonSamples = videos.filterNot { isSample(it) }
        val pool = if (nonSamples.isNotEmpty()) nonSamples else videos
        return pool.maxByOrNull { it.size }
    }

    fun isSample(file: SafeTorrentFile): Boolean {
        val name = file.fileName.lowercase(Locale.US)
        return name.contains("sample") || name.contains("trailer") || name.contains("extras")
    }

    fun subtitles(files: List<SafeTorrentFile>): List<SafeTorrentFile> =
        files.filter { it.rejection == null && it.kind == FileKind.SUBTITLE }

    /** A bounded, filesystem-safe display name used for the torrent directory. */
    fun safeDirectoryName(infoHash: String, name: String?): String {
        val cleaned = (name ?: "")
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .trim('.')
            .take(80)
            .ifBlank { "torrent" }
        return "${infoHash.take(16).lowercase(Locale.US)}-$cleaned"
    }

    private val ILLEGAL_CHARACTERS = setOf(
        '<', '>', ':', '"', '|', '?', '*', '\u007f',
    )
}

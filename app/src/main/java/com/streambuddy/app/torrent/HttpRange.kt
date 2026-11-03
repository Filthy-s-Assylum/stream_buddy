package com.streambuddy.app.torrent

/**
 * A single HTTP byte range. End is inclusive, matching the HTTP header.
 * Multi-range requests are intentionally rejected: the loopback server only
 * needs single ranges for media players and single ranges keep memory bounded.
 */
data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1

    fun contentRange(totalSize: Long): String = "bytes $start-$endInclusive/$totalSize"
}

sealed interface RangeParse {
    /** No Range header was supplied, or the header was blank. */
    data object Full : RangeParse

    data class Single(val range: ByteRange) : RangeParse

    /** Malformed, unsatisfiable or multi-range; the server answers 416. */
    data class Invalid(val reason: String) : RangeParse
}

object HttpRange {

    /**
     * Parses `Range: bytes=start-end`. The returned end is clamped to the last
     * byte of the resource. A start beyond the resource is unsatisfiable.
     */
    fun parse(header: String?, totalSize: Long): RangeParse {
        if (header.isNullOrBlank()) return RangeParse.Full
        if (totalSize <= 0) return RangeParse.Invalid("empty resource")
        val trimmed = header.trim()
        val separator = trimmed.indexOf('=')
        if (separator <= 0 || !trimmed.substring(0, separator).trim().equals("bytes", ignoreCase = true)) {
            return RangeParse.Invalid("unsupported range unit")
        }
        val spec = trimmed.substring(separator + 1).trim()
        if (spec.isEmpty()) return RangeParse.Invalid("empty range")
        if (spec.contains(',')) return RangeParse.Invalid("multi-range unsupported")

        val dash = spec.indexOf('-')
        if (dash < 0) return RangeParse.Invalid("missing dash")
        val first = spec.substring(0, dash).trim()
        val second = spec.substring(dash + 1).trim()

        return if (first.isEmpty()) {
            // Suffix range: last N bytes.
            val suffix = second.toLongOrNull() ?: return RangeParse.Invalid("invalid suffix")
            if (suffix <= 0) return RangeParse.Invalid("invalid suffix")
            val start = (totalSize - suffix).coerceAtLeast(0)
            RangeParse.Single(ByteRange(start, totalSize - 1))
        } else {
            val start = first.toLongOrNull() ?: return RangeParse.Invalid("invalid start")
            if (start < 0 || start >= totalSize) return RangeParse.Invalid("start out of bounds")
            val end = if (second.isEmpty()) {
                totalSize - 1
            } else {
                val parsed = second.toLongOrNull() ?: return RangeParse.Invalid("invalid end")
                if (parsed < start) return RangeParse.Invalid("end before start")
                parsed.coerceAtMost(totalSize - 1)
            }
            RangeParse.Single(ByteRange(start, end))
        }
    }
}

package com.streambuddy.app.torrent

/**
 * Decides which file inside a torrent to play, and explains why.
 *
 * This is the "optimal quality" half of the torrent feature: a transparent scoring model over the
 * device's real capabilities (screen, decoders, storage, connection) rather than a fixed ranking.
 * Pulling a 4K remux onto a 1080p phone on a metered connection is the failure mode it exists to
 * avoid, and every choice comes with the reasons behind it so it can be argued with.
 */
object QualitySelector {

    /** What the device can actually show/decode. */
    data class DeviceProfile(
        val screenWidthPx: Int,
        val screenHeightPx: Int,
        /** Widest video the hardware decoder accepts, from MediaCodec; 0 when unknown. */
        val maxDecodeWidth: Int = 0,
        val maxDecodeHeight: Int = 0,
        val supportsHevc: Boolean = true,
        val supportsHdr: Boolean = false,
        val availableStorageBytes: Long = Long.MAX_VALUE,
    ) {
        /**
         * Height in video lines this device can usefully render.
         *
         * A phone held in portrait plays video across its width, so the short side of the display
         * is the number to compare a `1080p` file against — not the 2400-pixel long side.
         */
        val effectiveHeight: Int
            get() {
                val shortest = minOf(screenWidthPx, screenHeightPx).takeIf { it > 0 } ?: return 1080
                if (maxDecodeHeight <= 0) return shortest
                return minOf(maxDecodeHeight, shortest)
            }
    }

    /** What the network can realistically deliver. */
    data class NetworkProfile(
        val metered: Boolean = false,
        /** Measured or estimated downstream, bits per second. 0 when unknown. */
        val downstreamBps: Long = 0L,
    )

    /** One file, scored. */
    data class Suggestion(
        val file: TorrentFile,
        val score: Double,
        val reasons: List<String>,
    ) {
        val height: Int? get() = file.advertisedHeight
    }

    data class Selection(
        val chosen: Suggestion,
        /** Everything playable, best first — the UI offers these as "try 720p instead". */
        val alternatives: List<Suggestion>,
    )

    /** Storage headroom kept free so the download cannot fill the device. */
    private const val STORAGE_HEADROOM = 1.15

    /** Below this the connection cannot sustain even a 720p stream with any buffer. */
    private const val MIN_STREAMING_BPS = 1_500_000L

    /**
     * Picks the best playable file. Returns `null` when the torrent has no video a phone can open
     * (a music pack, a game, an archive).
     */
    fun select(
        files: List<TorrentFile>,
        device: DeviceProfile,
        network: NetworkProfile,
    ): Selection? {
        val scored = files
            .filter { it.isPlayable }
            .map { file -> score(file, device, network) }
            .sortedWith(
                compareByDescending<Suggestion> { it.score }
                    .thenBy { it.file.sizeBytes },
            )
        val usable = scored.filter { it.score > 0.0 }
        if (usable.isEmpty()) return null
        return Selection(chosen = usable.first(), alternatives = usable)
    }

    /**
     * Next sensible step down from [current] when playback cannot keep up: the best-scoring file
     * that is meaningfully smaller. `null` when there is nothing smaller left.
     */
    fun downgrade(
        candidates: List<Suggestion>,
        current: Suggestion,
    ): Suggestion? = candidates
        .filter { it.file.index != current.file.index && it.file.sizeBytes < current.file.sizeBytes }
        .maxByOrNull { it.score }

    private fun score(
        file: TorrentFile,
        device: DeviceProfile,
        network: NetworkProfile,
    ): Suggestion {
        val reasons = mutableListOf<String>()
        var score = 1.0

        // 1. Can this device decode it at all?
        val advertisedHeight = file.advertisedHeight
        if (file.isHevc && !device.supportsHevc) {
            return Suggestion(file, 0.0, listOf("HEVC video, which this device cannot decode"))
        }
        if (file.isHdr && !device.supportsHdr) {
            score -= 0.15
            reasons += "HDR on an SDR screen (washed out colours)"
        }

        // 2. Does it fit on the device and in storage?
        val storageLimit = (device.availableStorageBytes / STORAGE_HEADROOM).toLong()
        if (file.sizeBytes > storageLimit) {
            return Suggestion(
                file,
                0.0,
                listOf("needs ${file.sizeBytes / 1_073_741_824.0} GiB, more than is free"),
            )
        }

        // 3. Resolution: match the screen, never waste data above it.
        val target = device.effectiveHeight
        if (advertisedHeight != null) {
            when {
                advertisedHeight > target + 120 -> {
                    // Above the screen: pure waste of bandwidth and storage.
                    val overshoot = advertisedHeight - target
                    score -= 0.35 + (overshoot / 2160.0).coerceAtMost(0.4)
                    reasons += "${advertisedHeight}p is above this screen (${target}p) — extra data, no extra detail"
                }

                advertisedHeight >= target - 240 -> {
                    score += 0.4
                    reasons += "${advertisedHeight}p matches this screen (${target}p)"
                }

                else -> {
                    val shortfall = (target - advertisedHeight) / target.toDouble()
                    score -= shortfall * 0.8
                    if (shortfall > 0.25) reasons += "only ${advertisedHeight}p on a ${target}p screen"
                }
            }
        } else {
            // No resolution in the name: judge by size, which correlates with bitrate.
            score += if (file.sizeBytes > 1_500_000_000L) -0.05 else 0.05
        }

        // 4. Network: a metered or slow link justifies dropping a tier.
        if (network.metered) {
            score -= if (file.sizeBytes > 2_000_000_000L) 0.35 else 0.05
            if (file.sizeBytes > 2_000_000_000L) reasons += "metered connection — prefer a smaller file"
        }
        if (network.downstreamBps in 1..MIN_STREAMING_BPS) {
            score -= 0.2
            reasons += "slow connection (${describeRate(network.downstreamBps)})"
        }

        // 5. Container: MP4 is seek-friendly everywhere; MKV is fine but heavier to scrub.
        if (file.extension == "mp4" || file.extension == "m4v") {
            score += 0.05
        } else if (file.extension == "mkv") {
            score -= 0.02
        }

        // 6. Remuxes are huge and usually the wrong choice on a phone.
        if (file.isRemuxOrRaw && file.sizeBytes > 4_000_000_000L) {
            score -= 0.2
            reasons += "full remux — very large"
        }

        if (reasons.isEmpty()) reasons += "no resolution marker; chosen for size and container"
        // Soft penalties can push a file below zero; it is still playable, so keep it as a fallback
        // instead of pretending the torrent has no video. Hard rejects above return exactly 0.0.
        return Suggestion(file, score.coerceAtLeast(0.01), reasons)
    }

    private fun describeRate(bitsPerSecond: Long): String = when {
        bitsPerSecond >= 1_000_000L ->
            String.format(java.util.Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000.0)

        else -> "${bitsPerSecond / 1000} Kbps"
    }
}

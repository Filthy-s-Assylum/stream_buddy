package com.streambuddy.app.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.streambuddy.app.cache.PlaybackCache
import com.streambuddy.app.torrent.TorrentUiState
import kotlin.math.max
import kotlin.math.min

data class PlaybackStatsUi(
    val bufferSeconds: Double = 0.0,
    val throughputBps: Double = 0.0,
    val bitrateBps: Long = 0L,
    val droppedFrames: Int = 0,
    val stallCount: Int = 0,
    val stallMs: Long = 0L,
    val bytesLoaded: Long = 0L,
    val peers: Int = 0,
    val torrentDownloadBps: Long = 0L,
    val torrentUploadBps: Long = 0L,
    val torrentProgress: Double = 0.0,
    val cacheUsedBytes: Long = 0L,
    val cacheMaxBytes: Long = 0L,
    val selectedTracks: String = "",
) {
    val cacheFraction: Double
        get() = if (cacheMaxBytes <= 0) 0.0 else (cacheUsedBytes.toDouble() / cacheMaxBytes).coerceIn(0.0, 1.0)
}

/**
 * Local telemetry. Counters exist only in memory; nothing is uploaded.
 *
 * "Throughput" is measured from real completed loads, never guessed from the
 * user's connection type. If no load has completed yet the value is 0 and the
 * UI shows "—".
 */
class PlaybackAnalytics {

    private var sessionStartedAt = 0L
    private var stallStartedAt = 0L
    private var stalled = false
    private var stallCount = 0
    private var stallMs = 0L
    private var bytesLoaded = 0L
    private var throughputBps = 0.0
    private var droppedFrames = 0
    private var bitrateBps = 0L
    private var selectedTracks = ""
    private var lastCacheRefresh = 0L
    private var cachedUsage = 0L
    private var cachedMax = 0L

    fun reset() {
        sessionStartedAt = System.currentTimeMillis()
        stallStartedAt = 0L
        stalled = false
        stallCount = 0
        stallMs = 0L
        bytesLoaded = 0L
        throughputBps = 0.0
        droppedFrames = 0
        bitrateBps = 0L
        selectedTracks = ""
        cachedUsage = 0L
        cachedMax = 0L
        lastCacheRefresh = 0L
    }

    fun onLoadCompleted(bytes: Long, elapsedMs: Long) {
        if (bytes <= 0) return
        bytesLoaded = safeAdd(bytesLoaded, bytes)
        if (elapsedMs > 0) {
            val sample = (bytes.toDouble() * 8_000.0) / elapsedMs.toDouble()
            throughputBps = if (throughputBps <= 0.0) sample else throughputBps * 0.65 + sample * 0.35
        }
    }

    fun onBandwidthEstimate(totalBytes: Long, totalMs: Long) {
        if (totalBytes <= 0 || totalMs <= 0) return
        val sample = (totalBytes.toDouble() * 8_000.0) / totalMs.toDouble()
        if (sample.isFinite() && sample > 0) {
            throughputBps = if (throughputBps <= 0.0) sample else throughputBps * 0.5 + sample * 0.5
        }
    }

    fun onDroppedFrames(count: Int) {
        droppedFrames = safeAdd(droppedFrames.toLong(), count.toLong()).toInt()
    }

    fun updateTracks(tracks: Tracks) {
        var videoBitrate = 0
        val selected = ArrayList<String>(4)
        for (group in tracks.groups) {
            if (!group.isSelected) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSelected(index)) continue
                val format = group.getTrackFormat(index)
                selected += describe(format)
                if (group.type == C.TRACK_TYPE_VIDEO && format.bitrate > 0) {
                    videoBitrate = max(videoBitrate, format.bitrate)
                }
            }
        }
        bitrateBps = videoBitrate.toLong()
        selectedTracks = selected.joinToString(" · ")
    }

    fun stallStarted() {
        if (stalled) return
        stalled = true
        stallStartedAt = System.currentTimeMillis()
    }

    fun stallEnded() {
        if (!stalled) return
        stalled = false
        val duration = (System.currentTimeMillis() - stallStartedAt).coerceAtLeast(0L)
        stallMs = safeAdd(stallMs, duration)
        stallCount++
    }

    fun isStalling(): Boolean = stalled

    fun selectedBitrateBps(): Long = bitrateBps

    fun snapshot(
        player: Player?,
        torrent: TorrentUiState,
        context: Context,
        refreshCache: Boolean,
    ): PlaybackStatsUi {
        val now = System.currentTimeMillis()
        if (refreshCache && now - lastCacheRefresh > CACHE_REFRESH_MS) {
            lastCacheRefresh = now
            runCatching {
                val usage = PlaybackCache.usage(context)
                cachedUsage = usage.bytes
                cachedMax = usage.maxBytes
            }
        }
        val bufferMs = if (player == null) 0L else {
            (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
        }
        return PlaybackStatsUi(
            bufferSeconds = bufferMs / 1_000.0,
            throughputBps = throughputBps,
            bitrateBps = bitrateBps,
            droppedFrames = droppedFrames,
            stallCount = stallCount,
            stallMs = stallMs,
            bytesLoaded = bytesLoaded,
            peers = torrent.peers,
            torrentDownloadBps = torrent.downloadRateBps,
            torrentUploadBps = torrent.uploadRateBps,
            torrentProgress = torrent.progress,
            cacheUsedBytes = cachedUsage,
            cacheMaxBytes = cachedMax,
            selectedTracks = selectedTracks,
        )
    }

    fun sessionDurationMs(): Long =
        if (sessionStartedAt <= 0) 0L else (System.currentTimeMillis() - sessionStartedAt).coerceAtLeast(0L)

    private fun describe(format: Format): String {
        val codec = format.codecs?.substringBefore(',')?.take(24).orEmpty()
        val quality = when {
            format.height > 0 -> "${format.height}p"
            format.bitrate > 0 -> "${format.bitrate / 1000} kbps"
            else -> format.sampleMimeType?.substringAfterLast('/')?.uppercase().orEmpty()
        }
        return listOf(quality, codec).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun safeAdd(a: Long, b: Long): Long =
        if (b > 0 && a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    private companion object {
        const val CACHE_REFRESH_MS = 5_000L
    }
}

/** Utility used by the UI for the "buffer health" chip. */
fun bufferHealth(stats: PlaybackStatsUi, targetSeconds: Int): Double {
    if (targetSeconds <= 0) return 0.0
    return min(1.0, stats.bufferSeconds / targetSeconds.toDouble())
}

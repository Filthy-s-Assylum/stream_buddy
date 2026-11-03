package com.streambuddy.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.common.Timeline

/**
 * A small, explicit load control so buffer targets can be changed while the
 * player is running. Media3's DefaultLoadControl only accepts durations at
 * build time, which is exactly what the optimizer needs to avoid.
 *
 * The allocator is still Media3's own [DefaultAllocator]; only the thresholds
 * are ours. That keeps memory accounting, trimming and buffer segments correct.
 */
@OptIn(UnstableApi::class)
class TunableLoadControl(
    minBufferMs: Int,
    maxBufferMs: Int,
    private val allocator: DefaultAllocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
) : LoadControl {

    @Volatile
    private var minBufferUs: Long = minBufferMs.coerceAtLeast(2_000).toLong() * 1_000L

    @Volatile
    private var maxBufferUs: Long = maxBufferMs.coerceAtLeast(6_000).toLong() * 1_000L

    @Volatile
    private var playbackBufferUs: Long = PLAYBACK_BUFFER_MS * 1_000L

    @Volatile
    private var rebufferBufferUs: Long = REBUFFER_BUFFER_MS * 1_000L

    @Volatile
    private var targetBufferBytes: Int = DEFAULT_TARGET_BYTES

    init {
        require(maxBufferMs >= minBufferMs) { "max buffer must be >= min buffer" }
        allocator.setTargetBufferSize(targetBufferBytes)
    }

    fun updateTargets(
        startBufferSeconds: Int,
        bufferTargetSeconds: Int,
        estimatedBitrateBps: Long = 0L,
    ) {
        val min = startBufferSeconds.coerceIn(2, 60)
        val max = bufferTargetSeconds.coerceIn(min, 300)
        minBufferUs = min.toLong() * 1_000_000L
        maxBufferUs = max.toLong() * 1_000_000L
        // Buffer needed before playback starts/restarts. Half of the start
        // buffer keeps the first frame responsive without starting empty.
        playbackBufferUs = (min.toLong() * 500_000L).coerceAtLeast(500_000L)
        rebufferBufferUs = (min.toLong() * 1_000_000L).coerceAtLeast(1_000_000L)
        val bitrate = estimatedBitrateBps.coerceIn(500_000L, 40_000_000L)
        val bytes = ((max.toLong() * bitrate) / 8L).coerceIn(16L * 1024 * 1024, 512L * 1024 * 1024)
        targetBufferBytes = bytes.toInt()
        allocator.setTargetBufferSize(targetBufferBytes)
    }

    override fun getAllocator(): DefaultAllocator = allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = 0L

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = false

    override fun shouldContinueLoading(params: LoadControl.Parameters): Boolean {
        val buffered = params.bufferedDurationUs
        return when {
            buffered < minBufferUs -> true
            buffered > maxBufferUs -> false
            else -> params.playWhenReady || params.rebuffering
        }
    }

    override fun shouldStartPlayback(params: LoadControl.Parameters): Boolean {
        val required = if (params.rebuffering) rebufferBufferUs else playbackBufferUs
        val scaled = (required * params.playbackSpeed.coerceAtLeast(0.1f)).toLong()
        return params.bufferedDurationUs >= scaled
    }

    // The newer per-playerId overloads delegate to the same implementation.
    override fun onPrepared(playerId: PlayerId) = Unit
    override fun onTracksSelected(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<androidx.media3.exoplayer.Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection>,
    ) = Unit
    override fun onStopped(playerId: PlayerId) = Unit
    override fun onReleased(playerId: PlayerId) = Unit

    fun targetBytes(): Int = targetBufferBytes

    private companion object {
        const val PLAYBACK_BUFFER_MS = 2_500L
        const val REBUFFER_BUFFER_MS = 4_000L
        const val DEFAULT_TARGET_BYTES = 64 * 1024 * 1024
    }
}

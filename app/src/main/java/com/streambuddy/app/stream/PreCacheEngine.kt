package com.streambuddy.app.stream

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.streambuddy.app.cache.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Fills the Media3 cache ahead of playback ("pre-play").
 *
 * * **HLS** — resolves the master playlist to a variant, then writes the first
 *   [PrefetchPlanner]-selected segments (the newest ones for live) into the cache.
 * * **Progressive** — writes the first part of the file (25 % of it, between
 *   [MIN_PROGRESSIVE_BYTES] and [MAX_PROGRESSIVE_BYTES]).
 * * **DASH** — not supported; playback still goes through the cache-first data source.
 *
 * Segment downloads resume: [CacheWriter] skips whatever is already cached, so retries and
 * cancelled runs pick up where they left off.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PreCacheEngine(
    private val context: Context,
    private val fetcher: StreamFetcher = StreamFetcher(),
) {

    data class Progress(
        val cachedBytes: Long,
        val totalBytes: Long,
        val cachedDurationMs: Long,
        val totalDurationMs: Long,
        val segmentsDone: Int,
        val segmentsTotal: Int,
    )

    data class Outcome(
        val cachedBytes: Long,
        val totalBytes: Long,
        val cachedDurationMs: Long,
        val totalDurationMs: Long,
    )

    /**
     * Caches as much as needed to cover [depthMs] of playback. [onProgress] is invoked from a
     * background thread; it must not block.
     */
    suspend fun prefetch(
        url: String,
        kindHint: StreamKind,
        depthMs: Long,
        preferHighestQuality: Boolean,
        onProgress: (Progress) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        val kind = if (kindHint != StreamKind.UNKNOWN) kindHint else sniffKind(url)
        when (kind) {
            StreamKind.HLS -> prefetchHls(url, depthMs, preferHighestQuality, onProgress)
            StreamKind.PROGRESSIVE -> prefetchProgressive(url, onProgress)
            StreamKind.DASH -> throw UnsupportedStreamException(
                "DASH streams play cache-first but cannot be pre-cached yet.",
            )
            StreamKind.TORRENT -> throw UnsupportedStreamException(
                "Magnet links are downloaded by the torrent engine, not the HTTP pre-cache.",
            )
            StreamKind.UNKNOWN -> throw UnsupportedStreamException(
                "Couldn't tell what kind of stream this is. Use a .m3u8, .mpd or media file URL.",
            )
        }
    }

    private suspend fun sniffKind(url: String): StreamKind {
        val probe = fetcher.probe(url)
        return StreamKind.fromContentType(probe.contentType) ?: StreamKind.UNKNOWN
    }

    private suspend fun prefetchHls(
        url: String,
        depthMs: Long,
        preferHighestQuality: Boolean,
        onProgress: (Progress) -> Unit,
    ): Outcome {
        val first = fetcher.fetchText(url)
        DrmDetector.detect(first.text)?.let { throw DrmProtectedException(it.scheme, it.description) }

        val playlist: HlsMediaPlaylist = when (val parsed = HlsPlaylistParser.parse(first.text, first.finalUrl)) {
            is HlsPlaylist.Master -> {
                val variant = selectVariant(parsed.variants, preferHighestQuality)
                    ?: throw UnsupportedStreamException("The master playlist has no playable variants")
                val media = fetcher.fetchText(variant.url)
                DrmDetector.detect(media.text)?.let { throw DrmProtectedException(it.scheme, it.description) }
                when (val nested = HlsPlaylistParser.parse(media.text, media.finalUrl)) {
                    is HlsPlaylist.Media -> nested.playlist
                    is HlsPlaylist.Master -> throw UnsupportedStreamException(
                        "The selected variant points at another master playlist",
                    )
                }
            }

            is HlsPlaylist.Media -> parsed.playlist
        }

        val plan = PrefetchPlanner.plan(playlist, depthMs)
        if (plan.isEmpty()) {
            throw PreCacheNetworkException("The playlist does not list any segments yet")
        }

        val mediaSegments = plan.count { !it.isInitialization }
        val cache = CacheManager.get(context)
        val factory = CacheManager.cacheDataSource(context)

        var cachedBytes = cachedBytesOf(cache, plan)
        var cachedDurationMs = 0L
        var segmentsDone = 0

        fun report() {
            onProgress(
                Progress(
                    cachedBytes = cachedBytes,
                    totalBytes = 0L,
                    cachedDurationMs = cachedDurationMs,
                    totalDurationMs = playlist.totalDurationMs,
                    segmentsDone = segmentsDone,
                    segmentsTotal = mediaSegments,
                ),
            )
        }

        report()

        for (segment in plan) {
            currentCoroutineContext().ensureActive()
            downloadSegment(factory, segment) { newBytes ->
                cachedBytes += newBytes
                report()
            }
            if (segment.isInitialization) continue
            cachedDurationMs += segment.durationMs
            segmentsDone++
            report()
        }

        val totalDurationMs = playlist.totalDurationMs
        val finalCachedDuration = if (totalDurationMs > 0L) {
            cachedDurationMs.coerceAtMost(totalDurationMs)
        } else {
            cachedDurationMs
        }
        return Outcome(
            cachedBytes = cachedBytesOf(cache, plan).coerceAtLeast(cachedBytes),
            totalBytes = 0L,
            cachedDurationMs = finalCachedDuration,
            totalDurationMs = totalDurationMs,
        )
    }

    private suspend fun prefetchProgressive(
        url: String,
        onProgress: (Progress) -> Unit,
    ): Outcome {
        val probe = fetcher.probe(url)
        val fileBytes = probe.contentLength
        val budget = when {
            fileBytes <= 0L -> MIN_PROGRESSIVE_BYTES
            else -> minOf(
                fileBytes,
                maxOf(fileBytes / 4L, MIN_PROGRESSIVE_BYTES).coerceAtMost(MAX_PROGRESSIVE_BYTES),
            )
        }

        val cache = CacheManager.get(context)
        val factory = CacheManager.cacheDataSource(context)
        val job = currentCoroutineContext()[Job]

        fun report(bytes: Long) {
            onProgress(
                Progress(
                    cachedBytes = bytes,
                    totalBytes = if (fileBytes > 0L) fileBytes else budget,
                    cachedDurationMs = 0L,
                    totalDurationMs = 0L,
                    segmentsDone = 0,
                    segmentsTotal = 1,
                ),
            )
        }

        report(safeCachedBytes(cache, url, 0L, budget))

        val dataSpec = DataSpec.Builder()
            .setUri(url)
            .setPosition(0L)
            .setLength(budget)
            .build()

        var writer: CacheWriter? = null
        val listener = CacheWriter.ProgressListener { _, bytesCached, _ ->
            if (job != null && !job.isActive) writer?.cancel()
            report(bytesCached)
        }
        val cacheWriter = CacheWriter(factory.createDataSource(), dataSpec, null, listener)
        writer = cacheWriter
        cacheWriter.cache()
        currentCoroutineContext().ensureActive()

        return Outcome(
            cachedBytes = safeCachedBytes(cache, url, 0L, budget),
            totalBytes = if (fileBytes > 0L) fileBytes else budget,
            cachedDurationMs = 0L,
            totalDurationMs = 0L,
        )
    }

    private suspend fun downloadSegment(
        factory: CacheDataSource.Factory,
        segment: HlsSegment,
        onBytes: (Long) -> Unit,
    ) {
        val job = currentCoroutineContext()[Job]
        val position = segment.byteRangeOffset ?: 0L
        // C.LENGTH_UNSET is an int; DataSpec.Builder wants a Long.
        val length: Long = segment.byteRangeLength ?: C.LENGTH_UNSET.toLong()

        val dataSpec = DataSpec.Builder()
            .setUri(segment.url)
            .setPosition(position)
            .setLength(length)
            .build()

        var writer: CacheWriter? = null
        val listener = CacheWriter.ProgressListener { _, _, newBytes ->
            if (job != null && !job.isActive) writer?.cancel()
            if (newBytes > 0L) onBytes(newBytes)
        }

        // CacheWriter opens/closes the data source itself for every block it reads.
        val cacheWriter = CacheWriter(factory.createDataSource(), dataSpec, null, listener)
        writer = cacheWriter
        cacheWriter.cache()
    }

    private fun cachedBytesOf(cache: Cache, plan: List<HlsSegment>): Long = plan.sumOf { segment ->
        safeCachedBytes(
            cache = cache,
            key = segment.url,
            position = segment.byteRangeOffset ?: 0L,
            length = segment.byteRangeLength ?: C.LENGTH_UNSET.toLong(),
        )
    }

    private fun safeCachedBytes(cache: Cache, key: String, position: Long, length: Long): Long =
        runCatching { cache.getCachedBytes(key, position, length) }.getOrDefault(0L)

    private fun selectVariant(variants: List<HlsVariant>, preferHighestQuality: Boolean): HlsVariant? =
        if (preferHighestQuality) variants.maxByOrNull { it.bandwidth } else variants.minByOrNull { it.bandwidth }

    companion object {
        const val MIN_PROGRESSIVE_BYTES = 8L * 1024L * 1024L
        const val MAX_PROGRESSIVE_BYTES = 150L * 1024L * 1024L
    }
}

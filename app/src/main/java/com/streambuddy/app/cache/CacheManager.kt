package com.streambuddy.app.cache

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.streambuddy.app.net.Http
import com.streambuddy.app.torrent.TorrentDataSourceFactory
import java.io.File

/**
 * Owns the single process-wide Media3 [SimpleCache] used by both playback and pre-caching.
 *
 * Media3 requires exactly one [SimpleCache] instance per directory, so everything goes through
 * [get]. The LRU evictor keeps the cache below [MAX_BYTES].
 */
@androidx.annotation.OptIn(UnstableApi::class)
object CacheManager {

    /** Upper bound for cached media. Oldest segments are evicted first. */
    const val MAX_BYTES: Long = 2L * 1024L * 1024L * 1024L

    private const val CACHE_DIRECTORY = "precache"

    @Volatile
    private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(context.applicationContext.cacheDir, CACHE_DIRECTORY),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { instance = it }
    }

    /** Plain HTTP data source (no cache). */
    fun networkDataSource(): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent(Http.USER_AGENT)
        .setConnectTimeoutMs(20_000)
        .setReadTimeoutMs(30_000)
        .setAllowCrossProtocolRedirects(true)

    /**
     * Cache-first data source. Reads served from disk, misses go upstream and are written back —
     * so ordinary playback fills the same cache that pre-caching fills.
     */
    fun cacheDataSource(context: Context): CacheDataSource.Factory {
        val cache = get(context)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(networkDataSource())
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Playback data source: torrents read from the BitTorrent engine, manifests go direct, and
     * everything else is cache-first.
     */
    fun playbackDataSource(context: Context): DataSource.Factory =
        PlaybackDataSourceFactory(
            cacheFactory = cacheDataSource(context),
            networkFactory = networkDataSource(),
            torrentFactory = TorrentDataSourceFactory(context.applicationContext),
        )

    /** Bytes currently held on disk. Safe to call from any thread. */
    fun usageBytes(context: Context): Long = runCatching { get(context).cacheSpace }.getOrDefault(0L)

    /**
     * Drops every cached span without releasing the cache, so it is safe while a player is
     * active (a released cache would make in-flight reads fail).
     */
    fun clear(context: Context) {
        val cache = runCatching { get(context) }.getOrNull() ?: return
        // Snapshot the keys: removeResource mutates the index this set is backed by.
        cache.keys.toList().forEach { key -> runCatching { cache.removeResource(key) } }
    }

    /** Releases the cache. Only for process teardown / instrumentation. */
    fun release() {
        synchronized(this) {
            runCatching { instance?.release() }
            instance = null
        }
    }
}

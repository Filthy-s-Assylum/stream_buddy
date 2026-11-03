package com.streambuddy.app.cache

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import com.streambuddy.app.core.Fmt
import java.io.File
import java.util.TreeSet

/**
 * LRU evictor whose byte budget can be changed at runtime from Settings.
 *
 * Unlike [androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor] the
 * limit is a [Volatile] value, so lowering the cache cap takes effect without
 * recreating the player or losing the in-memory cache index.
 */
@OptIn(UnstableApi::class)
class DynamicLruEvictor(maxBytes: Long) : CacheEvictor {

    @Volatile
    var maxBytes: Long = maxBytes.coerceAtLeast(MIN_BYTES)
        private set

    private val lru = TreeSet(
        compareBy<CacheSpan>({ it.lastTouchTimestamp }, { it.key }, { it.position }),
    )
    private var cache: Cache? = null

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {
        cache?.let { trim(it, 0L) }
    }

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        this.cache = cache
        if (length != C_LENGTH_UNSET) trim(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        this.cache = cache
        if (span.isCached) {
            synchronized(lru) { lru.add(span) }
            trim(cache, 0L)
        }
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        synchronized(lru) { lru.remove(span) }
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        synchronized(lru) {
            if (oldSpan.isCached) lru.remove(oldSpan)
            if (newSpan.isCached) lru.add(newSpan)
        }
        trim(cache, 0L)
    }

    fun setMaxBytes(context: Context, value: Long) {
        maxBytes = value.coerceAtLeast(MIN_BYTES)
        // A SimpleCache that has not been opened yet has no spans to evict. It
        // is trimmed the next time the player opens a file.
        cache?.let { trim(it, 0L) }
    }

    fun trim(cache: Cache, requiredSpace: Long) {
        synchronized(lru) {
            var size = cache.cacheSpace
            val iterator = lru.iterator()
            while (size + requiredSpace > maxBytes && iterator.hasNext()) {
                val span = iterator.next()
                if (!span.isCached) {
                    iterator.remove()
                    continue
                }
                val length = span.length.coerceAtLeast(0L)
                runCatching { cache.removeSpan(span) }
                iterator.remove()
                size = (size - length).coerceAtLeast(0L)
            }
        }
    }

    private companion object {
        const val MIN_BYTES = 64L * 1024 * 1024
        const val C_LENGTH_UNSET = -1L
    }
}

data class CacheUsage(
    val bytes: Long,
    val resourceCount: Int,
    val maxBytes: Long,
) {
    val fraction: Double get() = if (maxBytes <= 0) 0.0 else (bytes.toDouble() / maxBytes).coerceIn(0.0, 1.0)
    val prettyBytes: String get() = "${Fmt.bytes(bytes)} / ${Fmt.bytes(maxBytes)}"
}

/**
 * Playback cache. This is the evictable, best-effort cache used while
 * streaming. Completed offline downloads deliberately live in a separate cache
 * (see [com.streambuddy.app.download.Downloads]) so cache eviction can never
 * delete a file the user asked to keep.
 */
@OptIn(UnstableApi::class)
object PlaybackCache {

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var evictor: DynamicLruEvictor? = null

    @Volatile
    private var currentMaxBytes: Long = DEFAULT_MAX_BYTES

    @Synchronized
    fun get(context: Context, maxBytes: Long = currentMaxBytes): SimpleCache {
        currentMaxBytes = maxBytes.coerceAtLeast(64L * 1024 * 1024)
        cache?.let { existing ->
            evictor?.setMaxBytes(context, currentMaxBytes)
            return existing
        }
        val directory = File(context.applicationContext.cacheDir, "playback_cache")
        if (!directory.exists()) directory.mkdirs()
        val createdEvictor = DynamicLruEvictor(currentMaxBytes)
        return SimpleCache(
            directory,
            createdEvictor,
            StandaloneDatabaseProvider(context.applicationContext),
        ).also {
            cache = it
            evictor = createdEvictor
            createdEvictor.onCacheInitialized()
        }
    }

    fun reconfigure(context: Context, maxBytes: Long) {
        currentMaxBytes = maxBytes.coerceAtLeast(64L * 1024 * 1024)
        evictor?.setMaxBytes(context, currentMaxBytes)
        // Force creation so a later player uses the new budget even if the
        // player has not been opened yet in this process.
        get(context, currentMaxBytes)
    }

    fun usage(context: Context): CacheUsage {
        val active = get(context)
        var bytes = 0L
        var resources = 0
        for (key in active.keys) {
            resources++
            for (span in active.getCachedSpans(key)) {
                if (span.isCached) bytes += span.length.coerceAtLeast(0L)
            }
        }
        return CacheUsage(bytes, resources, currentMaxBytes)
    }

    /**
     * Drops evictable cached media. The caller must stop playback first; the
     * UI disables this action while the player is active.
     */
    fun clear(context: Context) {
        val active = get(context)
        for (key in active.keys.toList()) {
            runCatching { active.removeResource(key) }
        }
        evictor?.trim(active, 0L)
    }

    @Synchronized
    fun release() {
        cache?.release()
        cache = null
        evictor = null
    }

    const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024 * 1024
}

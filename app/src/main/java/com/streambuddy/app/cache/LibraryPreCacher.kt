package com.streambuddy.app.cache

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.streambuddy.app.ai.Prediction
import com.streambuddy.app.data.LibraryItem
import com.streambuddy.app.data.LibraryRepository
import com.streambuddy.app.data.PredictorRepository
import com.streambuddy.app.data.SettingsStore
import com.streambuddy.app.download.Downloads
import com.streambuddy.app.net.NetworkGuard
import com.streambuddy.app.net.VpnRequiredMessage
import com.streambuddy.app.stream.CacheState
import com.streambuddy.app.stream.DrmProtectedException
import com.streambuddy.app.stream.PreCacheEngine
import com.streambuddy.app.stream.StreamKind
import com.streambuddy.app.stream.StreamUrl
import com.streambuddy.app.stream.UnsupportedStreamException
import com.streambuddy.app.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class LibraryPreCacheState(
    val itemId: Long,
    val state: CacheState,
    val cachedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val cachedDurationMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val error: String? = null,
) {
    val progressFraction: Double
        get() = when {
            totalDurationMs > 0L -> (cachedDurationMs.toDouble() / totalDurationMs).coerceIn(0.0, 1.0)
            totalBytes > 0L -> (cachedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0)
            state == CacheState.READY -> 1.0
            else -> 0.0
        }
}

/**
 * Coordinates pre-play caching and on-device [SmartPrefetch] ranking for the
 * active [LibraryRepository], writing segments straight into [PlaybackCache] so
 * [com.streambuddy.app.playback.PlayerEngine] plays pre-cached content from disk.
 */
@OptIn(UnstableApi::class)
class LibraryPreCacher(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val library: LibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()

    private val _states = MutableStateFlow<Map<Long, LibraryPreCacheState>>(emptyMap())
    val states: StateFlow<Map<Long, LibraryPreCacheState>> = _states.asStateFlow()

    private val _predictions = MutableStateFlow<Map<Long, Prediction>>(emptyMap())
    val predictions: StateFlow<Map<Long, Prediction>> = _predictions.asStateFlow()

    init {
        scope.launch {
            combine(
                library.items,
                PredictorRepository.get(context).state,
                settingsStore.settings,
            ) { items, _, settings -> items to settings.smartPrefetch }
                .collect { (items, enabled) ->
                    refreshPredictionsInternal(items, enabled)
                }
        }
        if (settingsStore.current().smartPrefetch) {
            runCatching { SmartPrefetchWorker.schedule(context) }
        }
    }

    fun onSettingsChanged() {
        val enabled = settingsStore.current().smartPrefetch
        runCatching {
            if (enabled) SmartPrefetchWorker.schedule(context) else SmartPrefetchWorker.cancel(context)
        }
        refreshPredictions()
    }

    fun refreshPredictions() {
        scope.launch {
            val items = runCatching { library.items.first() }.getOrDefault(emptyList())
            refreshPredictionsInternal(items, settingsStore.current().smartPrefetch)
        }
    }

    private suspend fun refreshPredictionsInternal(items: List<LibraryItem>, enabled: Boolean) {
        if (!enabled) {
            _predictions.value = emptyMap()
            return
        }
        val ranked = SmartPrefetch.rankLibrary(context, items)
        _predictions.value = ranked.associateBy { it.candidate.streamId }
    }

    fun precache(item: LibraryItem, depthMsOverride: Long? = null) {
        val uri = item.sourceUri?.trim().orEmpty()
        if (!Downloads.isHttpUri(uri)) return
        if (jobs[item.id]?.isActive == true) return
        val job = scope.launch {
            precacheInternal(item, uri, depthMsOverride)
        }
        jobs[item.id] = job
        job.invokeOnCompletion { jobs.remove(item.id, job) }
    }

    suspend fun precacheBlocking(item: LibraryItem, depthMsOverride: Long? = null): Boolean {
        val uri = item.sourceUri?.trim().orEmpty()
        if (!Downloads.isHttpUri(uri)) return false
        return withContext(Dispatchers.IO) {
            precacheInternal(item, uri, depthMsOverride)
        }
    }

    fun cancel(itemId: Long) {
        jobs.remove(itemId)?.cancel()
        _states.update { current ->
            val existing = current[itemId] ?: return@update current
            if (existing.state == CacheState.CACHING) {
                current + (
                    itemId to existing.copy(
                        state = if (existing.cachedBytes > 0L) CacheState.PARTIAL else CacheState.QUEUED,
                        error = "Cancelled",
                    )
                )
            } else {
                current
            }
        }
    }

    fun clearCachedStates() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _states.value = emptyMap()
    }

    fun runSmartPrefetchNow() {
        SmartPrefetchWorker.runNow(context)
    }

    fun release() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
    }

    private suspend fun precacheInternal(
        item: LibraryItem,
        uri: String,
        depthMsOverride: Long?,
    ): Boolean {
        if (NetworkGuard.isBlocked()) {
            updateState(
                LibraryPreCacheState(
                    itemId = item.id,
                    state = CacheState.FAILED,
                    error = VpnRequiredMessage.network,
                ),
            )
            return false
        }
        val settings = settingsStore.current()
        val depthMs = depthMsOverride?.takeIf { it > 0L } ?: settings.prefetchMillis
        val maxCacheBytes = settings.playbackCacheMb.toLong() * 1024L * 1024L
        updateState(LibraryPreCacheState(itemId = item.id, state = CacheState.CACHING))

        val engine = PreCacheEngine(
            context = context,
            cacheProvider = { PlaybackCache.get(context, maxCacheBytes) },
            cacheFactoryProvider = { PlaybackCache.dataSourceFactory(context, maxCacheBytes) },
        )

        return try {
            val kindHint = StreamKind.fromUrl(uri)
            val outcome = engine.prefetch(
                url = uri,
                kindHint = kindHint,
                depthMs = depthMs,
                preferHighestQuality = settings.preferHighestQuality,
                onProgress = { progress ->
                    updateState(
                        LibraryPreCacheState(
                            itemId = item.id,
                            state = CacheState.CACHING,
                            cachedBytes = progress.cachedBytes,
                            totalBytes = progress.totalBytes,
                            cachedDurationMs = progress.cachedDurationMs,
                            totalDurationMs = progress.totalDurationMs,
                        ),
                    )
                },
            )
            updateState(
                LibraryPreCacheState(
                    itemId = item.id,
                    state = CacheState.READY,
                    cachedBytes = outcome.cachedBytes,
                    totalBytes = outcome.totalBytes,
                    cachedDurationMs = outcome.cachedDurationMs,
                    totalDurationMs = outcome.totalDurationMs,
                ),
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (drm: DrmProtectedException) {
            updateState(
                LibraryPreCacheState(
                    itemId = item.id,
                    state = CacheState.UNSUPPORTED,
                    error = "${drm.scheme} DRM streams cannot be pre-cached.",
                ),
            )
            false
        } catch (unsupported: UnsupportedStreamException) {
            updateState(
                LibraryPreCacheState(
                    itemId = item.id,
                    state = CacheState.UNSUPPORTED,
                    error = unsupported.message ?: "Stream plays online but cannot be pre-cached.",
                ),
            )
            false
        } catch (throwable: Throwable) {
            val prev = _states.value[item.id]
            val partial = prev != null && prev.cachedBytes > 0L
            updateState(
                LibraryPreCacheState(
                    itemId = item.id,
                    state = if (partial) CacheState.PARTIAL else CacheState.FAILED,
                    cachedBytes = prev?.cachedBytes ?: 0L,
                    totalBytes = prev?.totalBytes ?: 0L,
                    error = Format.shortMessage(throwable.message ?: "Pre-cache failed"),
                ),
            )
            false
        }
    }

    private fun updateState(state: LibraryPreCacheState) {
        _states.update { it + (state.itemId to state) }
    }

    companion object {
        fun isHttpSource(uri: String?): Boolean =
            !uri.isNullOrBlank() &&
                Downloads.isHttpUri(uri) &&
                StreamUrl.validate(uri) is StreamUrl.Result.Valid
    }
}

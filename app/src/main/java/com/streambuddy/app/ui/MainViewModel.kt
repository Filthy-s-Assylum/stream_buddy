package com.streambuddy.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streambuddy.app.ai.Prediction
import com.streambuddy.app.ai.WatchEvent
import com.streambuddy.app.cache.AppScope
import com.streambuddy.app.cache.CacheManager
import com.streambuddy.app.cache.PreCacheWorker
import com.streambuddy.app.cache.SmartPrefetch
import com.streambuddy.app.cache.SmartPrefetchWorker
import com.streambuddy.app.data.AppDb
import com.streambuddy.app.data.AppSettings
import com.streambuddy.app.data.PredictorRepository
import com.streambuddy.app.data.SettingsRepository
import com.streambuddy.app.data.StreamEntity
import com.streambuddy.app.stream.CacheState
import com.streambuddy.app.stream.SharedLink
import com.streambuddy.app.stream.StreamKind
import com.streambuddy.app.stream.StreamUrl
import com.streambuddy.app.torrent.MagnetLink
import com.streambuddy.app.torrent.QualitySelector
import com.streambuddy.app.torrent.TorrentEngine
import com.streambuddy.app.torrent.TorrentDataSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Player tab shows. */
data class PlaybackUiState(
    val streamId: Long? = null,
    val title: String = "",
    val url: String = "",
    val kind: StreamKind = StreamKind.UNKNOWN,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
    /** Why the torrent engine picked this file, e.g. "1080p matches this screen (1080p)". */
    val qualityNote: String? = null,
    /** Which file of the torrent is playing, so the player can mark the active quality. */
    val fileIndex: Int? = null,
) {
    val hasMedia: Boolean get() = streamId != null
}

/**
 * Single activity-scoped view model: owns the queue flow, the ExoPlayer instance and the settings,
 * so playback survives tab switches and configuration changes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val dao = AppDb.get(app).streams()
    private val settingsRepository = SettingsRepository.get(app)

    val streams: StateFlow<List<StreamEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Best guesses for what will be played next, keyed by stream id (empty when smart is off). */
    private val _predictions = MutableStateFlow<Map<Long, Prediction>>(emptyMap())
    val predictions: StateFlow<Map<Long, Prediction>> = _predictions.asStateFlow()

    /** Learned-model status for the Settings screen. */
    val modelState = PredictorRepository.get(app).state

    /**
     * A URL that arrived from the share sheet or a deep link, waiting for the Queue screen to pick
     * it up. Cleared with [consumeSharedUrl] so it is applied exactly once.
     */
    private val _sharedUrl = MutableStateFlow<String?>(null)
    val sharedUrl: StateFlow<String?> = _sharedUrl.asStateFlow()

    /** Live BitTorrent state per info hash, for the queue rows. */
    private val _torrentProgress = MutableStateFlow<Map<String, TorrentEngine.Progress>>(emptyMap())
    val torrentProgress: StateFlow<Map<String, TorrentEngine.Progress>> = _torrentProgress.asStateFlow()

    /**
     * Every playable file in the torrent that is on screen right now, best first, so the player
     * can offer "this one instead" when the automatic pick is not what you wanted.
     */
    private val _torrentFiles = MutableStateFlow<List<QualitySelector.Suggestion>>(emptyList())
    val torrentFiles: StateFlow<List<QualitySelector.Suggestion>> = _torrentFiles.asStateFlow()

    /** A tab requested by a deep link, waiting for the nav host to consume it. */
    private val _requestedTab = MutableStateFlow<String?>(null)
    val requestedTab: StateFlow<String?> = _requestedTab.asStateFlow()

    /** The playback currently being watched, so a session can be recorded when it ends. */
    private var session: WatchSession? = null
    private var sessionMaxPositionMs = 0L

    private data class WatchSession(val streamId: Long, val title: String, val startedAtMs: Long)

    /**
     * Cache-first player. Every data source request goes through [CacheManager], so audio and video
     * the pre-cache job already fetched play straight from disk and anything missing is fetched and
     * written back as it plays.
     */
    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(DefaultMediaSourceFactory(CacheManager.playbackDataSource(app)))
        .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    init {
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playback.update { it.copy(isPlaying = isPlaying) }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    _playback.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
                    if (playbackState == Player.STATE_ENDED) finishSession()
                }

                override fun onPlayerError(error: PlaybackException) {
                    val detail = error.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                    _playback.update {
                        it.copy(error = "${error.errorCodeName}$detail", isBuffering = false)
                    }
                }
            },
        )

        // BitTorrent has no callbacks either: sample the engine once a second while any torrent is
        // tracked, and stop polling when the queue has none.
        viewModelScope.launch {
            while (isActive) {
                val torrents = streams.value.mapNotNull { stream ->
                    if (stream.streamKind == StreamKind.TORRENT) MagnetLink.parse(stream.url) else null
                }
                if (torrents.isNotEmpty()) {
                    val snapshot = withContext(Dispatchers.IO) {
                        torrents.associate { magnet ->
                            magnet.infoHashHex to TorrentEngine.progress(app, magnet)
                        }
                    }
                    _torrentProgress.value = snapshot
                } else if (_torrentProgress.value.isNotEmpty()) {
                    _torrentProgress.value = emptyMap()
                }
                delay(POSITION_POLL_MS)
            }
        }

        // ExoPlayer has no position callback, so poll while something is loaded.
        viewModelScope.launch {
            while (isActive) {
                if (player.playbackState != Player.STATE_IDLE) {
                    val position = player.currentPosition.coerceAtLeast(0L)
                    if (position > sessionMaxPositionMs) sessionMaxPositionMs = position
                    _playback.update {
                        it.copy(
                            positionMs = position,
                            durationMs = player.duration.coerceAtLeast(0L),
                        )
                    }
                }
                delay(POSITION_POLL_MS)
            }
        }

        refreshCacheUsage()
        if (settings.value.smartPrefetch) SmartPrefetchWorker.schedule(app)

        // Re-rank the queue whenever it changes, the settings change, or a session is recorded.
        viewModelScope.launch {
            combine(streams, settings) { queue, current -> queue to current }.collect { (queue, current) ->
                if (current.smartPrefetch && queue.isNotEmpty()) {
                    val ranked = withContext(Dispatchers.Default) { SmartPrefetch.rankQueue(app) }
                    _predictions.value = ranked.associateBy { it.candidate.streamId }
                } else {
                    _predictions.value = emptyMap()
                }
            }
        }
    }

    // region intents

    /**
     * Handles a share sheet / deep link payload. Returns `true` when something was consumed, so
     * the activity knows whether to navigate.
     */
    fun handleSharedLink(target: SharedLink.Target): Boolean =
        when (target) {
            is SharedLink.Target.Stream -> {
                _sharedUrl.value = target.url
                _requestedTab.value = "queue"
                post("Shared link ready — press Add to pre-play it")
                true
            }

            is SharedLink.Target.Magnet -> {
                addMagnet(target.uri)
                true
            }

            is SharedLink.Target.Tab -> {
                _requestedTab.value = target.route
                true
            }

            SharedLink.Target.Ignore -> false
        }

    /** Marks the shared URL as handled. */
    fun consumeSharedUrl() {
        _sharedUrl.value = null
    }

    /** Marks the requested tab as handled. */
    fun consumeRequestedTab() {
        _requestedTab.value = null
    }

    /** Deep link to a specific queue entry, used by the pre-cache notification. */
    fun focusStream(streamId: Long) {
        viewModelScope.launch {
            val stream = dao.findById(streamId) ?: return@launch
            play(stream)
            _requestedTab.value = "player"
        }
    }

    // endregion

    // region torrents

    /**
     * Adds a magnet link to the queue and starts resolving it. Returns `false` when the link is
     * not a usable magnet.
     */
    fun addMagnet(raw: String, rawTitle: String = ""): Boolean {
        val magnet = MagnetLink.parse(raw) ?: run {
            post("That magnet link has no usable info hash")
            return false
        }
        viewModelScope.launch {
            val title = rawTitle.trim().ifBlank {
                magnet.displayName?.takeIf { it.isNotBlank() }
                    ?: "Torrent ${magnet.shortHash}"
            }
            val id = dao.insert(
                StreamEntity(
                    title = title,
                    url = raw.trim(),
                    kind = StreamKind.TORRENT.name,
                    state = CacheState.QUEUED.name,
                    prefetchMillis = settings.value.prefetchMillis,
                ),
            )
            withContext(Dispatchers.IO) { TorrentEngine.ensure(app, magnet) }
            post("Added “$title” — finding peers…")
            focusStream(id)
        }
        return true
    }

    /**
     * Plays the best file inside a torrent.
     *
     * Metadata can take a few seconds, so the state is updated as it arrives: "finding peers",
     * then the chosen file and why, then playback.
     */
    private suspend fun playMagnet(stream: StreamEntity) {
        val magnet = MagnetLink.parse(stream.url) ?: run {
            post("This entry is not a valid magnet link")
            return
        }
        // Record the previous watch session before switching, as the HTTP path does.
        finishSession()
        _playback.value = PlaybackUiState(
            streamId = stream.id,
            title = stream.title,
            url = stream.url,
            kind = StreamKind.TORRENT,
            isBuffering = true,
        )

        val selection = withContext(Dispatchers.IO) { TorrentEngine.prepare(app, magnet) }
        if (selection == null) {
            _playback.update {
                it.copy(isBuffering = false, error = "No peers found for this magnet")
            }
            post("No peers found for “${stream.title}”")
            return
        }

        startTorrentPlayback(stream, magnet, selection.chosen)
        _torrentFiles.value = selection.alternatives
    }

    /** Points the player at one specific file inside an already-resolved torrent. */
    private fun startTorrentPlayback(
        stream: StreamEntity,
        magnet: MagnetLink,
        suggestion: QualitySelector.Suggestion,
    ) {
        val chosen = suggestion
        val mime = when (chosen.file.extension) {
            "mp4", "m4v" -> MimeTypes.VIDEO_MP4
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "webm" -> MimeTypes.VIDEO_WEBM
            "ts", "m2ts" -> MimeTypes.VIDEO_MP2T
            "mov" -> MimeTypes.VIDEO_MP4
            "avi" -> MimeTypes.VIDEO_AVI
            else -> null
        }
        val item = MediaItem.Builder()
            .setUri(TorrentDataSourceFactory.uriFor(magnet, chosen.file.index))
            .apply { if (mime != null) setMimeType(mime) }
            .build()

        session = WatchSession(
            streamId = stream.id,
            title = stream.title,
            startedAtMs = System.currentTimeMillis(),
        )
        sessionMaxPositionMs = 0L
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        _playback.update {
            it.copy(
                isBuffering = true,
                error = null,
                // Surfaced in the player subtitle so the choice is visible, not magic.
                qualityNote = chosen.reasons.firstOrNull(),
                fileIndex = chosen.file.index,
            )
        }
        post("Playing ${chosen.file.name}")
    }

    /** Plays a different file from the current torrent, e.g. a smaller copy of the same film. */
    fun playTorrentAlternative(fileIndex: Int) {
        val suggestion = _torrentFiles.value.firstOrNull { it.file.index == fileIndex } ?: return
        val current = _playback.value
        val stream = streams.value.firstOrNull { it.id == current.streamId } ?: return
        val magnet = MagnetLink.parse(stream.url) ?: return
        viewModelScope.launch {
            val switched = withContext(Dispatchers.IO) {
                TorrentEngine.switchTo(app, magnet, fileIndex)
            }
            if (!switched) {
                post("That file is not available in this torrent")
                return@launch
            }
            startTorrentPlayback(stream, magnet, suggestion)
        }
    }

    /** Stops and forgets a torrent, keeping the downloaded files. */
    fun stopTorrent(stream: StreamEntity) {
        val magnet = MagnetLink.parse(stream.url) ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { TorrentEngine.stop(app, magnet) }
            post("Stopped “${stream.title}”")
        }
    }

    // endregion

    // region queue

    /** Validates and enqueues a stream, then starts pre-caching it. */
    fun addStream(rawUrl: String, rawTitle: String) {
        when (val result = StreamUrl.validate(rawUrl)) {
            is StreamUrl.Result.Invalid -> post(result.message)
            is StreamUrl.Result.Valid -> viewModelScope.launch {
                val title = rawTitle.trim().ifBlank {
                    StreamUrl.suggestTitle(result.url).ifBlank { "Untitled stream" }
                }
                val id = dao.insert(
                    StreamEntity(
                        title = title,
                        url = result.url,
                        kind = result.kind.name,
                        state = CacheState.QUEUED.name,
                        prefetchMillis = settings.value.prefetchMillis,
                    ),
                )
                PreCacheWorker.enqueue(app, id)
                SmartPrefetchWorker.schedule(app)
                post("Queued “$title” for pre-play")
            }
        }
    }

    fun startCaching(stream: StreamEntity) {
        if (stream.streamKind == StreamKind.TORRENT) {
            val magnet = MagnetLink.parse(stream.url)
            if (magnet != null) {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) { TorrentEngine.ensure(app, magnet) }
                    post("Fetching “${stream.title}” over BitTorrent")
                }
            }
            return
        }
        viewModelScope.launch {
            dao.resetForCaching(
                id = stream.id,
                prefetchMillis = settings.value.prefetchMillis,
                state = CacheState.QUEUED.name,
                now = System.currentTimeMillis(),
            )
            PreCacheWorker.enqueue(app, stream.id)
            post("Pre-caching “${stream.title}”")
        }
    }

    fun cancelCaching(stream: StreamEntity) {
        if (stream.streamKind == StreamKind.TORRENT) {
            stopTorrent(stream)
            return
        }
        PreCacheWorker.cancel(app, stream.id)
        viewModelScope.launch {
            dao.updateState(
                id = stream.id,
                state = CacheState.QUEUED.name,
                error = null,
                now = System.currentTimeMillis(),
            )
        }
    }

    fun deleteStream(stream: StreamEntity) {
        if (stream.streamKind == StreamKind.TORRENT) {
            MagnetLink.parse(stream.url)?.let { magnet ->
                viewModelScope.launch { withContext(Dispatchers.IO) { TorrentEngine.stop(app, magnet) } }
            }
        }
        PreCacheWorker.cancel(app, stream.id)
        if (_playback.value.streamId == stream.id) stopPlayback()
        viewModelScope.launch { dao.deleteById(stream.id) }
    }

    // endregion

    // region playback

    fun play(stream: StreamEntity) {
        if (stream.streamKind == StreamKind.TORRENT) {
            viewModelScope.launch { playMagnet(stream) }
            return
        }
        val mediaItem = MediaItem.Builder()
            .setUri(stream.url)
            .apply {
                when (stream.streamKind) {
                    StreamKind.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    StreamKind.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                    StreamKind.PROGRESSIVE, StreamKind.TORRENT, StreamKind.UNKNOWN -> Unit
                }
            }
            .build()

        finishSession()
        _torrentFiles.value = emptyList()
        session = WatchSession(
            streamId = stream.id,
            title = stream.title,
            startedAtMs = System.currentTimeMillis(),
        )
        sessionMaxPositionMs = 0L

        _playback.value = PlaybackUiState(
            streamId = stream.id,
            title = stream.title,
            url = stream.url,
            kind = stream.streamKind,
        )
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    fun togglePlayPause() {
        if (!_playback.value.hasMedia) return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun pausePlayback() {
        if (player.isPlaying) player.pause()
    }

    fun stopPlayback() {
        finishSession()
        player.stop()
        player.clearMediaItems()
        _torrentFiles.value = emptyList()
        _playback.value = PlaybackUiState()
    }

    /**
     * Records how much of the current stream was watched and lets the predictor refit.
     *
     * This is the only place watch data is produced: a session counts once, either when playback
     * ends, another stream starts, or the app is closed. Nothing leaves the device.
     */
    private fun finishSession() {
        val current = session ?: return
        session = null
        val watchedMs = sessionMaxPositionMs
        if (watchedMs < MIN_RECORDED_WATCH_MS) return

        val durationMs = player.duration.takeIf { it > 0L } ?: 0L
        val completed = durationMs > 0L && watchedMs >= (durationMs - COMPLETION_TOLERANCE_MS)
        val event = WatchEvent(
            streamId = current.streamId,
            title = current.title,
            startedAtMs = current.startedAtMs,
            watchedMs = watchedMs,
            durationMs = durationMs,
            completed = completed,
        )
        // Not viewModelScope: this also runs from onCleared, where that scope is already gone.
        AppScope.io.launch {
            SmartPrefetch.recordPlayback(app, event)
            // The next pass re-ranks with the fresher history; it stays on its unmetered window.
            if (settingsRepository.settings.value.smartPrefetch) SmartPrefetchWorker.schedule(app)
            if (_predictions.value.isNotEmpty()) {
                val ranked = SmartPrefetch.rankQueue(app)
                _predictions.value = ranked.associateBy { it.candidate.streamId }
            }
        }
    }

    // endregion

    // region settings & cache

    fun setPrefetchMinutes(minutes: Int) = settingsRepository.setPrefetchMinutes(minutes)

    fun setPreferHighestQuality(preferHighestQuality: Boolean) =
        settingsRepository.setPreferHighestQuality(preferHighestQuality)

    fun setSmartPrefetch(enabled: Boolean) {
        settingsRepository.setSmartPrefetch(enabled)
        if (enabled) SmartPrefetchWorker.schedule(app) else SmartPrefetchWorker.cancel(app)
        post(if (enabled) "Smart pre-cache on" else "Smart pre-cache off")
    }

    /** Runs a prediction pass now instead of waiting for the periodic job. */
    fun enhanceQueueNow() {
        SmartPrefetchWorker.runNow(app)
        post("Looking for what to cache next…")
    }

    fun resetLearning() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { SmartPrefetch.clearHistory(app) }
            _predictions.value = emptyMap()
            post("Watch history cleared; the model starts from scratch")
        }
    }

    fun refreshCacheUsage() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { CacheManager.usageBytes(app) }
            _cacheBytes.value = bytes
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CacheManager.clear(app) }
            _cacheBytes.value = 0L
            post("Cache cleared")
        }
    }

    // endregion

    override fun onCleared() {
        finishSession()
        player.release()
        super.onCleared()
    }

    private fun post(message: String) {
        _messages.tryEmit(message)
    }

    private companion object {
        const val POSITION_POLL_MS = 500L

        /** Shorter than this and it was a mis-tap, not a viewing decision. */
        const val MIN_RECORDED_WATCH_MS = 15_000L
        const val COMPLETION_TOLERANCE_MS = 10_000L
    }
}

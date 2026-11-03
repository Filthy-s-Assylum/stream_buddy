package com.streambuddy.app.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import com.streambuddy.app.cache.PlaybackCache
import com.streambuddy.app.data.LibraryItem
import com.streambuddy.app.data.LibraryRepository
import com.streambuddy.app.data.SettingsStore
import com.streambuddy.app.data.StreamKind
import com.streambuddy.app.download.Downloads
import com.streambuddy.app.net.VpnMonitor
import com.streambuddy.app.net.VpnRequiredMessage
import com.streambuddy.app.optimizer.OptimizerRuntime
import com.streambuddy.app.optimizer.PlaybackTelemetry
import com.streambuddy.app.torrent.TorrentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A subtitle track that can be shown in the player menu. */
data class SubtitleOption(
    val index: Int,
    val label: String,
    val url: String,
)

data class PlayerUiState(
    val itemId: Long = 0L,
    val title: String = "",
    val kind: StreamKind = StreamKind.MP4,
    val sourceLabel: String = "",
    val preparing: Boolean = false,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
    val subtitles: List<SubtitleOption> = emptyList(),
    val selectedSubtitle: Int = -1,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val isTorrent: Boolean = false,
)

/**
 * Owns the single ExoPlayer instance, its MediaSession and the bridge between
 * the library, the torrent swarm, the cache and the optimizer.
 *
 * Playback sources are resolved strictly from the [LibraryItem.sourceUri] the
 * user attached. Nothing here searches for, supplies or unlocks content.
 */
@OptIn(UnstableApi::class)
class PlayerEngine(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val optimizer: OptimizerRuntime,
    private val torrentManager: TorrentManager,
    private val library: LibraryRepository,
    private val vpnMonitor: VpnMonitor,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val loadControl = TunableLoadControl(
        minBufferMs = settingsStore.current().startBufferSeconds * 1_000,
        maxBufferMs = settingsStore.current().bufferTargetSeconds * 1_000,
    )

    private val httpFactory: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent("StreamBuddy/1.0 (Android)")
        .setConnectTimeoutMs(20_000)
        .setReadTimeoutMs(30_000)
        // Do not let an https source redirect playback to cleartext http.
        .setAllowCrossProtocolRedirects(false)

    private val cachedMediaSourceFactory: MediaSource.Factory = run {
        val cache = PlaybackCache.get(
            context,
            settingsStore.current().playbackCacheMb.toLong() * 1024L * 1024L,
        )
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        DefaultMediaSourceFactory(context).setDataSourceFactory(cacheFactory)
    }

    private val directMediaSourceFactory: MediaSource.Factory =
        DefaultMediaSourceFactory(context).setDataSourceFactory(DefaultDataSource.Factory(context))

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(cachedMediaSourceFactory)
        .build()
        .apply {
            setWakeMode(C.WAKE_MODE_NETWORK)
            setHandleAudioBecomingNoisy(true)
        }

    val session: MediaSession = MediaSession.Builder(context, player).build()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(PlaybackStatsUi())
    val stats: StateFlow<PlaybackStatsUi> = _stats.asStateFlow()

    private val analytics = PlaybackAnalytics()

    private var playJob: Job? = null
    private var tickJob: Job? = null
    private var active: Active? = null
    private var textGroups: List<Tracks.Group> = emptyList()
    private var pendingSubtitles: List<SubtitleOption> = emptyList()
    private var appliedCap: Int? = null
    private var appliedMinBuffer = -1
    private var appliedMaxBuffer = -1
    private var lastProgressSave = 0L

    private data class Active(
        val item: LibraryItem,
        val kind: StreamKind,
        val requiresVpn: Boolean,
        val startedAt: Long,
    )

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val duration = player.duration.takeIf { it > 0 } ?: _state.value.durationMs
            if (playbackState == Player.STATE_BUFFERING) analytics.stallStarted()
            if (playbackState == Player.STATE_READY) analytics.stallEnded()

            if (playbackState == Player.STATE_READY && duration > 0) {
                val session = active
                if (session != null && session.item.durationMs <= 0) {
                    scope.launch(Dispatchers.IO) {
                        runCatching { library.setDuration(session.item.id, duration) }
                    }
                }
            }
            if (playbackState == Player.STATE_ENDED) {
                analytics.stallEnded()
                saveProgress(completed = true)
            }
            _state.value = _state.value.copy(
                preparing = playbackState == Player.STATE_BUFFERING && !player.isPlaying,
                playing = player.isPlaying,
                buffering = playbackState == Player.STATE_BUFFERING,
                ended = playbackState == Player.STATE_ENDED,
                durationMs = duration,
                positionMs = player.currentPosition,
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(
                playing = isPlaying,
                preparing = if (isPlaying) false else _state.value.preparing,
            )
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.value = _state.value.copy(
                videoWidth = videoSize.width,
                videoHeight = videoSize.height,
            )
        }

        override fun onTracksChanged(tracks: Tracks) {
            analytics.updateTracks(tracks)
            textGroups = tracks.groups.filter {
                it.type == C.TRACK_TYPE_TEXT && it.length > 0
            }
            val derived = if (pendingSubtitles.isNotEmpty()) {
                pendingSubtitles
            } else {
                textGroups.mapIndexed { index, group ->
                    SubtitleOption(
                        index = index,
                        label = subtitleLabel(group),
                        url = "",
                    )
                }
            }
            val selected = if (_state.value.selectedSubtitle in derived.indices) {
                _state.value.selectedSubtitle
            } else {
                -1
            }
            _state.value = _state.value.copy(subtitles = derived, selectedSubtitle = selected)
        }

        override fun onPlayerError(error: PlaybackException) {
            analytics.stallEnded()
            val kind = active?.kind ?: _state.value.kind
            _state.value = _state.value.copy(
                preparing = false,
                playing = false,
                buffering = false,
                error = PlaybackErrors.friendly(error, kind),
            )
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            val elapsed = if (loadEventInfo.loadDurationMs > 0) {
                loadEventInfo.loadDurationMs
            } else {
                eventTime.realtimeMs - loadEventInfo.elapsedRealtimeMs
            }
            analytics.onLoadCompleted(loadEventInfo.bytesLoaded, elapsed)
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            analytics.onBandwidthEstimate(totalBytesLoaded, totalLoadTimeMs.toLong())
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            analytics.onDroppedFrames(droppedFrames)
        }
    }

    init {
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
        startTicker()
        restoreCacheBudget()
        scope.launch {
            vpnMonitor.status.collect { status ->
                val session = active ?: return@collect
                if (!settingsStore.current().requireVpnForNetwork) return@collect
                if (!status.active && session.requiresVpn && player.isPlaying) {
                    player.pause()
                    _state.value = _state.value.copy(
                        playing = false,
                        error = VpnRequiredMessage.playback,
                    )
                } else if (status.active && _state.value.error == VpnRequiredMessage.playback) {
                    // VPN is back. Do not auto-play; only clear the blocker so
                    // the user can press Play deliberately.
                    _state.value = _state.value.copy(error = null)
                }
            }
        }
    }

    // ---------------------------------------------------------------- playback

    /**
     * Plays the source attached to [item]. Magnet sources are opened through the
     * torrent engine first; no public index or content search is involved.
     */
    fun play(item: LibraryItem, startOverrideMs: Long? = null) {
        val uri = item.sourceUri?.trim().orEmpty()
        if (uri.isEmpty()) {
            _state.value = PlayerUiState(
                itemId = item.id,
                title = item.title,
                error = "No source is attached to this title. Attach your own authorised magnet, link or file first.",
            )
            return
        }

        playJob?.cancel()
        val settings = settingsStore.current()
        val isMagnet = uri.startsWith("magnet:", ignoreCase = true)
        val torrentFile = if (uri.startsWith(TORRENT_FILE_SCHEME, ignoreCase = true)) {
            runCatching { File(Uri.parse(uri).path ?: "") }.getOrNull()
        } else {
            null
        }
        val isTorrentFile = torrentFile != null
        val isLocal = !isMagnet && !isTorrentFile && isLocalUri(uri)
        val downloaded = !isMagnet && !isTorrentFile &&
            runCatching { Downloads.completed(context, uri) }.getOrNull() != null
        val requiresVpn = !isMagnet && !isTorrentFile && !isLocal && !downloaded

        if (settings.requireVpnForNetwork && (isMagnet || isTorrentFile || requiresVpn) && !vpnMonitor.isActive()) {
            _state.value = PlayerUiState(
                itemId = item.id,
                title = item.title,
                sourceLabel = item.sourceLabel.orEmpty(),
                error = VpnRequiredMessage.playback,
            )
            return
        }

        // Persist progress of whatever was playing before switching titles.
        saveProgress(completed = false)

        val startMs = (startOverrideMs ?: item.progressMs).coerceAtLeast(0L)
        val fraction = if (item.durationMs > 0) {
            (startMs.toDouble() / item.durationMs.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        _state.value = PlayerUiState(
            itemId = item.id,
            title = item.title,
            sourceLabel = item.sourceLabel.orEmpty(),
            preparing = true,
            positionMs = startMs,
            durationMs = item.durationMs,
        )

        playJob = scope.launch {
            if (isMagnet || isTorrentFile) {
                val result = if (isMagnet) {
                    torrentManager.open(uri, fraction)
                } else {
                    torrentManager.openTorrentFile(torrentFile!!, fraction)
                }
                val open = result.getOrElse { error ->
                    _state.value = _state.value.copy(
                        preparing = false,
                        playing = false,
                        error = error.message ?: "The torrent could not be opened.",
                    )
                    return@launch
                }
                startPlayback(
                    item = item,
                    kind = StreamKind.TORRENT,
                    uri = open.mediaUrl,
                    subtitles = open.subtitles.map { subtitle ->
                        SubtitleOption(subtitle.index, subtitle.label, subtitle.url)
                    },
                    startMs = startMs,
                    requiresVpn = true,
                )
            } else {
                startPlayback(
                    item = item,
                    kind = if (downloaded) kindFor(uri) else detectKind(uri),
                    uri = uri,
                    subtitles = emptyList(),
                    startMs = startMs,
                    requiresVpn = requiresVpn,
                )
            }
        }
    }

    private fun startPlayback(
        item: LibraryItem,
        kind: StreamKind,
        uri: String,
        subtitles: List<SubtitleOption>,
        startMs: Long,
        requiresVpn: Boolean,
    ) {
        active = Active(
            item = item,
            kind = kind,
            requiresVpn = requiresVpn,
            startedAt = System.currentTimeMillis(),
        )
        pendingSubtitles = subtitles
        textGroups = emptyList()
        appliedCap = null
        analytics.reset()
        optimizer.setSession(item.id)
        lastProgressSave = System.currentTimeMillis()

        val downloaded = !uri.startsWith("magnet:") &&
            runCatching { Downloads.completed(context, uri) }.getOrNull() != null

        val source = mediaSourceFor(kind, uri, item, subtitles, downloaded)
        player.setMediaSource(source, startMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true

        _state.value = _state.value.copy(
            itemId = item.id,
            title = item.title,
            kind = kind,
            sourceLabel = item.sourceLabel.orEmpty(),
            preparing = true,
            playing = true,
            buffering = true,
            error = null,
            subtitles = subtitles,
            selectedSubtitle = -1,
            isTorrent = kind.isTorrent,
            positionMs = startMs,
        )
    }

    private fun mediaSourceFor(
        kind: StreamKind,
        uri: String,
        item: LibraryItem,
        subtitles: List<SubtitleOption>,
        downloaded: Boolean,
    ): MediaSource {
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .build(),
            )
        if (subtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(
                subtitles.map { subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLabel(subtitle.label)
                        .setLanguage(subtitle.label.substringBefore('.').take(8))
                        .build()
                },
            )
        }
        val mediaItem = builder.build()
        val factory = when {
            kind == StreamKind.TORRENT -> directMediaSourceFactory
            downloaded -> offlineMediaSourceFactory()
            kind == StreamKind.LOCAL -> directMediaSourceFactory
            kind == StreamKind.HLS || kind == StreamKind.DASH -> cachedMediaSourceFactory
            else -> cachedMediaSourceFactory
        }
        return factory.createMediaSource(mediaItem)
    }

    private fun offlineMediaSourceFactory(): MediaSource.Factory {
        val cacheFactory = CacheDataSource.Factory()
            .setCache(Downloads.cache(context))
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DefaultMediaSourceFactory(context).setDataSourceFactory(cacheFactory)
    }

    fun retry() {
        if (active == null) return
        _state.value = _state.value.copy(error = null, preparing = true)
        player.prepare()
    }

    fun togglePlay() {
        if (player.isPlaying) {
            pause()
        } else {
            val session = active
            if (session != null && session.requiresVpn &&
                settingsStore.current().requireVpnForNetwork && !vpnMonitor.isActive()
            ) {
                _state.value = _state.value.copy(error = VpnRequiredMessage.playback)
                return
            }
            player.play()
        }
    }

    fun pause() {
        player.pause()
        _state.value = _state.value.copy(playing = false)
        saveProgress(completed = false)
    }

    fun seekTo(positionMs: Long) {
        val duration = player.duration
        val clamped = when {
            positionMs < 0 -> 0L
            duration > 0 -> positionMs.coerceAtMost(duration)
            else -> positionMs
        }
        player.seekTo(clamped)
        _state.value = _state.value.copy(positionMs = clamped, ended = false)
    }

    fun seekBy(deltaMs: Long) {
        seekTo((player.currentPosition + deltaMs).coerceAtLeast(0L))
    }

    /**
     * Enables the subtitle at [index] from [PlayerUiState.subtitles], or turns
     * subtitles off when [index] is negative. Only tracks the player already
     * reports are selectable.
     */
    fun selectSubtitle(index: Int) {
        if (index < 0) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
            _state.value = _state.value.copy(selectedSubtitle = -1)
            return
        }
        val group = textGroups.getOrNull(index) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        _state.value = _state.value.copy(selectedSubtitle = index)
    }

    fun stop(saveProgress: Boolean = true) {
        playJob?.cancel()
        playJob = null
        val session = active
        if (session != null) {
            if (saveProgress) {
                saveProgress(completed = player.playbackState == Player.STATE_ENDED)
            }
            persistSessionSummary(session)
        }
        if (session?.kind == StreamKind.TORRENT) {
            torrentManager.setOptimizerAhead(null)
            torrentManager.stop()
        }
        player.stop()
        player.clearMediaItems()
        active = null
        pendingSubtitles = emptyList()
        textGroups = emptyList()
        _state.value = PlayerUiState()
        _stats.value = PlaybackStatsUi()
    }

    fun release() {
        if (active != null) stop(saveProgress = true)
        runCatching { player.removeListener(playerListener) }
        runCatching { player.removeAnalyticsListener(analyticsListener) }
        runCatching { session.release() }
        runCatching { player.release() }
        scope.cancel()
    }

    fun onSettingsChanged() {
        val settings = settingsStore.current()
        PlaybackCache.reconfigure(context, settings.playbackCacheMb.toLong() * 1024L * 1024L)
        appliedMinBuffer = -1
        appliedMaxBuffer = -1
        torrentManager.applyRateLimits(settings)
        runCatching { Downloads.setWifiOnly(context, settings.wifiOnlyDownloads) }

        val session = active
        if (session != null && settings.requireVpnForNetwork && session.requiresVpn &&
            !vpnMonitor.isActive() && player.isPlaying
        ) {
            player.pause()
            _state.value = _state.value.copy(playing = false, error = VpnRequiredMessage.playback)
        }
    }

    // ------------------------------------------------------------------- ticker

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                delay(1_000)
                tick()
            }
        }
    }

    private fun tick() {
        if (active == null) return
        val now = System.currentTimeMillis()
        val torrentState = torrentManager.state.value
        val snapshot = analytics.snapshot(player, torrentState, context, refreshCache = true)
        _stats.value = snapshot

        val duration = player.duration.takeIf { it > 0 } ?: _state.value.durationMs
        val position = player.currentPosition
        _state.value = _state.value.copy(
            positionMs = position,
            durationMs = duration,
            playing = player.isPlaying,
            buffering = player.playbackState == Player.STATE_BUFFERING,
        )

        val session = active ?: return
        val telemetry = PlaybackTelemetry(
            atMs = now,
            bufferSeconds = snapshot.bufferSeconds,
            throughputBps = snapshot.throughputBps,
            bitrateBps = snapshot.bitrateBps.toDouble(),
            peerDownRateBps = snapshot.torrentDownloadBps.toDouble(),
            peerCount = snapshot.peers,
            isTorrent = session.kind.isTorrent,
            isAdaptive = session.kind.isAdaptive,
            playing = player.isPlaying,
            stalling = player.playbackState == Player.STATE_BUFFERING,
            positionMs = position,
            durationMs = duration,
        )

        val decision = optimizer.evaluate(telemetry).decision
        if (decision.startBufferSeconds != appliedMinBuffer ||
            decision.bufferTargetSeconds != appliedMaxBuffer
        ) {
            appliedMinBuffer = decision.startBufferSeconds
            appliedMaxBuffer = decision.bufferTargetSeconds
            loadControl.updateTargets(
                decision.startBufferSeconds,
                decision.bufferTargetSeconds,
                snapshot.bitrateBps,
            )
        }
        if (session.kind.isAdaptive) {
            applyQualityCap(decision.maxVideoBitrateBps)
        } else if (appliedCap != null) {
            applyQualityCap(null)
        }

        if (session.kind.isTorrent) {
            torrentManager.onPlayhead(position, duration)
            torrentManager.setOptimizerAhead(decision.torrentAheadBytes)
        }

        if (now - lastProgressSave >= PROGRESS_SAVE_MS) {
            lastProgressSave = now
            saveProgress(completed = false)
        }
    }

    /**
     * Adaptive sources only: cap the maximum video bitrate but leave ABR free to
     * choose anything at or below it. Fixed-quality torrents and local files are
     * never re-resolved.
     */
    private fun applyQualityCap(cap: Int?) {
        if (appliedCap == cap) return
        appliedCap = cap
        val builder = player.trackSelectionParameters.buildUpon()
        if (cap == null || cap <= 0) {
            builder.setMaxVideoBitrate(Integer.MAX_VALUE)
        } else {
            builder.setMaxVideoBitrate(cap.coerceAtLeast(1))
        }
        player.trackSelectionParameters = builder.build()
    }

    // ---------------------------------------------------------------- helpers

    private fun saveProgress(completed: Boolean) {
        val session = active ?: return
        val position = player.currentPosition
        val duration = player.duration.takeIf { it > 0 } ?: session.item.durationMs
        if (position <= 0) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                library.updateProgress(
                    id = session.item.id,
                    positionMs = position,
                    durationMs = duration,
                    completed = completed,
                )
            }
        }
    }

    private fun persistSessionSummary(session: Active) {
        val snapshot = analytics.snapshot(player, torrentManager.state.value, context, refreshCache = false)
        val entity = optimizer.buildSession(
            title = session.item.title,
            sourceKind = session.kind.name,
            mode = settingsStore.current().optimizerMode.name,
            startedAt = session.startedAt,
            endedAt = System.currentTimeMillis(),
            playbackMs = analytics.sessionDurationMs(),
            stallCount = snapshot.stallCount,
            stallMs = snapshot.stallMs,
            avgThroughputBps = snapshot.throughputBps.toLong(),
            avgBitrateBps = snapshot.bitrateBps,
            bytesLoaded = snapshot.bytesLoaded,
            metrics = optimizer.metrics(),
        )
        optimizer.saveSession(entity)
        optimizer.setSession(0L)
    }

    private fun restoreCacheBudget() {
        val settings = settingsStore.current()
        PlaybackCache.reconfigure(context, settings.playbackCacheMb.toLong() * 1024L * 1024L)
        torrentManager.applyRateLimits(settings)
        loadControl.updateTargets(settings.startBufferSeconds, settings.bufferTargetSeconds)
        appliedMinBuffer = settings.startBufferSeconds
        appliedMaxBuffer = settings.bufferTargetSeconds
    }

    private fun detectKind(uri: String): StreamKind {
        if (isLocalUri(uri)) return StreamKind.LOCAL
        val path = runCatching { Uri.parse(uri).path }.getOrNull().orEmpty().lowercase()
        return when {
            path.endsWith(".m3u8") || path.contains(".m3u8") -> StreamKind.HLS
            path.endsWith(".mpd") || path.contains(".mpd") -> StreamKind.DASH
            else -> StreamKind.MP4
        }
    }

    private fun kindFor(uri: String): StreamKind {
        val path = runCatching { Uri.parse(uri).path }.getOrNull().orEmpty().lowercase()
        return when {
            path.endsWith(".m3u8") || path.contains(".m3u8") -> StreamKind.HLS
            path.endsWith(".mpd") || path.contains(".mpd") -> StreamKind.DASH
            else -> StreamKind.MP4
        }
    }

    private fun isLocalUri(uri: String): Boolean {
        val scheme = runCatching { Uri.parse(uri).scheme }.getOrNull()?.lowercase()
        return scheme == null ||
            scheme == "file" ||
            scheme == "content" ||
            scheme == "android.resource" ||
            scheme == "asset"
    }

    private fun subtitleLabel(group: Tracks.Group): String {
        val format = group.getTrackFormat(0)
        return format.label
            ?: format.language
            ?: format.sampleMimeType?.substringAfterLast('/')?.uppercase()
            ?: "Subtitle"
    }

    private companion object {
        const val PROGRESS_SAVE_MS = 5_000L
        const val TORRENT_FILE_SCHEME = "torrent-file://"
    }
}

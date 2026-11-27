package com.streambuddy.app

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streambuddy.app.cache.PlaybackCache
import com.streambuddy.app.cache.SmartPrefetch
import com.streambuddy.app.data.AppSettings
import com.streambuddy.app.data.LibraryItem
import com.streambuddy.app.data.LibraryStatus
import com.streambuddy.app.data.MediaType
import com.streambuddy.app.data.MetadataCandidate
import com.streambuddy.app.data.PredictorRepository
import com.streambuddy.app.download.Downloads
import com.streambuddy.app.metadata.TorrentRelease
import com.streambuddy.app.torrent.Magnet
import com.streambuddy.app.ui.AddTitleScreen
import com.streambuddy.app.ui.AppsScreen
import com.streambuddy.app.ui.BrowserScreen
import com.streambuddy.app.ui.CatalogDetailScreen
import com.streambuddy.app.ui.DetailScreen
import com.streambuddy.app.ui.DiscoverScreen
import com.streambuddy.app.ui.DownloadsScreen
import com.streambuddy.app.ui.LibraryScreen
import com.streambuddy.app.ui.PlayerScreen
import com.streambuddy.app.ui.SettingsScreen
import com.streambuddy.app.ui.StreamBuddyTheme
import com.streambuddy.app.ui.validateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A magnet, link, .torrent or local video that arrived through an Android intent. */
data class SharedSource(
    val uri: String,
    val label: String?,
    val isTorrentFile: Boolean = false,
)

enum class AppTab { LIBRARY, BROWSER, APPS, STORAGE, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamBuddyRoot(
    container: AppContainer,
    sharedSource: SharedSource?,
    onSharedSourceHandled: () -> Unit,
    requestedTab: AppTab? = null,
    onRequestedTabHandled: () -> Unit = {},
    requestedShowLatest: Boolean = false,
    onRequestedShowLatestHandled: () -> Unit = {},
    requestedOpenPlayer: Boolean = false,
    onRequestedOpenPlayerHandled: () -> Unit = {},
) {
    StreamBuddyTheme {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val snackbarHost = remember { SnackbarHostState() }

        val items by container.libraryRepository.items.collectAsStateWithLifecycle(initialValue = emptyList())
        val settings by container.settingsStore.settings.collectAsStateWithLifecycle()
        val playerState by container.playerEngine.state.collectAsStateWithLifecycle()
        val stats by container.playerEngine.stats.collectAsStateWithLifecycle()
        val optimizer by container.optimizerRuntime.state.collectAsStateWithLifecycle()
        val predictorStatus by PredictorRepository.get(context).state.collectAsStateWithLifecycle()
        val torrent by container.torrentManager.state.collectAsStateWithLifecycle()
        val torrentDownload by container.torrentDownloadManager.state.collectAsStateWithLifecycle()
        val vpn by container.vpnMonitor.status.collectAsStateWithLifecycle()
        val security by container.securityCenter.state.collectAsStateWithLifecycle()
        val preCacheStates by container.libraryPreCacher.states.collectAsStateWithLifecycle()
        val predictions by container.libraryPreCacher.predictions.collectAsStateWithLifecycle()

        var tab by remember { mutableStateOf(AppTab.LIBRARY) }
        var showLatestCatalog by remember { mutableStateOf(false) }
        var catalogItem by remember { mutableStateOf<MetadataCandidate?>(null) }
        var detailId by remember { mutableStateOf<Long?>(null) }
        var addOpen by remember { mutableStateOf(false) }
        var playerOpen by remember { mutableStateOf(false) }
        var browserUrl by remember { mutableStateOf<String?>(null) }
        var torrentStorageBytes by remember { mutableLongStateOf(0L) }
        var cacheUsedBytes by remember { mutableLongStateOf(0L) }

        val cacheMaxBytes = settings.playbackCacheMb.toLong() * 1024L * 1024L
        val playerActive = playerState.playing || playerState.buffering || playerState.preparing
        val vpnBlocked = settings.requireVpnForNetwork && !vpn.active

        LaunchedEffect(requestedTab, requestedShowLatest) {
            if (requestedTab != null) {
                tab = requestedTab
                if (requestedTab == AppTab.LIBRARY) {
                    showLatestCatalog = requestedShowLatest
                }
                playerOpen = false
                addOpen = false
                catalogItem = null
                detailId = null
                onRequestedTabHandled()
                if (requestedShowLatest) onRequestedShowLatestHandled()
            } else if (requestedShowLatest) {
                tab = AppTab.LIBRARY
                showLatestCatalog = true
                playerOpen = false
                addOpen = false
                catalogItem = null
                detailId = null
                onRequestedShowLatestHandled()
            }
        }

        LaunchedEffect(requestedOpenPlayer) {
            if (requestedOpenPlayer) {
                if (playerState.itemId > 0L) {
                    playerOpen = true
                }
                onRequestedOpenPlayerHandled()
            }
        }

        LaunchedEffect(torrent.phase, tab, cacheMaxBytes) {
            while (true) {
                torrentStorageBytes = withContext(Dispatchers.IO) {
                    container.torrentManager.storageUsedBytes()
                }
                cacheUsedBytes = withContext(Dispatchers.IO) {
                    runCatching { PlaybackCache.usage(context, cacheMaxBytes).bytes }.getOrDefault(0L)
                }
                delay(3_000)
            }
        }

        val detailItem = detailId?.let { id -> items.firstOrNull { it.id == id } }
        LaunchedEffect(detailId, items) {
            if (detailId != null && items.isNotEmpty() && detailItem == null) detailId = null
        }

        fun openPlayer(item: LibraryItem, startOverride: Long?) {
            container.playerEngine.play(item, startOverride)
            playerOpen = true
        }

        suspend fun ensureLibraryId(item: MetadataCandidate): Long =
            container.libraryRepository.findByTmdb(item.tmdbId, item.mediaType)?.id
                ?: container.libraryRepository.add(item)

        fun watchCatalog(item: MetadataCandidate, release: TorrentRelease) {
            scope.launch {
                val id = ensureLibraryId(item)
                val infoHash = runCatching {
                    Magnet.parse(release.magnet).getOrThrow().infoHashHex
                }.getOrNull()
                container.libraryRepository.attachSource(
                    id = id,
                    sourceUri = release.magnet,
                    label = "${release.provider} · ${release.detailLabel}",
                    infoHash = infoHash,
                )
                val libraryItem = container.libraryRepository.find(id) ?: return@launch
                catalogItem = null
                openPlayer(libraryItem, null)
            }
        }

        fun downloadCatalog(item: MetadataCandidate, release: TorrentRelease) {
            scope.launch {
                val id = ensureLibraryId(item)
                val infoHash = runCatching {
                    Magnet.parse(release.magnet).getOrThrow().infoHashHex
                }.getOrNull()
                container.libraryRepository.attachSource(
                    id = id,
                    sourceUri = release.magnet,
                    label = "Download · ${release.provider} · ${release.detailLabel}",
                    infoHash = infoHash,
                )
                container.torrentDownloadManager.start(
                    itemKey = "${item.mediaType}-${item.tmdbId}",
                    title = item.title,
                    magnet = release.magnet,
                )
            }
        }

        fun playDownloaded(item: MetadataCandidate?, path: String) {
            scope.launch {
                val file = java.io.File(path)
                val id = if (item != null) {
                    ensureLibraryId(item).also {
                        container.libraryRepository.attachSource(
                            id = it,
                            sourceUri = Uri.fromFile(file).toString(),
                            label = "Downloaded · ${file.name}",
                            infoHash = null,
                        )
                    }
                } else {
                    container.libraryRepository.addCustom(
                        title = file.nameWithoutExtension.ifBlank { "Downloaded media" },
                        sourceUri = Uri.fromFile(file).toString(),
                        sourceLabel = "Downloaded · ${file.name}",
                        infoHash = null,
                        mediaType = MediaType.MOVIE,
                    )
                }
                val libraryItem = container.libraryRepository.find(id) ?: return@launch
                catalogItem = null
                openPlayer(libraryItem, null)
            }
        }

        BackHandler(enabled = playerOpen) { playerOpen = false }
        BackHandler(enabled = !playerOpen && addOpen) { addOpen = false }
        BackHandler(enabled = !playerOpen && !addOpen && catalogItem != null) { catalogItem = null }
        BackHandler(enabled = !playerOpen && !addOpen && catalogItem == null && detailId != null) {
            detailId = null
        }
        BackHandler(
            enabled = !playerOpen && !addOpen && catalogItem == null && detailId == null &&
                tab == AppTab.LIBRARY && showLatestCatalog,
        ) {
            showLatestCatalog = false
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                playerOpen -> {
                    PlayerScreen(
                        player = container.playerEngine.player,
                        state = playerState,
                        stats = stats,
                        optimizer = optimizer,
                        torrent = torrent,
                        settings = settings,
                        vpnActive = vpn.active,
                        vpnSummary = vpn.summary,
                        onBack = { playerOpen = false },
                        onTogglePlay = { container.playerEngine.togglePlay() },
                        onSeekTo = { container.playerEngine.seekTo(it) },
                        onSeekBy = { container.playerEngine.seekBy(it) },
                        onSelectSubtitle = { container.playerEngine.selectSubtitle(it) },
                        onSelectAudio = { container.playerEngine.selectAudioTrack(it) },
                        onSetSpeed = { container.playerEngine.setPlaybackSpeed(it) },
                        onSelectTorrentFile = { container.playerEngine.switchTorrentFile(it) },
                        onCancelAutoplay = { container.playerEngine.cancelAutoplay() },
                        onPlayNextNow = { container.playerEngine.playNextNow() },
                        onRetry = { container.playerEngine.retry() },
                        onTorrentPause = { container.torrentManager.pause() },
                        onTorrentResume = { container.torrentManager.resume() },
                        onStopPlayback = {
                            container.playerEngine.stop()
                            playerOpen = false
                        },
                        onSeedingChange = { enabled ->
                            container.torrentManager.setSeeding(enabled)
                        },
                        onOpenSettings = { playerOpen = false; tab = AppTab.SETTINGS },
                    )
                }

                addOpen -> {
                    AddTitleScreen(
                        metadata = container.metadataRepository,
                        library = container.libraryRepository,
                        torrentManager = container.torrentManager,
                        onAdded = { id ->
                            addOpen = false
                            detailId = id
                        },
                        onBack = { addOpen = false },
                        onOpenSettings = {
                            addOpen = false
                            tab = AppTab.SETTINGS
                        },
                    )
                }

                catalogItem != null -> {
                    val catalog = catalogItem!!
                    CatalogDetailScreen(
                        item = catalog,
                        discover = container.discoverRepository,
                        inLibrary = items.any {
                            it.tmdbId == catalog.tmdbId && it.mediaType == catalog.mediaType
                        },
                        vpnBlocked = vpnBlocked,
                        download = torrentDownload,
                        onBack = { catalogItem = null },
                        onWatchNow = { item, release -> watchCatalog(item, release) },
                        onDownload = { item, release -> downloadCatalog(item, release) },
                        onPlayDownload = { item, path -> playDownloaded(item, path) },
                        onAddToLibrary = { item ->
                            scope.launch {
                                val id = ensureLibraryId(item)
                                catalogItem = null
                                detailId = id
                            }
                        },
                        onOpenBrowser = { url ->
                            catalogItem = null
                            browserUrl = url
                            tab = AppTab.BROWSER
                        },
                        onCancelDownload = { container.torrentDownloadManager.cancel() },
                    )
                }

                detailItem != null -> {
                    DetailScreen(
                        item = detailItem,
                        playerState = playerState,
                        motn = container.motnRepository,
                        vpnBlocked = vpnBlocked,
                        preCacheState = preCacheStates[detailItem.id],
                        prediction = predictions[detailItem.id],
                        prefetchMinutes = settings.prefetchMinutes,
                        onBack = { detailId = null },
                        onPlay = { item, startOverride -> openPlayer(item, startOverride) },
                        onAttachSource = { item, uri, label ->
                            scope.launch {
                                container.libraryRepository.attachSource(
                                    id = item.id,
                                    sourceUri = uri,
                                    label = label,
                                    infoHash = runCatching {
                                        Magnet.parse(uri).getOrThrow().infoHashHex
                                    }.getOrNull(),
                                )
                            }
                        },
                        onAttachTorrentUri = { item, uri, onDone ->
                            scope.launch {
                                container.torrentManager.importTorrentUri(uri)
                                    .onSuccess { imported ->
                                        container.libraryRepository.attachSource(
                                            id = item.id,
                                            sourceUri = "torrent-file://${imported.file.absolutePath}",
                                            label = "Torrent file · ${imported.name}",
                                            infoHash = imported.infoHash,
                                        )
                                        onDone(null)
                                    }
                                    .onFailure { err ->
                                        onDone(err.message ?: "That .torrent file is not valid.")
                                    }
                            }
                        },
                        onClearSource = { item ->
                            scope.launch { container.libraryRepository.clearSource(item.id) }
                        },
                        onSetWatchlist = { item, watchlist ->
                            scope.launch {
                                container.libraryRepository.setStatus(
                                    item.id,
                                    if (watchlist) LibraryStatus.WATCHLIST else LibraryStatus.WATCHING,
                                )
                            }
                        },
                        onSetWatched = { item, watched ->
                            scope.launch {
                                container.libraryRepository.setStatus(
                                    item.id,
                                    if (watched) LibraryStatus.WATCHED else LibraryStatus.WATCHING,
                                )
                            }
                        },
                        onDelete = { item ->
                            scope.launch {
                                container.libraryRepository.delete(item.id)
                                detailId = null
                            }
                        },
                        onDownload = { item ->
                            val source = item.sourceUri
                            if (!source.isNullOrBlank() && Downloads.isHttpUri(source)) {
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        Downloads.enqueue(
                                            context = context,
                                            uri = source,
                                            mimeType = Downloads.mimeForUri(source),
                                        )
                                    }
                                }
                            }
                        },
                        onPreCache = { item ->
                            container.libraryPreCacher.precache(item)
                        },
                        onCancelPreCache = { id ->
                            container.libraryPreCacher.cancel(id)
                        },
                        onOpenPlayer = { playerOpen = true },
                        onWatchInBrowser = { link ->
                            browserUrl = link
                            detailId = null
                            tab = AppTab.BROWSER
                        },
                    )
                }

                else -> {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        snackbarHost = { SnackbarHost(snackbarHost) },
                        bottomBar = {
                            Column {
                                if (playerState.itemId > 0L && playerState.title.isNotBlank()) {
                                    MiniPlayerBar(
                                        title = playerState.title,
                                        playing = playerState.playing,
                                        buffering = playerState.buffering,
                                        onTogglePlay = { container.playerEngine.togglePlay() },
                                        onOpen = { playerOpen = true },
                                    )
                                }
                                BottomTabs(tab) { tab = it }
                            }
                        },
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) {
                            when (tab) {
                                AppTab.LIBRARY -> if (showLatestCatalog) {
                                    DiscoverScreen(
                                        discover = container.discoverRepository,
                                        vpnBlocked = vpnBlocked,
                                        onOpen = { catalogItem = it },
                                        onOpenSettings = { tab = AppTab.SETTINGS },
                                        onShowLibrary = { showLatestCatalog = false },
                                    )
                                } else {
                                    LibraryScreen(
                                        items = items,
                                        onOpen = { detailId = it.id },
                                        onPlay = { openPlayer(it, null) },
                                        onAdd = { addOpen = true },
                                        vpnWarning = if (vpnBlocked) vpn.summary else null,
                                        predictions = predictions,
                                        preCacheStates = preCacheStates,
                                        onShowLatest = { showLatestCatalog = true },
                                    )
                                }

                                AppTab.BROWSER -> BrowserScreen(
                                    security = container.securityCenter,
                                    vpnBlocked = vpnBlocked,
                                    initialUrl = browserUrl,
                                    onInitialUrlConsumed = { browserUrl = null },
                                    onAddSource = { title, uri, label, infoHash, mediaType ->
                                        scope.launch {
                                            val id = container.libraryRepository.addCustom(
                                                title = title,
                                                sourceUri = uri,
                                                sourceLabel = label,
                                                infoHash = infoHash,
                                                mediaType = mediaType,
                                            )
                                            detailId = id
                                            tab = AppTab.LIBRARY
                                        }
                                    },
                                    onAddTorrent = { title, file, mediaType ->
                                        scope.launch {
                                            val imported = container.torrentManager
                                                .importTorrentFile(file)
                                                .getOrNull()
                                            if (imported != null) {
                                                val id = container.libraryRepository.addCustom(
                                                    title = title,
                                                    sourceUri = "torrent-file://${imported.file.absolutePath}",
                                                    sourceLabel = "Torrent file · ${imported.name}",
                                                    infoHash = imported.infoHash,
                                                    mediaType = mediaType,
                                                )
                                                runCatching { file.delete() }
                                                detailId = id
                                                tab = AppTab.LIBRARY
                                            }
                                        }
                                    },
                                    onAddLocalMedia = { title, file ->
                                        scope.launch {
                                            val id = container.libraryRepository.addCustom(
                                                title = title,
                                                sourceUri = Uri.fromFile(file).toString(),
                                                sourceLabel = "Downloaded · ${file.name}",
                                                infoHash = null,
                                                mediaType = MediaType.MOVIE,
                                            )
                                            detailId = id
                                            tab = AppTab.LIBRARY
                                        }
                                    },
                                    onOpenSettings = { tab = AppTab.SETTINGS },
                                )

                                AppTab.APPS -> AppsScreen(
                                    onMessage = { msg ->
                                        scope.launch { snackbarHost.showSnackbar(msg) }
                                    },
                                )

                                AppTab.STORAGE -> DownloadsScreen(
                                    torrent = torrent,
                                    torrentStorageBytes = torrentStorageBytes,
                                    cacheMaxBytes = cacheMaxBytes,
                                    playerActive = playerActive,
                                    vpnBlocked = vpnBlocked,
                                    smartPrefetchEnabled = settings.smartPrefetch,
                                    libraryItems = items,
                                    preCacheStates = preCacheStates,
                                    predictions = predictions,
                                    onClearTorrentStorage = {
                                        container.torrentManager.clearTorrentStorage()
                                    },
                                    onClearPreCacheStates = {
                                        container.libraryPreCacher.clearCachedStates()
                                    },
                                    onCancelPreCache = { id ->
                                        container.libraryPreCacher.cancel(id)
                                    },
                                    onRunSmartPrefetch = {
                                        container.libraryPreCacher.runSmartPrefetchNow()
                                        scope.launch {
                                            snackbarHost.showSnackbar("Smart pre-cache scheduled")
                                        }
                                    },
                                    onOpenSettings = { tab = AppTab.SETTINGS },
                                    torrentDownload = torrentDownload,
                                    onCancelTorrentDownload = { container.torrentDownloadManager.cancel() },
                                    onPlayTorrentDownload = { path -> playDownloaded(null, path) },
                                )

                                AppTab.SETTINGS -> SettingsScreen(
                                    settings = settings,
                                    optimizer = optimizer,
                                    predictorStatus = predictorStatus,
                                    security = security,
                                    cacheUsedBytes = cacheUsedBytes,
                                    torrentStorageBytes = torrentStorageBytes,
                                    playerActive = playerActive,
                                    vpnActive = vpn.active,
                                    vpnSummary = vpn.summary,
                                    onChange = { updated -> applySettings(context, container, updated) },
                                    onBack = { tab = AppTab.LIBRARY },
                                    onClearCache = {
                                        if (!playerActive) {
                                            scope.launch {
                                                cacheUsedBytes = withContext(Dispatchers.IO) {
                                                    runCatching {
                                                        PlaybackCache.clear(context)
                                                        PlaybackCache.usage(context, cacheMaxBytes).bytes
                                                    }.getOrDefault(0L)
                                                }
                                                container.libraryPreCacher.clearCachedStates()
                                            }
                                        }
                                    },
                                    onResetModel = {
                                        container.optimizerRuntime.resetModel()
                                        scope.launch(Dispatchers.IO) {
                                            runCatching { SmartPrefetch.clearHistory(context) }
                                            container.libraryPreCacher.refreshPredictions()
                                        }
                                    },
                                    onRunSmartPrefetchNow = {
                                        container.libraryPreCacher.runSmartPrefetchNow()
                                        scope.launch {
                                            snackbarHost.showSnackbar("Smart pre-cache scheduled")
                                        }
                                    },
                                    onRefreshVpn = { container.vpnMonitor.refresh() },
                                    onScanNow = {
                                        val activeDir = container.torrentManager.activeDirectory()
                                        container.securityCenter.scanNow(
                                            skip = activeDir?.let(::setOf) ?: emptySet(),
                                        )
                                    },
                                    onClearQuarantine = {
                                        scope.launch(Dispatchers.IO) {
                                            container.securityCenter.deleteQuarantined()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (sharedSource != null) {
            IncomingSourceDialog(
                source = sharedSource,
                onDismiss = onSharedSourceHandled,
                onAdd = { title, mediaType ->
                    scope.launch {
                        val id = if (sharedSource.isTorrentFile) {
                            val imported = container.torrentManager
                                .importTorrentUri(Uri.parse(sharedSource.uri))
                                .getOrNull()
                            if (imported == null) {
                                onSharedSourceHandled()
                                snackbarHost.showSnackbar("That .torrent file could not be imported.")
                                return@launch
                            }
                            container.libraryRepository.addCustom(
                                title = title,
                                sourceUri = "torrent-file://${imported.file.absolutePath}",
                                sourceLabel = "Torrent file · ${imported.name}",
                                infoHash = imported.infoHash,
                                mediaType = mediaType,
                            )
                        } else {
                            val infoHash = runCatching {
                                Magnet.parse(sharedSource.uri).getOrThrow().infoHashHex
                            }.getOrNull()
                            container.libraryRepository.addCustom(
                                title = title,
                                sourceUri = sharedSource.uri,
                                sourceLabel = sharedSource.label,
                                infoHash = infoHash,
                                mediaType = mediaType,
                            )
                        }
                        onSharedSourceHandled()
                        addOpen = false
                        playerOpen = false
                        tab = AppTab.LIBRARY
                        detailId = id
                    }
                },
            )
        }
    }
}

@Composable
private fun MiniPlayerBar(
    title: String,
    playing: Boolean,
    buffering: Boolean,
    onTogglePlay: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        buffering -> "Buffering…"
                        playing -> "Playing · tap to open player"
                        else -> "Paused · tap to open player"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onTogglePlay) {
                Text(if (playing) "Pause" else "Play")
            }
        }
    }
}

@Composable
private fun BottomTabs(selected: AppTab, onSelect: (AppTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(
            selected = selected == AppTab.LIBRARY,
            onClick = { onSelect(AppTab.LIBRARY) },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text("Library") },
        )
        NavigationBarItem(
            selected = selected == AppTab.BROWSER,
            onClick = { onSelect(AppTab.BROWSER) },
            icon = { Icon(Icons.Filled.Search, contentDescription = null) },
            label = { Text("Browser") },
        )
        NavigationBarItem(
            selected = selected == AppTab.APPS,
            onClick = { onSelect(AppTab.APPS) },
            icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            label = { Text("Apps") },
        )
        NavigationBarItem(
            selected = selected == AppTab.STORAGE,
            onClick = { onSelect(AppTab.STORAGE) },
            icon = { Icon(Icons.Filled.List, contentDescription = null) },
            label = { Text("Storage") },
        )
        NavigationBarItem(
            selected = selected == AppTab.SETTINGS,
            onClick = { onSelect(AppTab.SETTINGS) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("Settings") },
        )
    }
}

/**
 * Shared links are never added silently. The user chooses the title, and the
 * app explains that the link must be one they are authorised to use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IncomingSourceDialog(
    source: SharedSource,
    onDismiss: () -> Unit,
    onAdd: (String, MediaType) -> Unit,
) {
    val suggested = source.label
        ?.removeSuffix(".torrent")
        ?: runCatching { Magnet.parse(source.uri).getOrThrow().displayName }.getOrNull()
        ?: source.uri.substringBefore('?').substringAfterLast('/').takeIf { it.isNotBlank() }
        ?: "Shared media"

    var title by remember(source.uri) { mutableStateOf(suggested.take(300)) }
    var mediaType by remember(source.uri) { mutableStateOf(MediaType.MOVIE) }
    val validation = remember(source.uri) { validateSource(source.uri) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    source.isTorrentFile -> "Add shared .torrent?"
                    Magnet.isMagnet(source.uri) -> "Add shared magnet?"
                    else -> "Add shared media?"
                },
            )
        },
        text = {
            Column {
                Text(
                    if (source.isTorrentFile || Magnet.isMagnet(source.uri)) {
                        "Stream Buddy can add this torrent to your library. Only continue if you are authorised " +
                            "to stream this content."
                    } else {
                        "Stream Buddy can attach this media link to a library title. Only continue if you own " +
                            "or are authorised to use it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    source.uri,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(300) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Title") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MediaType.entries.forEach { type ->
                        FilterChip(
                            selected = mediaType == type,
                            onClick = { mediaType = type },
                            label = { Text(if (type == MediaType.MOVIE) "Movie" else "TV") },
                        )
                    }
                }
                if (validation != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        validation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(title.trim().ifBlank { "Shared media" }, mediaType) },
                enabled = validation == null && title.isNotBlank(),
            ) { Text("Add to library") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Ignore") } },
    )
}

private fun applySettings(context: android.content.Context, container: AppContainer, updated: AppSettings) {
    container.settingsStore.update { updated }
    container.playerEngine.onSettingsChanged()
    container.torrentManager.applyRateLimits(updated)
    container.securityCenter.onSettingsChanged()
    container.libraryPreCacher.onSettingsChanged()
    val current = container.settingsStore.current()
    runCatching { Downloads.setWifiOnly(context, current.wifiOnlyDownloads) }
}

package com.streambuddy.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streambuddy.app.AppContainer
import com.streambuddy.app.cache.PlaybackCache
import com.streambuddy.app.data.AppSettings
import com.streambuddy.app.data.LibraryItem
import com.streambuddy.app.data.LibraryStatus
import com.streambuddy.app.data.MediaType
import com.streambuddy.app.download.Downloads
import com.streambuddy.app.torrent.Magnet
import com.streambuddy.app.ui.AddTitleScreen
import com.streambuddy.app.ui.BrowserScreen
import com.streambuddy.app.ui.DetailScreen
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

/** A magnet, link or local video that arrived through an Android intent. */
data class SharedSource(val uri: String, val label: String?)

enum class AppTab { LIBRARY, BROWSER, STORAGE, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamBuddyRoot(
    container: AppContainer,
    sharedSource: SharedSource?,
    onSharedSourceHandled: () -> Unit,
    requestedTab: AppTab? = null,
    onRequestedTabHandled: () -> Unit = {},
) {
    StreamBuddyTheme {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val items by container.libraryRepository.items.collectAsStateWithLifecycle(initialValue = emptyList())
        val settings by container.settingsStore.settings.collectAsStateWithLifecycle()
        val playerState by container.playerEngine.state.collectAsStateWithLifecycle()
        val stats by container.playerEngine.stats.collectAsStateWithLifecycle()
        val optimizer by container.optimizerRuntime.state.collectAsStateWithLifecycle()
        val torrent by container.torrentManager.state.collectAsStateWithLifecycle()
        val vpn by container.vpnMonitor.status.collectAsStateWithLifecycle()
        val security by container.securityCenter.state.collectAsStateWithLifecycle()

        var tab by remember { mutableStateOf(AppTab.LIBRARY) }
        var detailId by remember { mutableStateOf<Long?>(null) }
        var addOpen by remember { mutableStateOf(false) }
        var playerOpen by remember { mutableStateOf(false) }
        var browserUrl by remember { mutableStateOf<String?>(null) }
        var torrentStorageBytes by remember { mutableLongStateOf(0L) }
        var cacheUsedBytes by remember { mutableLongStateOf(0L) }

        val cacheMaxBytes = settings.playbackCacheMb.toLong() * 1024L * 1024L
        val playerActive = playerState.playing || playerState.buffering || playerState.preparing

        LaunchedEffect(requestedTab) {
            if (requestedTab != null) {
                tab = requestedTab
                playerOpen = false
                addOpen = false
                detailId = null
                onRequestedTabHandled()
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

        BackHandler(enabled = playerOpen) { playerOpen = false }
        BackHandler(enabled = !playerOpen && addOpen) { addOpen = false }
        BackHandler(enabled = !playerOpen && !addOpen && detailId != null) { detailId = null }

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
                        onSeekBy = { container.playerEngine.seekBy(it) },
                        onSelectSubtitle = { container.playerEngine.selectSubtitle(it) },
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

                detailItem != null -> {
                    DetailScreen(
                        item = detailItem,
                        playerState = playerState,
                        motn = container.motnRepository,
                        vpnBlocked = settings.requireVpnForNetwork && !vpn.active,
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
                        onOpenPlayer = { playerOpen = true },
                        onWatchInBrowser = { link ->
                            browserUrl = link
                            tab = AppTab.BROWSER
                        },
                    )
                }

                else -> {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        bottomBar = { BottomTabs(tab) { tab = it } },
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) {
                            when (tab) {
                                AppTab.LIBRARY -> LibraryScreen(
                                    items = items,
                                    onOpen = { detailId = it.id },
                                    onPlay = { openPlayer(it, null) },
                                    onAdd = { addOpen = true },
                                    vpnWarning = if (!vpn.active && settings.requireVpnForNetwork) {
                                        vpn.summary
                                    } else {
                                        null
                                    },
                                )

                                AppTab.STORAGE -> DownloadsScreen(
                                    torrent = torrent,
                                    torrentStorageBytes = torrentStorageBytes,
                                    cacheMaxBytes = cacheMaxBytes,
                                    playerActive = playerActive,
                                    onClearTorrentStorage = {
                                        container.torrentManager.clearTorrentStorage()
                                    },
                                    onOpenSettings = { tab = AppTab.SETTINGS },
                                )

                                AppTab.BROWSER -> BrowserScreen(
                                    security = container.securityCenter,
                                    vpnBlocked = settings.requireVpnForNetwork && !vpn.active,
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
                                    onAddTorrent = { title, file ->
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
                                                    mediaType = MediaType.MOVIE,
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
                                                sourceUri = android.net.Uri.fromFile(file).toString(),
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

                                AppTab.SETTINGS -> SettingsScreen(
                                    settings = settings,
                                    optimizer = optimizer,
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
                                            }
                                        }
                                    },
                                    onResetModel = { container.optimizerRuntime.resetModel() },
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
                        val infoHash = runCatching {
                            Magnet.parse(sharedSource.uri).getOrThrow().infoHashHex
                        }.getOrNull()
                        val id = container.libraryRepository.addCustom(
                            title = title,
                            sourceUri = sharedSource.uri,
                            sourceLabel = sharedSource.label,
                            infoHash = infoHash,
                            mediaType = mediaType,
                        )
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
        ?: runCatching { Magnet.parse(source.uri).getOrThrow().displayName }.getOrNull()
        ?: source.uri.substringBefore('?').substringAfterLast('/').takeIf { it.isNotBlank() }
        ?: "Shared media"

    var title by remember(source.uri) { mutableStateOf(suggested.take(300)) }
    var mediaType by remember(source.uri) { mutableStateOf(MediaType.MOVIE) }
    val validation = remember(source.uri) { validateSource(source.uri) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (Magnet.isMagnet(source.uri)) "Add shared magnet?" else "Add shared media?") },
        text = {
            Column {
                Text(
                    if (Magnet.isMagnet(source.uri)) {
                        "Stream Buddy can add this magnet to your library. Only continue if you are authorised " +
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
    val current = container.settingsStore.current()
    runCatching { Downloads.setWifiOnly(context, current.wifiOnlyDownloads) }
}

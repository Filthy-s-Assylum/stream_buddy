package com.streambuddy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.streambuddy.app.ai.Prediction
import com.streambuddy.app.cache.CacheUsage
import com.streambuddy.app.cache.LibraryPreCacheState
import com.streambuddy.app.cache.PlaybackCache
import com.streambuddy.app.core.Fmt
import com.streambuddy.app.data.LibraryItem
import com.streambuddy.app.download.Downloads
import com.streambuddy.app.stream.CacheState
import com.streambuddy.app.torrent.TorrentDownloadState
import com.streambuddy.app.torrent.TorrentDownloadStatus
import com.streambuddy.app.torrent.TorrentUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Storage screen. It separates the three different kinds of bytes Stream Buddy
 * keeps, because they have different lifetimes:
 *
 *  - offline downloads: kept until the user deletes them;
 *  - playback cache (including pre-cached segments): evictable, safe to clear;
 *  - torrent scratch: temporary pieces, deleted when playback stops.
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun DownloadsScreen(
    torrent: TorrentUiState,
    torrentStorageBytes: Long,
    cacheMaxBytes: Long,
    playerActive: Boolean,
    vpnBlocked: Boolean,
    smartPrefetchEnabled: Boolean,
    libraryItems: List<LibraryItem>,
    preCacheStates: Map<Long, LibraryPreCacheState>,
    predictions: Map<Long, Prediction>,
    onClearTorrentStorage: () -> Unit,
    onClearPreCacheStates: () -> Unit,
    onCancelPreCache: (Long) -> Unit,
    onRunSmartPrefetch: () -> Unit,
    onOpenSettings: () -> Unit,
    torrentDownload: TorrentDownloadState? = null,
    onCancelTorrentDownload: () -> Unit = {},
    onPlayTorrentDownload: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloads by remember { mutableStateOf<List<Download>>(emptyList()) }
    var cacheUsage by remember { mutableStateOf(CacheUsage(0, 0, cacheMaxBytes)) }
    var confirmCache by remember { mutableStateOf(false) }
    var confirmTorrent by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick, torrent.phase, cacheMaxBytes) {
        while (true) {
            val (snap, usage) = withContext(Dispatchers.IO) {
                Downloads.snapshot(context) to PlaybackCache.usage(context, cacheMaxBytes)
            }
            downloads = snap
            cacheUsage = usage
            delay(2_000)
        }
    }

    val titlesById = remember(libraryItems) { libraryItems.associateBy { it.id } }
    val preCacheEntries = remember(preCacheStates, titlesById) {
        preCacheStates.values
            .sortedByDescending { it.state == CacheState.CACHING }
    }
    val topPredictions = remember(predictions, titlesById) {
        predictions.values
            .sortedByDescending { it.probability }
            .take(3)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Storage", fontWeight = FontWeight.Bold) },
                actions = { TextButton(onClick = onOpenSettings) { Text("Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            actionError?.let { message ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { actionError = null }) { Text("Dismiss") }
                    }
                }
            }

            item {
                SectionTitle("Playback cache")
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            Fmt.bytes(cacheUsage.bytes),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "of ${Fmt.bytes(cacheUsage.maxBytes)} · evicted least-recently-used first",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        ProgressLine(
                            fraction = if (cacheUsage.maxBytes <= 0) 0.0
                            else cacheUsage.bytes.toDouble() / cacheUsage.maxBytes,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { confirmCache = true },
                                enabled = !playerActive && cacheUsage.bytes > 0,
                            ) { Text("Clear playback cache") }
                            TextButton(onClick = onOpenSettings) { Text("Change limit") }
                        }
                    }
                }
            }

            if (smartPrefetchEnabled || preCacheEntries.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    SectionTitle(
                        text = "Pre-cache & smart picks",
                        trailing = if (smartPrefetchEnabled) {
                            {
                                TextButton(
                                    onClick = onRunSmartPrefetch,
                                    enabled = !vpnBlocked,
                                ) { Text("Run now") }
                            }
                        } else {
                            null
                        },
                    )
                }

                if (preCacheEntries.isEmpty() && topPredictions.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Top predicted next plays",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                topPredictions.forEach { pred ->
                                    val reason = pred.reasons.firstOrNull()?.detail
                                        ?: "${(pred.probability * 100).toInt()}% score"
                                    Text(
                                        "${pred.candidate.title} · $reason",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                items(preCacheEntries, key = { "pc-${it.itemId}" }) { entry ->
                    val title = titlesById[entry.itemId]?.title ?: "Library item #${entry.itemId}"
                    PreCacheRow(
                        title = title,
                        state = entry,
                        onCancel = { onCancelPreCache(entry.itemId) },
                    )
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                SectionTitle("Offline downloads")
            }

            if (torrentDownload != null && torrentDownload.status != TorrentDownloadStatus.IDLE) {
                item(key = "torrent-offline-dl") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                torrentDownload.title.ifBlank { torrentDownload.fileName.ifBlank { "Torrent download" } },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when (torrentDownload.status) {
                                    TorrentDownloadStatus.PREPARING -> "Fetching torrent metadata…"
                                    TorrentDownloadStatus.DOWNLOADING -> buildString {
                                        append(Fmt.percent(torrentDownload.progress))
                                        append(" · ").append(Fmt.bytes(torrentDownload.downloadedBytes))
                                        append(" / ").append(Fmt.bytes(torrentDownload.totalBytes))
                                        if (torrentDownload.rateBps > 0) {
                                            append(" · ").append(Fmt.rate(torrentDownload.rateBps.toDouble()))
                                        }
                                        if (torrentDownload.peers > 0) {
                                            append(" · ").append(torrentDownload.peers).append(" peers")
                                        }
                                    }
                                    TorrentDownloadStatus.COMPLETED -> "Completed · ${torrentDownload.fileName}"
                                    TorrentDownloadStatus.FAILED -> torrentDownload.error ?: "Failed"
                                    TorrentDownloadStatus.CANCELLED -> "Cancelled"
                                    TorrentDownloadStatus.IDLE -> ""
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (torrentDownload.status == TorrentDownloadStatus.FAILED) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            if (torrentDownload.status == TorrentDownloadStatus.DOWNLOADING) {
                                ProgressLine(torrentDownload.progress)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (torrentDownload.active) {
                                    TextButton(onClick = onCancelTorrentDownload) { Text("Cancel") }
                                }
                                val path = torrentDownload.filePath
                                if (torrentDownload.status == TorrentDownloadStatus.COMPLETED && !path.isNullOrBlank()) {
                                    Button(onClick = { onPlayTorrentDownload(path) }) { Text("Play") }
                                }
                            }
                        }
                    }
                }
            }

            if (downloads.isEmpty()) {
                item {
                    Text(
                        "No offline downloads yet. Open a title, attach a direct http(s) media link you are " +
                            "allowed to use, then choose “Download first”.",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(downloads, key = { it.request.id }) { download ->
                    DownloadRow(
                        download = download,
                        vpnBlocked = vpnBlocked,
                        onPause = {
                            Downloads.pause(context, download.request.id)
                            refreshTick++
                        },
                        onResume = {
                            runCatching { Downloads.resume(context, download.request.id) }
                                .onFailure { actionError = it.message }
                            refreshTick++
                        },
                        onRetry = {
                            runCatching { Downloads.retry(context, download) }
                                .onFailure { actionError = it.message }
                            refreshTick++
                        },
                        onRemove = {
                            Downloads.remove(context, download.request.id)
                            refreshTick++
                        },
                    )
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                SectionTitle("Torrent scratch data")
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            Fmt.bytes(torrentStorageBytes),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (torrent.active) {
                                "Pieces for the torrent you are playing. They are removed when playback stops."
                            } else {
                                "No torrent is active. Leftover scratch data can be removed safely."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { confirmTorrent = true },
                            enabled = !torrent.active && torrentStorageBytes > 0,
                        ) { Text("Remove scratch data") }
                    }
                }
            }

            item {
                Spacer(Modifier.height(14.dp))
                DisclaimerCard(
                    "Clearing the playback cache only removes copies Stream Buddy made for smoother seeking. " +
                        "Your library entries, attached sources and offline downloads are kept.",
                )
            }
        }
    }

    if (confirmCache) {
        AlertDialog(
            onDismissRequest = { confirmCache = false },
            title = { Text("Clear playback cache?") },
            text = { Text("Cached media segments are deleted. Playback continues online and rebuilds the cache.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCache = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { PlaybackCache.clear(context) }
                        }
                        onClearPreCacheStates()
                        refreshTick++
                    }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmCache = false }) { Text("Cancel") } },
        )
    }

    if (confirmTorrent) {
        AlertDialog(
            onDismissRequest = { confirmTorrent = false },
            title = { Text("Remove torrent scratch data?") },
            text = { Text("Temporary pieces are deleted. Nothing in your library changes.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearTorrentStorage()
                    confirmTorrent = false
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmTorrent = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PreCacheRow(
    title: String,
    state: LibraryPreCacheState,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    StatusPill(state.state)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = state.error ?: buildString {
                        if (state.cachedBytes > 0L) append(Fmt.bytes(state.cachedBytes)).append(" cached")
                        else append(state.state.label)
                        if (state.totalDurationMs > 0L) {
                            append(" · ")
                            append(Fmt.duration(state.cachedDurationMs / 1000.0))
                            append(" / ")
                            append(Fmt.duration(state.totalDurationMs / 1000.0))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.state == CacheState.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.state == CacheState.CACHING) {
                    Spacer(Modifier.height(5.dp))
                    ProgressLine(state.progressFraction)
                }
            }
            if (state.state == CacheState.CACHING) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Cancel") }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun DownloadRow(
    download: Download,
    vpnBlocked: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = downloadLabel(download),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append(downloadState(download.state))
                        if (download.contentLength > 0) {
                            append(" · ")
                            append(Fmt.bytes(download.bytesDownloaded))
                            append(" / ")
                            append(Fmt.bytes(download.contentLength))
                        }
                        if (download.failureReason != Download.FAILURE_REASON_NONE) {
                            append(" · failed (reason ${download.failureReason})")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (download.state == Download.STATE_DOWNLOADING && download.percentDownloaded >= 0f) {
                    Spacer(Modifier.height(5.dp))
                    ProgressLine(download.percentDownloaded / 100.0)
                }
            }
            Row(
                modifier = Modifier.padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (download.state) {
                    Download.STATE_DOWNLOADING,
                    Download.STATE_QUEUED,
                    Download.STATE_RESTARTING,
                    -> TextButton(onClick = onPause) { Text("Pause") }

                    Download.STATE_STOPPED -> TextButton(
                        onClick = onResume,
                        enabled = !vpnBlocked,
                    ) { Text("Resume") }

                    Download.STATE_FAILED -> TextButton(
                        onClick = onRetry,
                        enabled = !vpnBlocked,
                    ) { Text("Retry") }

                    Download.STATE_COMPLETED,
                    Download.STATE_REMOVING,
                    -> Unit
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove download")
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun downloadLabel(download: Download): String {
    val uri = download.request.uri
    val last = uri.lastPathSegment?.takeIf { it.isNotBlank() }
    return last ?: uri.host ?: download.request.id
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun downloadState(state: Int): String = when (state) {
    Download.STATE_QUEUED -> "Queued"
    Download.STATE_DOWNLOADING -> "Downloading"
    Download.STATE_COMPLETED -> "Ready offline"
    Download.STATE_FAILED -> "Failed"
    Download.STATE_STOPPED -> "Paused"
    Download.STATE_REMOVING -> "Removing"
    Download.STATE_RESTARTING -> "Restarting"
    else -> "Unknown"
}

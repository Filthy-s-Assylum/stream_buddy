package com.streambuddy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.streambuddy.app.cache.CacheUsage
import com.streambuddy.app.cache.PlaybackCache
import com.streambuddy.app.core.Fmt
import com.streambuddy.app.download.Downloads
import com.streambuddy.app.torrent.TorrentPhase
import com.streambuddy.app.torrent.TorrentUiState
import kotlinx.coroutines.delay

/**
 * Storage screen. It separates the three different kinds of bytes Stream Buddy
 * keeps, because they have different lifetimes:
 *
 *  - offline downloads: kept until the user deletes them;
 *  - playback cache: evictable, safe to clear at any time;
 *  - torrent scratch: temporary pieces, deleted when playback stops.
 */
@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun DownloadsScreen(
    torrent: TorrentUiState,
    torrentStorageBytes: Long,
    onClearTorrentStorage: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var downloads by remember { mutableStateOf<List<Download>>(emptyList()) }
    var cacheUsage by remember { mutableStateOf(CacheUsage(0, 0, 0)) }
    var confirmCache by remember { mutableStateOf(false) }
    var confirmTorrent by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick, torrent.phase) {
        while (true) {
            downloads = Downloads.snapshot(context)
            cacheUsage = PlaybackCache.usage(context)
            delay(2_000)
        }
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
                            Button(onClick = { confirmCache = true }) { Text("Clear playback cache") }
                            TextButton(onClick = onOpenSettings) { Text("Change limit") }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                SectionTitle("Offline downloads")
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
                    PlaybackCache.clear(context)
                    refreshTick++
                    confirmCache = false
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
private fun DownloadRow(download: Download, onRemove: () -> Unit) {
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
            Box(Modifier.padding(start = 8.dp)) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove download")
                }
            }
        }
    }
}

private fun downloadLabel(download: Download): String {
    val uri = download.request.uri
    val last = uri.lastPathSegment?.takeIf { it.isNotBlank() }
    return last ?: uri.host ?: download.request.id
}

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

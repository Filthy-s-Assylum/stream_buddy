package com.streambuddy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streambuddy.app.ai.Prediction
import com.streambuddy.app.data.StreamEntity
import com.streambuddy.app.stream.CacheState
import com.streambuddy.app.stream.StreamKind
import com.streambuddy.app.torrent.MagnetLink
import com.streambuddy.app.torrent.TorrentEngine

/** Add streams to the pre-play queue and watch their cache state. */
@Composable
fun QueueScreen(
    viewModel: MainViewModel,
    onPlay: (StreamEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val streams by viewModel.streams.collectAsStateWithLifecycle()
    val predictions by viewModel.predictions.collectAsStateWithLifecycle()
    val sharedUrl by viewModel.sharedUrl.collectAsStateWithLifecycle()
    val torrentProgress by viewModel.torrentProgress.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<StreamEntity?>(null) }
    var magnet by rememberSaveable { mutableStateOf("") }

    // A URL shared from another app (or a deep link) pre-fills the field once, then is cleared.
    LaunchedEffect(sharedUrl) {
        val incoming = sharedUrl ?: return@LaunchedEffect
        url = incoming
        viewModel.consumeSharedUrl()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(
                    title = "Pre-play queue",
                    subtitle = "Add an HLS playlist (.m3u8) or a media file and Stream Buddy " +
                        "caches the start of it, so playback begins instantly.",
                )
                predictions.values.maxByOrNull { it.probability }?.let { top ->
                    SmartPickNote(prediction = top)
                }
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Stream URL (.m3u8, .mpd, .mp4)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            viewModel.addStream(url, title)
                            url = ""
                            title = ""
                        },
                        enabled = url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add & pre-play")
                    }
                }
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Play from a magnet link",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Paste a magnet: link and Stream Buddy resolves it over BitTorrent, " +
                            "picks the file that suits this screen and network, and starts " +
                            "playing while it downloads.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = magnet,
                        onValueChange = { magnet = it },
                        label = { Text("magnet:?xt=urn:btih:…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            if (viewModel.addMagnet(magnet)) magnet = ""
                        },
                        enabled = magnet.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add torrent")
                    }
                    Text(
                        text = "BitTorrent is a general-purpose protocol — use it for content you " +
                            "have the right to access.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (streams.isEmpty()) {
            item {
                EmptyState(
                    title = "Nothing queued yet",
                    detail = "Paste a stream URL above to start caching it for instant playback.",
                )
            }
        } else {
            items(streams, key = { it.id }) { stream ->
                StreamCard(
                    stream = stream,
                    prediction = predictions[stream.id],
                    torrent = MagnetLink.parse(stream.url)
                        ?.let { magnet -> torrentProgress[magnet.infoHashHex] },
                    onPlay = { onPlay(stream) },
                    onCache = { viewModel.startCaching(stream) },
                    onCancel = { viewModel.cancelCaching(stream) },
                    onDelete = { pendingDelete = stream },
                )
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove from queue?") },
            text = {
                Text(
                    "“${toDelete.title}” will be removed. Cached media is reclaimed by the " +
                        "cache's size limit, or immediately with Clear cache in Settings.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteStream(toDelete)
                        pendingDelete = null
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep") }
            },
        )
    }
}

/** Banner at the top of the queue explaining the model's current best guess. */
@Composable
private fun SmartPickNote(prediction: Prediction) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Smart pre-cache: “${prediction.candidate.title}”",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "${prediction.percent}% likely to be your next watch — " +
                    "cached ahead of time while you're on Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StreamCard(
    stream: StreamEntity,
    prediction: Prediction?,
    torrent: TorrentEngine.Progress?,
    onPlay: () -> Unit,
    onCache: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val state = stream.cacheState
    val percent = stream.progressPercent

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stream.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(state)
            }

            Text(
                text = stream.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = "${stream.streamKind.label} · ${stream.progressDetail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (torrent != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!torrent.running) {
                        Text(
                            text = "Not downloading — press Play to resolve and stream it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (!torrent.hasMetadata) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "Finding peers and metadata…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { torrent.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = buildString {
                                append("${torrent.progressPercent}%")
                                if (torrent.fileName != null) append(" · ${torrent.fileName}")
                                append(" · ${torrent.seeds} seeds / ${torrent.peers} peers")
                                if (torrent.downloadRateBps > 0L) {
                                    append(" · ${torrent.downloadRateBps / 1024} KB/s")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                when {
                    state == CacheState.CACHING && percent != null -> LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    state == CacheState.CACHING -> LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (prediction != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${prediction.percent}% likely next · on-device model",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    prediction.reasons.firstOrNull()?.let { reason ->
                        Text(
                            text = reason.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            stream.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play")
                }

                if (state == CacheState.CACHING) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                } else {
                    OutlinedButton(onClick = onCache) {
                        Text(
                            when {
                                stream.streamKind == StreamKind.TORRENT -> "Fetch now"
                                state == CacheState.QUEUED -> "Pre-play"
                                else -> "Retry"
                            },
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

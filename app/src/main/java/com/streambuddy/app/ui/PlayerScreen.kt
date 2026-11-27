package com.streambuddy.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.streambuddy.app.core.Fmt
import com.streambuddy.app.data.AppSettings
import com.streambuddy.app.optimizer.OptimizerUiState
import com.streambuddy.app.playback.PlaybackStatsUi
import com.streambuddy.app.playback.PlayerUiState
import com.streambuddy.app.torrent.TorrentUiState

/**
 * Playback screen showing the Media3 player surface, seek bar, transport controls,
 * speed/audio/subtitle track selectors, live playback/optimizer diagnostics,
 * autoplay countdown, and multi-file torrent controls.
 */
@Composable
fun PlayerScreen(
    player: ExoPlayer,
    state: PlayerUiState,
    stats: PlaybackStatsUi,
    optimizer: OptimizerUiState,
    torrent: TorrentUiState,
    settings: AppSettings,
    vpnActive: Boolean,
    vpnSummary: String,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSelectTorrentFile: (Int) -> Unit,
    onCancelAutoplay: () -> Unit,
    onPlayNextNow: () -> Unit,
    onRetry: () -> Unit,
    onTorrentPause: () -> Unit,
    onTorrentResume: () -> Unit,
    onStopPlayback: () -> Unit,
    onSeedingChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var showStats by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    val nextTorrentFile = remember(state.isTorrent, torrent.files, torrent.selectedFileIndex) {
        if (!state.isTorrent || torrent.files.size <= 1) null
        else {
            val pos = torrent.files.indexOfFirst { it.index == torrent.selectedFileIndex }
            if (pos >= 0 && pos + 1 < torrent.files.size) torrent.files[pos + 1] else null
        }
    }
    val hasNext = state.nextEpisodeId != null || nextTorrentFile != null

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                text = state.title.ifBlank { "Player" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showStats = !showStats }) {
                Text(if (showStats) "Hide stats" else "Stats")
            }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }

        AndroidView(
            factory = { context -> PlayerView(context).apply { this.player = player } },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 310.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.durationMs > 0) {
                val liveFraction = (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
                val shownFraction = if (scrubbing) scrubFraction else liveFraction
                val shownMs = if (scrubbing) (scrubFraction * state.durationMs).toLong() else state.positionMs
                Slider(
                    value = shownFraction,
                    onValueChange = {
                        scrubbing = true
                        scrubFraction = it
                    },
                    onValueChangeFinished = {
                        onSeekTo((scrubFraction * state.durationMs).toLong())
                        scrubbing = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        Fmt.duration(shownMs / 1000.0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        Fmt.duration(state.durationMs / 1000.0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = when {
                    !vpnActive && settings.requireVpnForNetwork -> vpnSummary
                    state.buffering -> "Buffering…"
                    state.error != null -> state.error
                    else -> listOfNotNull(
                        state.sourceLabel.takeIf { it.isNotBlank() },
                        stats.selectedTracks.takeIf { it.isNotBlank() } ?: optimizer.lastReason,
                    ).joinToString(" · ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.autoplayCountdownSeconds?.let { seconds ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Up next in ${seconds}s",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = nextTorrentFile?.name ?: state.nextEpisodeTitle.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = onCancelAutoplay) { Text("Cancel") }
                        Button(onClick = onPlayNextNow) { Text("Play now") }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { onSeekBy(-10_000L) }) { Text("−10s") }
                Button(onClick = if (state.error != null) onRetry else onTogglePlay) {
                    Text(if (state.error != null) "Retry" else if (state.playing) "Pause" else "Play")
                }
                OutlinedButton(onClick = { onSeekBy(10_000L) }) { Text("+10s") }
                if (hasNext && state.autoplayCountdownSeconds == null) {
                    OutlinedButton(onClick = onPlayNextNow) { Text("Next") }
                }
                TextButton(onClick = onStopPlayback) { Text("Stop") }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Speed:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SPEED_OPTIONS.forEach { speed ->
                    FilterChip(
                        selected = kotlin.math.abs(state.playbackSpeed - speed) < 0.01f,
                        onClick = { onSetSpeed(speed) },
                        label = {
                            Text(if (speed == 1.0f) "1x" else "${speed}x")
                        },
                    )
                }
            }

            if (state.audioTracks.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Audio:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.audioTracks.forEach { track ->
                        FilterChip(
                            selected = track.index == state.selectedAudioTrack,
                            onClick = { onSelectAudio(track.index) },
                            label = { Text(track.label) },
                        )
                    }
                }
            }

            if (state.subtitles.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Subtitles:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilterChip(
                        selected = state.selectedSubtitle < 0,
                        onClick = { onSelectSubtitle(-1) },
                        label = { Text("Off") },
                    )
                    state.subtitles.forEach { subtitle ->
                        FilterChip(
                            selected = subtitle.index == state.selectedSubtitle,
                            onClick = { onSelectSubtitle(subtitle.index) },
                            label = { Text(subtitle.label) },
                        )
                    }
                }
            }

            if (showStats) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LabeledValueRow(
                            "Video",
                            if (state.videoWidth > 0 && state.videoHeight > 0) {
                                "${state.videoWidth}×${state.videoHeight}"
                            } else {
                                "—"
                            },
                        )
                        LabeledValueRow("Throughput", Fmt.rate(stats.throughputBps))
                        LabeledValueRow("Video bitrate", Fmt.rate(stats.bitrateBps.toDouble()))
                        LabeledValueRow("Buffer ahead", "${Fmt.oneDecimal(stats.bufferSeconds)} s")
                        LabeledValueRow(
                            "Stalls / dropped",
                            "${stats.stallCount} (${stats.stallMs} ms) · ${stats.droppedFrames} frames",
                        )
                        LabeledValueRow(
                            "Playback cache",
                            "${Fmt.bytes(stats.cacheUsedBytes)} / ${Fmt.bytes(stats.cacheMaxBytes)}",
                        )
                        LabeledValueRow(
                            "Stall risk (${optimizer.mode.name})",
                            "${(optimizer.lastRisk * 100).toInt()}% (${optimizer.lastLevel.name})",
                        )
                    }
                }
            }

            if (state.isTorrent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "${torrent.selectedFileName.ifBlank { torrent.title }} · " +
                                "${(torrent.progress * 100).toInt()}% · ${torrent.peers} peers (${torrent.seeds} seeds)",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "↓ ${Fmt.bytes(torrent.downloadRateBps)}/s · ↑ ${Fmt.bytes(torrent.uploadRateBps)}/s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = if (torrent.paused) onTorrentResume else onTorrentPause) {
                        Text(if (torrent.paused) "Resume" else "Pause torrent")
                    }
                }
                torrent.trackerWarning?.let { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (torrent.files.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Files:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        torrent.files.forEach { file ->
                            FilterChip(
                                selected = file.selected,
                                onClick = { if (!file.selected) onSelectTorrentFile(file.index) },
                                label = {
                                    Text(
                                        "${file.name} (${Fmt.bytes(file.sizeBytes)})",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
                ToggleRow(
                    title = "Seed after playback",
                    detail = "Continue sharing this torrent while it remains active.",
                    checked = torrent.seeding,
                    onCheckedChange = onSeedingChange,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private val SPEED_OPTIONS = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)

package com.streambuddy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.streambuddy.app.data.AppSettings
import com.streambuddy.app.optimizer.OptimizerUiState
import com.streambuddy.app.playback.PlaybackStatsUi
import com.streambuddy.app.playback.PlayerUiState
import com.streambuddy.app.torrent.TorrentUiState

/**
 * Playback screen showing the Media3 player surface, transport controls,
 * subtitle track selector, and torrent swarm controls when active.
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
    onSeekBy: (Long) -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onRetry: () -> Unit,
    onTorrentPause: () -> Unit,
    onTorrentResume: () -> Unit,
    onStopPlayback: () -> Unit,
    onSeedingChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                text = state.title.ifBlank { "Player" },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
        AndroidView(
            factory = { context -> PlayerView(context).apply { this.player = player } },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Text(
            text = when {
                !vpnActive && settings.requireVpnForNetwork -> vpnSummary
                state.buffering -> "Buffering…"
                state.error != null -> state.error
                else -> "${state.sourceLabel} · ${stats.selectedTracks.ifBlank { optimizer.lastReason }}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { onSeekBy(-10_000L) }) { Text("−10s") }
            Button(onClick = if (state.error != null) onRetry else onTogglePlay) {
                Text(if (state.error != null) "Retry" else if (state.playing) "Pause" else "Play")
            }
            OutlinedButton(onClick = { onSeekBy(10_000L) }) { Text("+10s") }
            TextButton(onClick = onStopPlayback) { Text("Stop") }
        }
        if (state.subtitles.isNotEmpty()) {
            TextButton(onClick = { onSelectSubtitle(-1) }) {
                Text(if (state.selectedSubtitle < 0) "✓ Subtitles off" else "Subtitles off")
            }
            state.subtitles.forEach { subtitle ->
                TextButton(onClick = { onSelectSubtitle(subtitle.index) }) {
                    Text(if (subtitle.index == state.selectedSubtitle) "✓ ${subtitle.label}" else subtitle.label)
                }
            }
        }
        if (state.isTorrent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${torrent.title} · ${(torrent.progress * 100).toInt()}% · " +
                        "${torrent.peers} peers",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = if (torrent.paused) onTorrentResume else onTorrentPause) {
                    Text(if (torrent.paused) "Resume" else "Pause torrent")
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

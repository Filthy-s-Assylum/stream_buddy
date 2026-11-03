package com.streambuddy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.streambuddy.app.core.Fmt
import com.streambuddy.app.data.AppSettings
import com.streambuddy.app.data.PolicySource
import com.streambuddy.app.data.RiskLevel
import com.streambuddy.app.optimizer.Confusion
import com.streambuddy.app.optimizer.OptimizerUiState
import com.streambuddy.app.playback.PlaybackStatsUi
import com.streambuddy.app.playback.PlayerUiState
import com.streambuddy.app.torrent.TorrentPhase
import com.streambuddy.app.torrent.TorrentUiState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
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
    var subtitleMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.title.ifBlank { "Player" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append(state.kind.label)
                                if (state.sourceLabel.isNotBlank()) append(" · ").append(state.sourceLabel)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onStopPlayback) { Text("Stop") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PlayerView(context).apply {
                            this.player = player
                            useController = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            setShowNextButton(false)
                            setShowPreviousButton(false)
                        }
                    },
                    update = { view -> view.player = player },
                )
            }

            if (state.preparing) {
                Text(
                    "Resolving the source…",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (!vpnActive && settings.requireVpnForNetwork) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "VPN not detected",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            vpnSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            state.error?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "Playback stopped",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRetry) { Text("Try again") }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { onSeekBy(-10_000) }, modifier = Modifier.weight(1f)) {
                    Text("−10 s")
                }
                Button(onClick = onTogglePlay, modifier = Modifier.weight(1f)) {
                    Text(if (state.playing) "Pause" else "Play")
                }
                OutlinedButton(onClick = { onSeekBy(30_000) }, modifier = Modifier.weight(1f)) {
                    Text("+30 s")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${Fmt.duration(state.positionMs / 1000.0)} / ${Fmt.duration(state.durationMs / 1000.0)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    TextButton(onClick = { subtitleMenu = true }) {
                        val selected = state.subtitles.getOrNull(state.selectedSubtitle)
                        Text(selected?.label ?: "Subtitles off")
                    }
                    DropdownMenu(expanded = subtitleMenu, onDismissRequest = { subtitleMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Off") },
                            onClick = {
                                subtitleMenu = false
                                onSelectSubtitle(-1)
                            },
                        )
                        state.subtitles.forEachIndexed { index, subtitle ->
                            DropdownMenuItem(
                                text = { Text(subtitle.label) },
                                onClick = {
                                    subtitleMenu = false
                                    onSelectSubtitle(index)
                                },
                            )
                        }
                    }
                }
            }

            SectionTitle("Delivery")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile("Buffer", "${Fmt.oneDecimal(stats.bufferSeconds)} s", Modifier.weight(1f))
                StatTile("Throughput", Fmt.rate(stats.throughputBps), Modifier.weight(1f))
                StatTile(
                    "Bitrate",
                    if (stats.bitrateBps > 0) Fmt.rate(stats.bitrateBps.toDouble()) else "—",
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile("Peers", if (torrent.active) "${stats.peers}" else "—", Modifier.weight(1f))
                StatTile(
                    "Stalls",
                    "${stats.stallCount}",
                    Modifier.weight(1f),
                    hint = if (stats.stallMs > 0) Fmt.duration(stats.stallMs / 1000.0) else null,
                )
                StatTile(
                    "Cache",
                    Fmt.bytes(stats.cacheUsedBytes),
                    Modifier.weight(1f),
                    hint = Fmt.bytes(stats.cacheMaxBytes),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (stats.selectedTracks.isBlank()) {
                    "Track details appear once playback starts."
                } else {
                    stats.selectedTracks
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Optimizer", trailing = {
                TextButton(onClick = onOpenSettings) { Text("Settings") }
            })
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = optimizer.mode.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = when (optimizer.lastSource) {
                                PolicySource.MODEL -> "on-device model"
                                PolicySource.MODEL_FALLBACK -> "fallback to rules"
                                PolicySource.RULES -> "rules only"
                                null -> "waiting"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = optimizer.lastReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(
                            "Risk",
                            Fmt.percent(optimizer.lastRisk),
                            Modifier.weight(1f),
                            hint = optimizer.lastLevel.name,
                        )
                        StatTile(
                            "Model samples",
                            "${optimizer.trainedSamples}",
                            Modifier.weight(1f),
                            hint = if (optimizer.eligible) "eligible" else "collecting",
                        )
                        StatTile(
                            "Model version",
                            if (optimizer.modelVersion > 0) "v${optimizer.modelVersion}" else "—",
                            Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Baseline vs model",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    MetricsRow("Rules baseline", optimizer.baselineMetrics)
                    MetricsRow("On-device model", optimizer.modelMetrics)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The model only adjusts buffer targets, prefetch windows and quality caps inside Stream Buddy. " +
                            "It cannot add bandwidth, peers or resolution, and it is never used for fixed-quality torrents.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (torrent.active || torrent.phase == TorrentPhase.ERROR) {
                SectionTitle("Torrent")
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = torrent.title.ifBlank { torrent.selectedFileName.ifBlank { "Torrent" } },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${torrent.phase.name.lowercase().replace('_', ' ')} · " +
                                "${Fmt.percent(torrent.progress)} · ${Fmt.rate(torrent.downloadRateBps.toDouble())}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ProgressLine(torrent.progress, Modifier.padding(vertical = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("Peers", "${torrent.peers}", Modifier.weight(1f))
                            StatTile("Seeds", "${torrent.seeds}", Modifier.weight(1f))
                            StatTile("Upload", Fmt.rate(torrent.uploadRateBps.toDouble()), Modifier.weight(1f))
                            StatTile(
                                "Files",
                                "${torrent.totalFiles}",
                                Modifier.weight(1f),
                                hint = Fmt.bytes(torrent.selectedFileSize),
                            )
                        }
                        torrent.trackerWarning?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Tracker warning: $it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        torrent.error?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (torrent.paused) {
                                Button(onClick = onTorrentResume) { Text("Resume torrent") }
                            } else {
                                OutlinedButton(onClick = onTorrentPause) { Text("Pause torrent") }
                            }
                            TextButton(onClick = onStopPlayback) { Text("Remove data") }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Seeding", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Off by default. Seeding uploads pieces to other peers and can reveal your IP on the swarm. " +
                                        "You are responsible for what you share.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = settings.seedingEnabled, onCheckedChange = onSeedingChange)
                        }
                    }
                }
            }

            var riskBar by remember(optimizer.lastRisk) { mutableStateOf(optimizer.lastRisk) }
            riskBar = optimizer.lastRisk
            if (optimizer.recent.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Recent risk", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            optimizer.recent.takeLast(60).forEach { point ->
                                val height = (point.riskScore.coerceIn(0.0, 1.0) * 34).roundToInt().coerceAtLeast(2)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(height.dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(
                                            when {
                                                point.riskScore >= 0.66 -> MaterialTheme.colorScheme.error
                                                point.riskScore >= 0.33 -> Color(0xFFFFC857)
                                                else -> MaterialTheme.colorScheme.tertiary
                                            },
                                        ),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Higher bars mean a larger chance of a stall in the next seconds, from measured buffer and throughput.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MetricsRow(label: String, confusion: Confusion) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (confusion.total == 0) {
                "no labelled windows yet"
            } else {
                "acc ${Fmt.percent(confusion.accuracy)} · recall ${Fmt.percent(confusion.recall)} · n=${confusion.total}"
            },
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Suppress("unused")
private fun riskLevelLabel(level: RiskLevel): String = when (level) {
    RiskLevel.LOW -> "Low"
    RiskLevel.MEDIUM -> "Medium"
    RiskLevel.HIGH -> "High"
}

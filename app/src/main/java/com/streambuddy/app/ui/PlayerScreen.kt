package com.streambuddy.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.streambuddy.app.AppGraph
import com.streambuddy.app.BuildConfig
import com.streambuddy.app.ai.PrefetchPredictor
import com.streambuddy.app.cache.CacheManager
import com.streambuddy.app.data.AppSettings
import com.streambuddy.app.optimizer.OptimizerUiState
import com.streambuddy.app.playback.PlaybackStatsUi
import com.streambuddy.app.playback.PlayerUiState
import com.streambuddy.app.torrent.TorrentUiState
import com.streambuddy.app.util.Format

/**
 * Playback screen showing the current stream player UI.
 *
 * Settings values are read from [MainViewModel.settings], which is the shared
 * encrypted [AppSettings] blob; writes go straight back to that same store, so
 * every other reader (the VPN gate, the torrent engine, the player) sees them
 * immediately.
 */
@Composable
fun PlayerScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cacheBytes by viewModel.cacheBytes.collectAsStateWithLifecycle()
    val model by viewModel.modelState.collectAsStateWithLifecycle()
    val predictions by viewModel.predictions.collectAsStateWithLifecycle()
    val vpn by AppGraph.require().vpnMonitor.status.collectAsStateWithLifecycle()

    var confirmClear by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }
    var notificationsEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    LaunchedEffect(Unit) {
        viewModel.refreshCacheUsage()
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = "Settings & boost",
                subtitle = "Pre-caching is cache-first: everything you play also warms the cache.",
            )
        }

        item {
            SettingsSection(title = "Smart pre-cache") {
                Text(
                    text = "An on-device model learns from what you actually watch — series, " +
                        "time of day, how often you finish — and pre-caches the next likely " +
                        "play. It starts from hand-tuned priors and improves as you use the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LabeledValueRow(
                    label = "Status",
                    value = when {
                        !settings.smartPrefetch -> "Off"
                        model.model.hasLearnedAnything -> "Personalised"
                        else -> "Learning"
                    },
                    valueColor = if (settings.smartPrefetch) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                LabeledValueRow(
                    label = "Playbacks recorded",
                    value = model.historyCount.toString(),
                )
                predictions.values.maxByOrNull { it.probability }?.let { top ->
                    Text(
                        text = "Next up: “${top.candidate.title}” (${top.percent}%) — " +
                            (top.reasons.firstOrNull()?.detail ?: "no strong signal yet"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (settings.smartPrefetch) {
                        FilledTonalButton(onClick = { viewModel.setSmartPrefetch(false) }) {
                            Text("Turn off")
                        }
                    } else {
                        OutlinedButton(onClick = { viewModel.setSmartPrefetch(true) }) {
                            Text("Turn on")
                        }
                    }
                    TextButton(onClick = viewModel::enhanceQueueNow) { Text("Enhance now") }
                }
                if (model.historyCount > 0 || model.model.hasLearnedAnything) {
                    TextButton(onClick = { confirmReset = true }) { Text("Reset learning") }
                }
                Text(
                    text = "Needs ${PrefetchPredictor.MIN_EVENTS_FOR_TRAINING} playbacks before it " +
                        "starts adjusting the priors. History stays on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsSection(title = "Pre-play buffer") {
                Text(
                    text = "How much of each stream to cache before you press play " +
                        "(currently ${settings.prefetchMinutes} min).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppSettings.PRESET_MINUTES.chunked(2).forEach { rowMinutes ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowMinutes.forEach { minutes ->
                            val selected = settings.prefetchMinutes == minutes
                            if (selected) {
                                FilledTonalButton(
                                    onClick = { viewModel.setPrefetchMinutes(minutes) },
                                    modifier = Modifier.weight(1f),
                                ) { Text("$minutes min") }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.setPrefetchMinutes(minutes) },
                                    modifier = Modifier.weight(1f),
                                ) { Text("$minutes min") }
                            }
                        }
                    }
                }
            }
        }

        item {
            SettingsSection(title = "Cache quality") {
                Text(
                    text = "Which HLS variant to pre-cache. Playback still adapts to your network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val lowSelected = !settings.preferHighestQuality
                    if (lowSelected) {
                        FilledTonalButton(
                            onClick = { viewModel.setPreferHighestQuality(false) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Data saver") }
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.setPreferHighestQuality(false) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Data saver") }
                    }

                    if (!lowSelected) {
                        FilledTonalButton(
                            onClick = { viewModel.setPreferHighestQuality(true) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Best quality") }
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.setPreferHighestQuality(true) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Best quality") }
                    }
                }
            }
        }

        item {
            SettingsSection(title = "Storage") {
                LabeledValueRow("Cached media", Format.bytes(cacheBytes))
                val usedPercent = Format.percent(cacheBytes, CacheManager.MAX_BYTES)
                if (usedPercent != null) {
                    LinearProgressIndicator(
                        progress = { usedPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = "Limit ${Format.bytes(CacheManager.MAX_BYTES)} — the least recently " +
                        "used segments are evicted first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        enabled = cacheBytes > 0L,
                    ) { Text("Clear cache") }
                    TextButton(onClick = viewModel::refreshCacheUsage) { Text("Refresh") }
                }
            }
        }

        item {
            SettingsSection(title = "Metadata") {
                Text(
                    text = "Stream Buddy does not ship a TMDB key. Use your own free TMDB API key " +
                        "to load posters and descriptions. The key is encrypted with the Android " +
                        "Keystore and never leaves this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = settings.tmdbApiKey,
                    onValueChange = {
                        AppGraph.require().settingsStore.update { s -> s.copy(tmdbApiKey = it.take(200)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("TMDB API key") },
                    singleLine = true,
                    visualTransformation = if (keyVisible) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { keyVisible = !keyVisible }) {
                            Text(if (keyVisible) "Hide" else "Show")
                        }
                    },
                )
                OutlinedTextField(
                    value = settings.preferredSubtitleLanguage,
                    onValueChange = {
                        AppGraph.require().settingsStore.update { s ->
                            s.copy(preferredSubtitleLanguage = it.take(8))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Preferred subtitle language (e.g. en)") },
                    singleLine = true,
                )
            }
        }

        item {
            SettingsSection(title = "Where to watch") {
                Text(
                    text = "Movie of the Night shows official streaming services, prices and deep " +
                        "links. Use your own API key; it is encrypted with the Android Keystore and " +
                        "only sent to api.movieofthenight.com. Release builds ship no key, so the " +
                        "field starts empty.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = settings.motnApiKey,
                    onValueChange = {
                        AppGraph.require().settingsStore.update { s -> s.copy(motnApiKey = it.take(200)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Movie of the Night API key") },
                    singleLine = true,
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { keyVisible = !keyVisible }) {
                            Text(if (keyVisible) "Hide" else "Show")
                        }
                    },
                )
                OutlinedTextField(
                    value = settings.motnCountry,
                    onValueChange = {
                        AppGraph.require().settingsStore.update { s -> s.copy(motnCountry = it.take(2)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Where-to-watch country (ISO 3166-1, e.g. us, gb)") },
                    singleLine = true,
                )
            }
        }

        item {
            SettingsSection(title = "VPN") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (vpn.active) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            if (vpn.active) "Protected route" else "VPN not detected",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (vpn.active) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            vpn.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (vpn.active) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { AppGraph.require().vpnMonitor.refresh() }) {
                                Text("Check again")
                            }
                            TextButton(onClick = {
                                val intent = Intent(Settings.ACTION_VPN_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                val opened = runCatching { context.startActivity(intent) }.isSuccess
                                if (!opened) {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_SETTINGS)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                }
                            }) { Text("Open VPN settings") }
                        }
                    }
                }
                ToggleRow(
                    title = "Require VPN for all network access",
                    detail = "On by default. Torrents, playback, metadata lookups, posters, the " +
                        "built-in browser, page scans and downloads all refuse to start, and active " +
                        "playback or downloads pause, when this app is not routed through your VPN. " +
                        "Local files and completed offline downloads are always allowed. Stream " +
                        "Buddy does not provide a VPN; use a trusted system VPN and make sure " +
                        "Stream Buddy is not excluded from it.",
                    checked = settings.requireVpnForNetwork,
                    onCheckedChange = { enabled ->
                        AppGraph.require().settingsStore.update { it.copy(requireVpnForNetwork = enabled) }
                    },
                )
            }
        }

        item {
            SettingsSection(title = "Privacy and security") {
                ToggleRow(
                    title = "Block screenshots and screen recording",
                    detail = "Sets FLAG_SECURE on the app window. Recommended when your library " +
                        "or sources are private.",
                    checked = settings.blockScreenshots,
                    onCheckedChange = { enabled ->
                        AppGraph.require().settingsStore.update { it.copy(blockScreenshots = enabled) }
                    },
                )
                Text(
                    text = "Library titles, attached sources, the TMDB key and playback history " +
                        "are encrypted with AES-256-GCM keys held by the Android Keystore. Android " +
                        "backup and device-to-device transfer are disabled for app data. Torrent " +
                        "piece traffic uses forced RC4 peer encryption and the local streaming " +
                        "server only listens on 127.0.0.1 with a random token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Peer encryption protects Stream Buddy↔peer connections. It cannot " +
                        "hide your IP from the swarm or encrypt metadata you publish to " +
                        "trackers/DHT, which is why the VPN requirement is on by default. Stream " +
                        "Buddy does not bypass DRM, paywalls or region locks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsSection(title = "Notifications") {
                LabeledValueRow(
                    label = "Pre-cache progress",
                    value = if (notificationsEnabled) "Enabled" else "Blocked",
                    valueColor = if (notificationsEnabled) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(
                    text = "Caching runs in the background; a low-priority notification shows " +
                        "its progress.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!notificationsEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            OutlinedButton(
                                onClick = {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        notificationsEnabled = true
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                            ) { Text("Allow") }
                        }
                        TextButton(onClick = { openNotificationSettings(context) }) {
                            Text("System settings")
                        }
                    }
                }
            }
        }

        item {
            SettingsSection(title = "About") {
                LabeledValueRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Text(
                    text = "DRM-protected services (Netflix, Disney+, Prime Video, …) can only " +
                        "play inside their own apps. Streams protected by FairPlay, Widevine or " +
                        "PlayReady are detected and never cached.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "DASH (.mpd) streams play cache-first but are not pre-cached; HLS " +
                        "(.m3u8) and direct media files are.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset what was learned?") },
            text = {
                Text(
                    "Deletes the watch history and the learned weights. Pre-caching falls back " +
                        "to the built-in defaults until it has enough playbacks again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetLearning()
                        confirmReset = false
                    },
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Keep") }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear cached media?") },
            text = {
                Text(
                    "Removes ${Format.bytes(cacheBytes)} of cached segments. Playback will " +
                        "start from the network again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearCache()
                        confirmClear = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

/** Label on the left, switch on the right, with an explanatory line underneath. */
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
        modifier = Modifier.fillMaxSize(),
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
        state.subtitles.forEach { subtitle ->
            TextButton(onClick = { onSelectSubtitle(subtitle.index) }) {
                Text(if (subtitle.index == state.selectedSubtitle) "✓ ${subtitle.label}" else subtitle.label)
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

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}
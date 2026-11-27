package com.streambuddy.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.streambuddy.app.core.Fmt
import com.streambuddy.app.data.AppSettings
import com.streambuddy.app.data.ModelStatus
import com.streambuddy.app.data.OptimizerMode
import com.streambuddy.app.optimizer.OptimizerUiState
import com.streambuddy.app.security.SecurityUiState

/**
 * Settings screen backed by the encrypted [AppSettings] store.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    optimizer: OptimizerUiState,
    predictorStatus: ModelStatus,
    security: SecurityUiState,
    cacheUsedBytes: Long,
    torrentStorageBytes: Long,
    playerActive: Boolean,
    vpnActive: Boolean,
    vpnSummary: String,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onClearCache: () -> Unit,
    onResetModel: () -> Unit,
    onRunSmartPrefetchNow: () -> Unit,
    onRefreshVpn: () -> Unit,
    onScanNow: () -> Unit,
    onClearQuarantine: () -> Unit,
) {
    var showTmdbKey by remember { mutableStateOf(false) }
    var showMotnKey by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("Back") }
                SectionHeader(title = "Settings")
            }
        }
        item {
            SettingsSection(title = "Smart pre-cache & optimizer") {
                LabeledValueRow("Optimizer", optimizer.mode.name)
                LabeledValueRow("Stall risk samples", optimizer.trainedSamples.toString())
                LabeledValueRow(
                    "Predictor history",
                    "${predictorStatus.historyCount} plays (${if (predictorStatus.model.hasLearnedAnything) "trained" else "priors"})",
                )
                Text("Optimizer policy", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OptimizerMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.optimizerMode == mode,
                            onClick = { onChange(settings.copy(optimizerMode = mode)) },
                            label = { Text(if (mode == OptimizerMode.RULES) "Rules" else "Adaptive") },
                        )
                    }
                }
                ToggleRow(
                    title = "Smart pre-cache",
                    detail = "Learn from playback history to prioritize likely next streams.",
                    checked = settings.smartPrefetch,
                    onCheckedChange = { onChange(settings.copy(smartPrefetch = it)) },
                )
                Text(
                    text = "Pre-cache window: ${settings.prefetchMinutes} minutes",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppSettings.PRESET_MINUTES.forEach { minutes ->
                        FilterChip(
                            selected = settings.prefetchMinutes == minutes,
                            onClick = { onChange(settings.copy(prefetchMinutes = minutes)) },
                            label = { Text("$minutes min") },
                        )
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onRunSmartPrefetchNow,
                        enabled = settings.smartPrefetch && (!settings.requireVpnForNetwork || vpnActive),
                    ) { Text("Run smart pre-cache") }
                    OutlinedButton(onClick = onResetModel) { Text("Reset learning") }
                }
            }
        }
        item {
            SettingsSection(title = "Playback & buffering") {
                ToggleRow(
                    title = "Prefer highest quality",
                    detail = "Pick higher-resolution torrent files and HLS variants when available.",
                    checked = settings.preferHighestQuality,
                    onCheckedChange = { onChange(settings.copy(preferHighestQuality = it)) },
                )
                ToggleRow(
                    title = "Autoplay next episode",
                    detail = "Start a 5-second countdown to the next episode or torrent file at playback end.",
                    checked = settings.autoplayNextEpisode,
                    onCheckedChange = { onChange(settings.copy(autoplayNextEpisode = it)) },
                )
                Text(
                    text = "Start buffer: ${settings.startBufferSeconds}s",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PresetChipRow(
                    options = listOf(2 to "2s", 4 to "4s", 8 to "8s", 15 to "15s"),
                    selected = settings.startBufferSeconds,
                    onSelect = { onChange(settings.copy(startBufferSeconds = it)) },
                )
                Text(
                    text = "Target buffer: ${settings.bufferTargetSeconds}s",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PresetChipRow(
                    options = listOf(20 to "20s", 45 to "45s", 90 to "90s", 120 to "120s"),
                    selected = settings.bufferTargetSeconds,
                    onSelect = { onChange(settings.copy(bufferTargetSeconds = it)) },
                )
                Text(
                    text = "Max adaptive video bitrate: " +
                        if (settings.maxVideoBitrateKbps <= 0) "Auto" else "${settings.maxVideoBitrateKbps / 1000.0} Mbps",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PresetChipRow(
                    options = listOf(0 to "Auto", 2000 to "2 Mbps", 5000 to "5 Mbps", 10000 to "10 Mbps"),
                    selected = settings.maxVideoBitrateKbps,
                    onSelect = { onChange(settings.copy(maxVideoBitrateKbps = it)) },
                )
            }
        }
        item {
            SettingsSection(title = "Storage & offline downloads") {
                LabeledValueRow("Cached media", "${Fmt.bytes(cacheUsedBytes)} / ${settings.playbackCacheMb} MB")
                PresetChipRow(
                    options = listOf(512 to "512 MB", 1024 to "1 GB", 2048 to "2 GB", 4096 to "4 GB"),
                    selected = settings.playbackCacheMb,
                    onSelect = { onChange(settings.copy(playbackCacheMb = it)) },
                )
                OutlinedButton(
                    onClick = onClearCache,
                    enabled = !playerActive && cacheUsedBytes > 0,
                ) {
                    Text(if (playerActive) "Stop playback to clear cache" else "Clear cache")
                }
                ToggleRow(
                    title = "Wi-Fi only offline downloads",
                    detail = "Pause Media3 offline downloads when not on an unmetered network.",
                    checked = settings.wifiOnlyDownloads,
                    onCheckedChange = { onChange(settings.copy(wifiOnlyDownloads = it)) },
                )
            }
        }
        item {
            SettingsSection(title = "Torrents & seeding") {
                LabeledValueRow("Torrent scratch", "${Fmt.bytes(torrentStorageBytes)} / ${settings.torrentStorageLimitMb} MB")
                ToggleRow(
                    title = "Seed after playback",
                    detail = "Continue sharing completed pieces while the torrent stays open.",
                    checked = settings.seedingEnabled,
                    onCheckedChange = { onChange(settings.copy(seedingEnabled = it)) },
                )
                Text(
                    text = "Download rate limit: " +
                        if (settings.torrentDownloadLimitKbps <= 0) "Unlimited" else "${settings.torrentDownloadLimitKbps} KB/s",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PresetChipRow(
                    options = listOf(0 to "Unlimited", 2048 to "2 MB/s", 5120 to "5 MB/s", 10240 to "10 MB/s"),
                    selected = settings.torrentDownloadLimitKbps,
                    onSelect = { onChange(settings.copy(torrentDownloadLimitKbps = it)) },
                )
                Text(
                    text = "Upload rate limit: ${settings.torrentUploadLimitKbps} KB/s",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PresetChipRow(
                    options = listOf(64 to "64 KB/s", 256 to "256 KB/s", 1024 to "1 MB/s", 5120 to "5 MB/s"),
                    selected = settings.torrentUploadLimitKbps,
                    onSelect = { onChange(settings.copy(torrentUploadLimitKbps = it)) },
                )
                Text(
                    text = "Torrent storage cap: ${settings.torrentStorageLimitMb} MB",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PresetChipRow(
                    options = listOf(1024 to "1 GB", 4096 to "4 GB", 8192 to "8 GB", 16384 to "16 GB"),
                    selected = settings.torrentStorageLimitMb,
                    onSelect = { onChange(settings.copy(torrentStorageLimitMb = it)) },
                )
                Text(
                    text = "Metadata timeout: ${settings.metadataTimeoutSeconds}s · Piece prefetch: ${settings.prefetchSeconds}s",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PresetChipRow(
                    options = listOf(20 to "20s meta", 45 to "45s meta", 90 to "90s meta", 120 to "120s meta"),
                    selected = settings.metadataTimeoutSeconds,
                    onSelect = { onChange(settings.copy(metadataTimeoutSeconds = it)) },
                )
                PresetChipRow(
                    options = listOf(30 to "30s ahead", 90 to "90s ahead", 180 to "180s ahead", 300 to "300s ahead"),
                    selected = settings.prefetchSeconds,
                    onSelect = { onChange(settings.copy(prefetchSeconds = it)) },
                )
            }
        }
        item {
            SettingsSection(title = "Privacy and network") {
                LabeledValueRow(
                    "VPN status",
                    if (vpnActive) "Protected route" else "VPN not detected",
                )
                Text(vpnSummary, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onRefreshVpn) { Text("Refresh VPN status") }
                ToggleRow(
                    title = "Require VPN for network access",
                    detail = "Pause network activity while a VPN is not active.",
                    checked = settings.requireVpnForNetwork,
                    onCheckedChange = { onChange(settings.copy(requireVpnForNetwork = it)) },
                )
                ToggleRow(
                    title = "Enable malware scanning",
                    detail = "Scan imported files before opening them.",
                    checked = settings.malwareScanEnabled,
                    onCheckedChange = { onChange(settings.copy(malwareScanEnabled = it)) },
                )
                LabeledValueRow(
                    "Malware scanner",
                    when {
                        !settings.malwareScanEnabled -> "Disabled"
                        security.scanning -> "Scanning…"
                        security.quarantinedCount > 0 ->
                            "${security.quarantinedCount} quarantined (${Fmt.bytes(security.quarantineBytes)})"
                        security.filesScanned > 0 -> "Clean (${security.filesScanned} scanned)"
                        else -> "Ready"
                    },
                )
                security.lastError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onScanNow,
                        enabled = settings.malwareScanEnabled && !security.scanning,
                    ) { Text("Scan storage") }
                    if (security.quarantinedCount > 0) {
                        OutlinedButton(onClick = onClearQuarantine) {
                            Text("Delete quarantine")
                        }
                    }
                }
                ToggleRow(
                    title = "Block screenshots and recording",
                    detail = "Protect app content from screen capture.",
                    checked = settings.blockScreenshots,
                    onCheckedChange = { onChange(settings.copy(blockScreenshots = it)) },
                )
            }
        }
        item {
            SettingsSection(title = "Metadata and subtitles") {
                OutlinedTextField(
                    value = settings.tmdbApiKey,
                    onValueChange = { onChange(settings.copy(tmdbApiKey = it.take(200))) },
                    label = { Text("TMDB API key") },
                    singleLine = true,
                    visualTransformation = if (showTmdbKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showTmdbKey = !showTmdbKey }) {
                            Text(if (showTmdbKey) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.motnApiKey,
                    onValueChange = { onChange(settings.copy(motnApiKey = it.take(200))) },
                    label = { Text("Movie of the Night API key") },
                    singleLine = true,
                    visualTransformation = if (showMotnKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showMotnKey = !showMotnKey }) {
                            Text(if (showMotnKey) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.motnCountry,
                    onValueChange = { onChange(settings.copy(motnCountry = it.take(2))) },
                    label = { Text("Country code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.preferredSubtitleLanguage,
                    onValueChange = {
                        onChange(settings.copy(preferredSubtitleLanguage = it.take(16)))
                    },
                    label = { Text("Preferred subtitle language") },
                    placeholder = { Text("e.g. en, es, fr") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PresetChipRow(
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
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

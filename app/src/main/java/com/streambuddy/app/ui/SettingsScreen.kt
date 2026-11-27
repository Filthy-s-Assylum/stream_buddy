package com.streambuddy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
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
import com.streambuddy.app.optimizer.OptimizerUiState
import com.streambuddy.app.security.SecurityUiState

/**
 * Settings screen backed by the encrypted [AppSettings] store.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    optimizer: OptimizerUiState,
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
            SettingsSection(title = "Smart pre-cache") {
                LabeledValueRow("Optimizer", optimizer.mode.name)
                LabeledValueRow("Trained samples", optimizer.trainedSamples.toString())
                ToggleRow(
                    title = "Smart pre-cache",
                    detail = "Learn from playback history to prioritize likely next streams.",
                    checked = settings.smartPrefetch,
                    onCheckedChange = { onChange(settings.copy(smartPrefetch = it)) },
                )
                ToggleRow(
                    title = "Prefer highest quality",
                    detail = "Choose higher-quality torrent files when available.",
                    checked = settings.preferHighestQuality,
                    onCheckedChange = { onChange(settings.copy(preferHighestQuality = it)) },
                )
                Text(
                    text = "Pre-cache window: ${settings.prefetchMinutes} minutes",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.PRESET_MINUTES.forEach { minutes ->
                        OutlinedButton(onClick = {
                            onChange(settings.copy(prefetchMinutes = minutes))
                        }) {
                            Text("$minutes min")
                        }
                    }
                }
                OutlinedButton(onClick = onResetModel) { Text("Reset learning") }
            }
        }
        item {
            SettingsSection(title = "Storage") {
                LabeledValueRow("Cached media", Fmt.bytes(cacheUsedBytes))
                LabeledValueRow("Torrent storage", Fmt.bytes(torrentStorageBytes))
                OutlinedButton(
                    onClick = onClearCache,
                    enabled = !playerActive && cacheUsedBytes > 0,
                ) {
                    Text(if (playerActive) "Stop playback to clear cache" else "Clear cache")
                }
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
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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

package com.streambuddy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.provider.Settings
import com.streambuddy.app.core.Fmt
import com.streambuddy.app.data.AppSettings
import com.streambuddy.app.data.OptimizerMode
import com.streambuddy.app.optimizer.OptimizerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    optimizer: OptimizerUiState,
    cacheUsedBytes: Long,
    torrentStorageBytes: Long,
    vpnActive: Boolean,
    vpnSummary: String,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    onClearCache: () -> Unit,
    onResetModel: () -> Unit,
    onRefreshVpn: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
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
            SectionTitle("Playback")
            Text(
                "Start buffer is how much video is collected before playback begins. The target is how much " +
                    "Stream Buddy tries to keep ready while you watch. Larger buffers survive short network drops.",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Stepper(
                label = "Start buffer",
                value = settings.startBufferSeconds,
                range = 2..30,
                unit = "s",
                onChange = { onChange(settings.copy(startBufferSeconds = it)) },
            )
            Stepper(
                label = "Buffer target",
                value = settings.bufferTargetSeconds,
                range = 10..180,
                unit = "s",
                step = 5,
                onChange = { onChange(settings.copy(bufferTargetSeconds = it)) },
            )
            Stepper(
                label = "Prefetch window",
                value = settings.prefetchSeconds,
                range = 15..600,
                unit = "s",
                step = 15,
                onChange = { onChange(settings.copy(prefetchSeconds = it)) },
            )
            Stepper(
                label = "Maximum video bitrate (0 = unlimited)",
                value = settings.maxVideoBitrateKbps,
                range = 0..20_000,
                unit = "kbps",
                step = 500,
                onChange = { onChange(settings.copy(maxVideoBitrateKbps = it)) },
            )
            Stepper(
                label = "Playback cache",
                value = settings.playbackCacheMb,
                range = 128..16_384,
                unit = "MB",
                step = 128,
                onChange = { onChange(settings.copy(playbackCacheMb = it)) },
            )
            Text(
                "Cache in use: ${Fmt.bytes(cacheUsedBytes)}",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onClearCache) { Text("Clear playback cache") }
            }

            SectionTitle("Optimizer")
            Text(
                "Rules baseline uses fixed, explainable thresholds. The adaptive option adds an on-device logistic " +
                    "model that learns from stalls on this device. It only changes buffer targets, prefetch windows and " +
                    "quality caps. It cannot add bandwidth or peers, and it never switches resolution for a " +
                    "fixed-quality torrent.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                OptimizerMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.optimizerMode == mode,
                        onClick = { onChange(settings.copy(optimizerMode = mode)) },
                        shape = SegmentedButtonDefaults.itemShape(index, OptimizerMode.entries.size),
                    ) { Text(mode.label) }
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Training state",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Model version ${if (optimizer.modelVersion > 0) optimizer.modelVersion else "—"} · " +
                            "${optimizer.trainedSamples} labelled windows stored on this device",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Baseline accuracy ${if (optimizer.baselineMetrics.total == 0) "—" else Fmt.percent(optimizer.baselineMetrics.accuracy)} · " +
                            "model accuracy ${if (optimizer.modelMetrics.total == 0) "—" else Fmt.percent(optimizer.modelMetrics.accuracy)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { confirmReset = true }) { Text("Reset learned model") }
                }
            }

            SectionTitle("Torrents")
            Stepper(
                label = "Download limit (0 = unlimited)",
                value = settings.torrentDownloadLimitKbps,
                range = 0..100_000,
                unit = "KB/s",
                step = 500,
                onChange = { onChange(settings.copy(torrentDownloadLimitKbps = it)) },
            )
            Stepper(
                label = "Upload limit",
                value = settings.torrentUploadLimitKbps,
                range = 8..10_000,
                unit = "KB/s",
                step = 8,
                onChange = { onChange(settings.copy(torrentUploadLimitKbps = it)) },
            )
            Stepper(
                label = "Storage budget for torrent pieces",
                value = settings.torrentStorageLimitMb,
                range = 256..32_768,
                unit = "MB",
                step = 256,
                onChange = { onChange(settings.copy(torrentStorageLimitMb = it)) },
            )
            Stepper(
                label = "Metadata timeout",
                value = settings.metadataTimeoutSeconds,
                range = 10..180,
                unit = "s",
                step = 5,
                onChange = { onChange(settings.copy(metadataTimeoutSeconds = it)) },
            )
            Text(
                "Scratch data in use: ${Fmt.bytes(torrentStorageBytes)}",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow(
                title = "Seed downloaded pieces",
                detail = "Off by default. Seeding uploads pieces to other peers and can expose your IP address " +
                    "on the swarm. Only enable it for content you are allowed to share.",
                checked = settings.seedingEnabled,
                onCheckedChange = { onChange(settings.copy(seedingEnabled = it)) },
            )

            SectionTitle("Downloads")
            ToggleRow(
                title = "Only download over Wi-Fi",
                detail = "Offline downloads wait for an unmetered connection.",
                checked = settings.wifiOnlyDownloads,
                onCheckedChange = { onChange(settings.copy(wifiOnlyDownloads = it)) },
            )

            SectionTitle("Metadata")
            Text(
                "Stream Buddy does not ship a TMDB key. Use your own free TMDB API key to load posters and " +
                    "descriptions. The key is encrypted with the Android Keystore and never leaves this device.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = settings.tmdbApiKey,
                onValueChange = { onChange(settings.copy(tmdbApiKey = it.take(200))) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                label = { Text("TMDB API key") },
                singleLine = true,
                visualTransformation = if (keyVisible) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { keyVisible = !keyVisible }) {
                        Text(if (keyVisible) "Hide" else "Show")
                    }
                },
            )
            OutlinedTextField(
                value = settings.preferredSubtitleLanguage,
                onValueChange = { onChange(settings.copy(preferredSubtitleLanguage = it.take(8))) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                label = { Text("Preferred subtitle language (e.g. en)") },
                singleLine = true,
            )

            SectionTitle("Where to watch")
            Text(
                "Movie of the Night shows official streaming services, prices and deep links. Use your own API " +
                    "key; it is encrypted with the Android Keystore and only sent to api.movieofthenight.com. " +
                    "Release builds ship no key, so the field starts empty.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = settings.motnApiKey,
                onValueChange = { onChange(settings.copy(motnApiKey = it.take(200))) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                label = { Text("Movie of the Night API key") },
                singleLine = true,
                visualTransformation = if (keyVisible) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { keyVisible = !keyVisible }) {
                        Text(if (keyVisible) "Hide" else "Show")
                    }
                },
            )
            OutlinedTextField(
                value = settings.motnCountry,
                onValueChange = { onChange(settings.copy(motnCountry = it.take(2))) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                label = { Text("Where-to-watch country (ISO 3166-1, e.g. us, gb)") },
                singleLine = true,
            )

            SectionTitle("VPN")
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (vpnActive) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        if (vpnActive) "Protected route" else "VPN not detected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (vpnActive) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        vpnSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (vpnActive) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefreshVpn) { Text("Check again") }
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
                detail = "On by default. Torrents, playback, metadata lookups, posters, the built-in browser, page " +
                    "scans and downloads all refuse to start, and active playback or downloads pause, when this app " +
                    "is not routed through your VPN. Local files and completed offline downloads are always allowed. " +
                    "Stream Buddy does not provide a VPN; use a trusted system VPN and make sure Stream Buddy is " +
                    "not excluded from it.",
                checked = settings.requireVpnForNetwork,
                onCheckedChange = { onChange(settings.copy(requireVpnForNetwork = it)) },
            )

            SectionTitle("Privacy and security")
            ToggleRow(
                title = "Block screenshots and screen recording",
                detail = "Sets FLAG_SECURE on the app window. Recommended when your library or sources are private.",
                checked = settings.blockScreenshots,
                onCheckedChange = { onChange(settings.copy(blockScreenshots = it)) },
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "What is protected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Library titles, attached sources, the TMDB key and playback history are encrypted with " +
                            "AES-256-GCM keys held by the Android Keystore. Android backup and device-to-device " +
                            "transfer are disabled for app data. Torrent piece traffic uses forced RC4 peer " +
                            "encryption and the local streaming server only listens on 127.0.0.1 with a random token.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Peer encryption protects Stream Buddy↔peer connections. It cannot hide your IP from the " +
                            "swarm or encrypt metadata you publish to trackers/DHT, which is why the VPN requirement " +
                            "is on by default. Use a trusted VPN if that matters to you. Stream Buddy does not bypass " +
                            "DRM, paywalls or region locks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text("Done") }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset the learned model?") },
            text = {
                Text(
                    "The on-device model and its counters are cleared. The rules baseline stays active while new " +
                        "labels are collected. Your library and downloads are not affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onResetModel()
                    confirmReset = false
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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

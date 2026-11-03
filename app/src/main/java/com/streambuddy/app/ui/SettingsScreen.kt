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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streambuddy.app.BuildConfig
import com.streambuddy.app.ai.PrefetchPredictor
import com.streambuddy.app.cache.CacheManager
import com.streambuddy.app.data.AppSettings
import com.streambuddy.app.util.Format

/** Pre-play window, cache budget/usage, notification permission and app info. */
@Composable
fun SettingsScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cacheBytes by viewModel.cacheBytes.collectAsStateWithLifecycle()
    val model by viewModel.modelState.collectAsStateWithLifecycle()
    val predictions by viewModel.predictions.collectAsStateWithLifecycle()

    var confirmClear by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
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

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}

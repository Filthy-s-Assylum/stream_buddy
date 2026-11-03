package com.streambuddy.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists the apps the user can open on this device, streaming services first.
 *
 * Netflix, Disney+, Prime Video and friends can only play DRM-protected content inside their own
 * app, so Stream Buddy launches them rather than trying to cache their streams.
 */
@Composable
fun AppsScreen(onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
        loading = false
    }

    val filtered = remember(apps, query) {
        if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(
                title = "Streaming apps",
                subtitle = "Tap to open. DRM services only stream inside their own app, so their " +
                    "media can't be pre-cached here.",
            )
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )
        }

        when {
            loading -> item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            filtered.isEmpty() -> item {
                EmptyState(
                    title = if (apps.isEmpty()) "No launchable apps" else "No matches",
                    detail = if (apps.isEmpty()) {
                        "Install a streaming app to see it here."
                    } else {
                        "Try a different search term."
                    },
                )
            }

            else -> items(filtered, key = { it.packageName }) { app ->
                AppRow(app = app, onClick = { launchApp(context, app, onMessage) })
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, onClick: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        ListItem(
            headlineContent = { Text(app.label) },
            supportingContent = {
                Text(
                    text = if (app.isStreaming) "Streaming app" else app.packageName,
                    maxLines = 1,
                )
            },
            trailingContent = {
                if (app.isStreaming) {
                    Text(
                        text = "Open",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        )
    }
}

private data class InstalledApp(
    val label: String,
    val packageName: String,
    val isStreaming: Boolean,
)

private val STREAMING_HINTS = listOf(
    "netflix", "hulu", "disney", "youtube", "twitch", "prime", "amazon", "hbo", "max",
    "peacock", "plex", "crunchyroll", "tubi", "paramount", "spotify", "vudu", "fandango",
    "starz", "showtime", "pluto", "jellyfin", "emby", "kodi", "vlc", "mxtech", "infuse",
)

// The flags-less overload is the only one available below API 33.
@Suppress("DEPRECATION")
private fun loadLaunchableApps(context: Context): List<InstalledApp> {
    val packageManager = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    return packageManager.queryIntentActivities(launcherIntent, 0)
        .mapNotNull { it.activityInfo?.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
        .map { info ->
            InstalledApp(
                label = info.loadLabel(packageManager).toString().ifBlank { info.packageName },
                packageName = info.packageName,
                isStreaming = STREAMING_HINTS.any { info.packageName.contains(it, ignoreCase = true) },
            )
        }
        .sortedWith(
            compareByDescending<InstalledApp> { it.isStreaming }
                .thenBy { it.label.lowercase() },
        )
}

private fun launchApp(context: Context, app: InstalledApp, onMessage: (String) -> Unit) {
    val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
    if (intent == null) {
        onMessage("${app.label} can't be opened")
        return
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        onMessage("Couldn't open ${app.label}")
    }
}

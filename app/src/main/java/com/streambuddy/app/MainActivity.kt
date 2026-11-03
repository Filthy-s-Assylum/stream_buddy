package com.streambuddy.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.streambuddy.app.cache.PreCacheWorker
import com.streambuddy.app.data.SettingsRepository
import com.streambuddy.app.stream.SharedLink
import com.streambuddy.app.ui.AppsScreen
import com.streambuddy.app.ui.MainViewModel
import com.streambuddy.app.ui.PlayerScreen
import com.streambuddy.app.ui.QueueScreen
import com.streambuddy.app.ui.SettingsScreen
import com.streambuddy.app.ui.StreamBuddyTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Single entry point. It accepts:
 *
 *  - ACTION_VIEW with a magnet link;
 *  - ACTION_VIEW with a local video (content:// or file://);
 *  - ACTION_SEND text containing a magnet or an http(s) media link.
 *
 * Nothing is added or played silently: the shared link goes to a confirmation
 * dialog in [StreamBuddyRoot]. Playback only ever uses sources the user
 * attached to their own library entries.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 enforces edge-to-edge; opt in explicitly so older versions match.
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            StreamBuddyTheme {
                StreamBuddyApp(viewModel = viewModel)
            }
        }

        setContent {
            StreamBuddyRoot(
                container = AppGraph.require(),
                sharedSource = pendingShared,
                onSharedSourceHandled = { pendingShared = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppGraph.containerOrNull()?.vpnMonitor?.refresh()
    }

    private fun applySecureFlag(secure: Boolean) {
        if (secure) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
        }
    }

    private fun extractShared(intent: Intent?): SharedSource? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return null
                val value = data.toString().trim()
                when {
                    value.startsWith("magnet:", ignoreCase = true) ->
                        SharedSource(value, null)

                    data.scheme.equals("content", true) || data.scheme.equals("file", true) -> {
                        // Keep read access across restarts for a picked file.
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                data,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                        SharedSource(value, data.lastPathSegment)
                    }

                    value.startsWith("http://", true) || value.startsWith("https://", true) ->
                        if (validateSource(value) == null) {
                            SharedSource(value, data.lastPathSegment)
                        } else {
                            null
                        }

                    else -> null
                }
            }

            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: return null
                val magnet = MAGNET_PATTERN.find(text)?.value
                if (magnet != null) return SharedSource(magnet, null)
                val url = URL_PATTERN.find(text)?.value?.trimEnd('.', ',', ')', ']', '}', '>', '"', '\'')
                if (url != null && validateSource(url) == null) SharedSource(url, null) else null
            }

            else -> null
        }
    }

    override fun onStop() {
        super.onStop()
        // No media session is published, so pause instead of playing audio unattended.
        if (!isChangingConfigurations) viewModel.pausePlayback()
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 7101
        val MAGNET_PATTERN = Regex("magnet:\\?[^\\s]+", RegexOption.IGNORE_CASE)
        val URL_PATTERN = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
    }
}

private data class TabSpec(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val TABS = listOf(
    TabSpec("apps", R.string.tab_apps, Icons.Filled.Home),
    TabSpec("queue", R.string.tab_queue, Icons.Filled.List),
    TabSpec("player", R.string.tab_player, Icons.Filled.PlayArrow),
    TabSpec("settings", R.string.tab_settings, Icons.Filled.Settings),
)

@Composable
private fun StreamBuddyApp(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    // Deep links, share intents and notifications ask for a tab; the nav host applies it once.
    val requestedTab by viewModel.requestedTab.collectAsStateWithLifecycle()
    LaunchedEffect(requestedTab) {
        val route = requestedTab ?: return@LaunchedEffect
        navController.switchTab(route)
        viewModel.consumeRequestedTab()
    }

    RequestNotificationPermissionOnce()

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = { if (currentRoute != tab.route) navController.switchTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "apps",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("apps") {
                AppsScreen(
                    onMessage = { message ->
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    },
                )
            }
            composable("queue") {
                QueueScreen(
                    viewModel = viewModel,
                    onPlay = { stream ->
                        viewModel.play(stream)
                        navController.switchTab("player")
                    },
                )
            }
            composable("player") { PlayerScreen(viewModel = viewModel) }
            composable("settings") { SettingsScreen(viewModel = viewModel) }
        }
    }
}
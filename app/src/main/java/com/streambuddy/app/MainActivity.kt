package com.streambuddy.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streambuddy.app.data.SettingsRepository
import com.streambuddy.app.ui.validateSource

class MainActivity : ComponentActivity() {

    private var pendingShared by mutableStateOf<SharedSource?>(null)
    private var pendingTab by mutableStateOf<AppTab?>(null)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        SettingsRepository.get(this).markNotificationPermissionAsked()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val container = AppGraph.require()
            val settings by container.settingsStore.settings.collectAsStateWithLifecycle()
            LaunchedEffect(settings.blockScreenshots) {
                if (settings.blockScreenshots) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            StreamBuddyRoot(
                container = container,
                sharedSource = pendingShared,
                onSharedSourceHandled = { pendingShared = null },
                requestedTab = pendingTab,
                onRequestedTabHandled = { pendingTab = null },
            )
        }
        requestNotificationPermission()
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

    private fun handleIntent(intent: Intent?) {
        val shortcutTab = extractShortcutTab(intent)
        if (shortcutTab != null) {
            pendingTab = shortcutTab
            pendingShared = null
        } else {
            pendingShared = extractShared(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val settings = SettingsRepository.get(this)
        if (settings.shouldRequestNotificationPermission()) {
            settings.markNotificationPermissionAsked()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun extractShortcutTab(intent: Intent?): AppTab? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        if (!data.scheme.equals("streambuddy", ignoreCase = true)) return null
        return when (data.host?.lowercase()) {
            "settings" -> AppTab.SETTINGS
            "queue" -> AppTab.LIBRARY
            else -> null
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

    private companion object {
        val MAGNET_PATTERN = Regex("magnet:\\?[^\\s]+", RegexOption.IGNORE_CASE)
        val URL_PATTERN = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
    }
}

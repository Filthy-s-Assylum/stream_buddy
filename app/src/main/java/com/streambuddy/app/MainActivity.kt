package com.streambuddy.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.streambuddy.app.SharedSource
import com.streambuddy.app.StreamBuddyRoot
import com.streambuddy.app.ui.validateSource
import kotlinx.coroutines.launch
import java.util.Locale

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

    private var pendingShared by mutableStateOf<SharedSource?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingShared = extractShared(intent)
        requestNotificationPermission()
        applySecureFlag(AppGraph.containerOrNull()?.settingsStore?.current()?.blockScreenshots == true)

        // Keep FLAG_SECURE in sync when the setting changes while the app is
        // open, and re-check the VPN route after the user returns from system
        // settings.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppGraph.require().settingsStore.settings.collect { settings ->
                    applySecureFlag(settings.blockScreenshots)
                }
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
        pendingShared = extractShared(intent)
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

    private companion object {
        const val REQUEST_NOTIFICATIONS = 7101
        val MAGNET_PATTERN = Regex("magnet:\\?[^\\s]+", RegexOption.IGNORE_CASE)
        val URL_PATTERN = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
    }
}

package com.streambuddy.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.streambuddy.app.data.SettingsRepository
import com.streambuddy.app.ui.validateSource

class MainActivity : ComponentActivity() {

    private var pendingShared by mutableStateOf<SharedSource?>(null)
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
            StreamBuddyRoot(
                container = AppGraph.require(),
                sharedSource = pendingShared,
                onSharedSourceHandled = { pendingShared = null },
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
        pendingShared = extractShared(intent)
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
        val STREAMTAPE_PATTERN = Regex("https?://(?:www\\.)?streamtape\\.(?:com|eu)/(?:[a-zA-Z0-9]+)?", RegexOption.IGNORE_CASE)
        val DOOD_PATTERN = Regex("https?://(?:www\\.)?dood\\.(?:co|to)/(?:[a-zA-Z0-9]+)?", RegexOption.IGNORE_CASE)
        val UPTOBOX_PATTERN = Regex("https?://(?:www\\.)?uptobox\\.(?:com|net)/(?:[a-zA-Z0-9]+)?", RegexOption.IGNORE_CASE)
    }
}

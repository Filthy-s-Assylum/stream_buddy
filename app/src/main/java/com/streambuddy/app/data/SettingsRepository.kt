package com.streambuddy.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.StateFlow

/** SharedPreferences-backed notification state and compatibility access to app settings. */
class SettingsRepository private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val settingsStore = SettingsStore.get(context)
    val settings: StateFlow<AppSettings> = settingsStore.settings

    init {
        if (prefs.contains(KEY_PREFETCH_MINUTES) ||
            prefs.contains(KEY_HIGHEST_QUALITY) ||
            prefs.contains(KEY_SMART_PREFETCH)
        ) {
            settingsStore.update { current ->
                current.copy(
                    prefetchMinutes = prefs.getInt(
                        KEY_PREFETCH_MINUTES,
                        AppSettings.DEFAULT_PREFETCH_MINUTES,
                    ),
                    preferHighestQuality = prefs.getBoolean(KEY_HIGHEST_QUALITY, false),
                    smartPrefetch = prefs.getBoolean(KEY_SMART_PREFETCH, true),
                )
            }
            prefs.edit {
                remove(KEY_PREFETCH_MINUTES)
                remove(KEY_HIGHEST_QUALITY)
                remove(KEY_SMART_PREFETCH)
            }
        }
    }

    fun setPrefetchMinutes(minutes: Int) {
        settingsStore.update { it.copy(prefetchMinutes = minutes) }
    }

    fun setPreferHighestQuality(preferHighest: Boolean) {
        settingsStore.update { it.copy(preferHighestQuality = preferHighest) }
    }

    fun setSmartPrefetch(enabled: Boolean) {
        settingsStore.update { it.copy(smartPrefetch = enabled) }
    }

    /** True until the app has asked for POST_NOTIFICATIONS once. */
    fun shouldRequestNotificationPermission(): Boolean =
        !prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)

    fun markNotificationPermissionAsked() {
        prefs.edit { putBoolean(KEY_NOTIFICATIONS_ASKED, true) }
    }

    companion object {
        private const val PREFS_NAME = "stream_buddy_settings"
        private const val KEY_PREFETCH_MINUTES = "prefetch_minutes"
        private const val KEY_HIGHEST_QUALITY = "prefer_highest_quality"
        private const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
        private const val KEY_SMART_PREFETCH = "smart_prefetch"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository = instance ?: synchronized(this) {
            instance ?: SettingsRepository(context).also { instance = it }
        }
    }
}

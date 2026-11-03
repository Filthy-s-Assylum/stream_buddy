package com.streambuddy.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-tunable pre-cache behaviour. */
data class AppSettings(
    /** How much playback to pre-cache ahead of time, in minutes. */
    val prefetchMinutes: Int = DEFAULT_PREFETCH_MINUTES,
    /** `false` pre-caches the lowest-bandwidth variant (data saver), `true` the highest. */
    val preferHighestQuality: Boolean = false,
    /**
     * Let the on-device predictor choose *what* gets pre-cached and how deeply. When off, only
     * streams you explicitly queue are cached, each with the configured window.
     */
    val smartPrefetch: Boolean = true,
) {
    val prefetchMillis: Long get() = prefetchMinutes * 60_000L

    companion object {
        const val DEFAULT_PREFETCH_MINUTES = 5
        const val MIN_PREFETCH_MINUTES = 1
        const val MAX_PREFETCH_MINUTES = 30
        val PRESET_MINUTES = listOf(2, 5, 10, 20)
    }
}

/** SharedPreferences-backed settings, exposed as a [StateFlow] for Compose. */
class SettingsRepository private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun setPrefetchMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(AppSettings.MIN_PREFETCH_MINUTES, AppSettings.MAX_PREFETCH_MINUTES)
        prefs.edit { putInt(KEY_PREFETCH_MINUTES, clamped) }
        _settings.value = read()
    }

    fun setPreferHighestQuality(preferHighest: Boolean) {
        prefs.edit { putBoolean(KEY_HIGHEST_QUALITY, preferHighest) }
        _settings.value = read()
    }

    fun setSmartPrefetch(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SMART_PREFETCH, enabled) }
        _settings.value = read()
    }

    /** True until the app has asked for POST_NOTIFICATIONS once. */
    fun shouldRequestNotificationPermission(): Boolean =
        !prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)

    fun markNotificationPermissionAsked() {
        prefs.edit { putBoolean(KEY_NOTIFICATIONS_ASKED, true) }
    }

    private fun read(): AppSettings = AppSettings(
        prefetchMinutes = prefs.getInt(KEY_PREFETCH_MINUTES, AppSettings.DEFAULT_PREFETCH_MINUTES)
            .coerceIn(AppSettings.MIN_PREFETCH_MINUTES, AppSettings.MAX_PREFETCH_MINUTES),
        preferHighestQuality = prefs.getBoolean(KEY_HIGHEST_QUALITY, false),
        smartPrefetch = prefs.getBoolean(KEY_SMART_PREFETCH, true),
    )

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

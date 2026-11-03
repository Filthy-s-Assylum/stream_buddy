package com.streambuddy.app.data

import android.content.Context
import com.streambuddy.app.core.security.SecurePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLDecoder
import java.net.URLEncoder

data class AppSettings(
    val prefetchMinutes: Int = DEFAULT_PREFETCH_MINUTES,
    val preferHighestQuality: Boolean = false,
    val smartPrefetch: Boolean = true,
    val playbackCacheMb: Int = 2048,
    val startBufferSeconds: Int = 4,
    val bufferTargetSeconds: Int = 45,
    val prefetchSeconds: Int = 90,
    val maxVideoBitrateKbps: Int = 0,
    val optimizerMode: OptimizerMode = OptimizerMode.RULES,
    val torrentDownloadLimitKbps: Int = 0,
    val torrentUploadLimitKbps: Int = 64,
    val seedingEnabled: Boolean = false,
    val torrentStorageLimitMb: Int = 4096,
    val metadataTimeoutSeconds: Int = 45,
    val wifiOnlyDownloads: Boolean = true,
    val requireVpnForNetwork: Boolean = true,
    val malwareScanEnabled: Boolean = true,
    val blockScreenshots: Boolean = false,
    val tmdbApiKey: String = "",
    val motnApiKey: String = "",
    val motnCountry: String = "us",
    val autoplayNextEpisode: Boolean = false,
    val preferredSubtitleLanguage: String = "",
) {
    val prefetchMillis: Long get() = prefetchMinutes * 60_000L

    fun sanitized(): AppSettings = copy(
        prefetchMinutes = prefetchMinutes.coerceIn(MIN_PREFETCH_MINUTES, MAX_PREFETCH_MINUTES),
        playbackCacheMb = playbackCacheMb.coerceIn(128, 16 * 1024),
        startBufferSeconds = startBufferSeconds.coerceIn(2, 30),
        bufferTargetSeconds = bufferTargetSeconds.coerceIn(10, 180),
        prefetchSeconds = prefetchSeconds.coerceIn(15, 600),
        maxVideoBitrateKbps = maxVideoBitrateKbps.coerceIn(0, 100_000),
        torrentDownloadLimitKbps = torrentDownloadLimitKbps.coerceIn(0, 1_000_000),
        torrentUploadLimitKbps = torrentUploadLimitKbps.coerceIn(8, 1_000_000),
        torrentStorageLimitMb = torrentStorageLimitMb.coerceIn(256, 512 * 1024),
        metadataTimeoutSeconds = metadataTimeoutSeconds.coerceIn(10, 180),
        motnApiKey = motnApiKey.take(200),
        motnCountry = motnCountry.trim().lowercase().filter { it.isLetter() }.take(2).ifBlank { "us" },
    )

    internal fun encode(): String {
        val values = linkedMapOf(
            "v" to "1",
            "prefetchMinutes" to prefetchMinutes.toString(),
            "preferHighestQuality" to preferHighestQuality.toString(),
            "smartPrefetch" to smartPrefetch.toString(),
            "playbackCacheMb" to playbackCacheMb.toString(),
            "startBufferSeconds" to startBufferSeconds.toString(),
            "bufferTargetSeconds" to bufferTargetSeconds.toString(),
            "prefetchSeconds" to prefetchSeconds.toString(),
            "maxVideoBitrateKbps" to maxVideoBitrateKbps.toString(),
            "optimizerMode" to optimizerMode.name,
            "torrentDownloadLimitKbps" to torrentDownloadLimitKbps.toString(),
            "torrentUploadLimitKbps" to torrentUploadLimitKbps.toString(),
            "seedingEnabled" to seedingEnabled.toString(),
            "torrentStorageLimitMb" to torrentStorageLimitMb.toString(),
            "metadataTimeoutSeconds" to metadataTimeoutSeconds.toString(),
            "wifiOnlyDownloads" to wifiOnlyDownloads.toString(),
            "requireVpnForNetwork" to requireVpnForNetwork.toString(),
            "malwareScanEnabled" to malwareScanEnabled.toString(),
            "blockScreenshots" to blockScreenshots.toString(),
            "tmdbApiKey" to tmdbApiKey,
            "motnApiKey" to motnApiKey,
            "motnCountry" to motnCountry,
            "autoplayNextEpisode" to autoplayNextEpisode.toString(),
            "preferredSubtitleLanguage" to preferredSubtitleLanguage,
        )
        return values.entries.joinToString("\n") { (key, value) ->
            key + "=" + URLEncoder.encode(value, "UTF-8")
        }
    }

    internal companion object {
        fun decode(raw: String): AppSettings {
            val map = raw.lineSequence().mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null
                else line.substring(0, index) to URLDecoder.decode(line.substring(index + 1), "UTF-8")
            }.toMap()
            fun int(key: String, fallback: Int) = map[key]?.toIntOrNull() ?: fallback
            fun bool(key: String, fallback: Boolean) = map[key]?.toBooleanStrictOrNull() ?: fallback
            return AppSettings(
                prefetchMinutes = int("prefetchMinutes", DEFAULT_PREFETCH_MINUTES),
                preferHighestQuality = bool("preferHighestQuality", false),
                smartPrefetch = bool("smartPrefetch", true),
                playbackCacheMb = int("playbackCacheMb", 2048),
                startBufferSeconds = int("startBufferSeconds", 4),
                bufferTargetSeconds = int("bufferTargetSeconds", 45),
                prefetchSeconds = int("prefetchSeconds", 90),
                maxVideoBitrateKbps = int("maxVideoBitrateKbps", 0),
                optimizerMode = map["optimizerMode"]
                    ?.let { runCatching { OptimizerMode.valueOf(it) }.getOrNull() }
                    ?: OptimizerMode.RULES,
                torrentDownloadLimitKbps = int("torrentDownloadLimitKbps", 0),
                torrentUploadLimitKbps = int("torrentUploadLimitKbps", 64),
                seedingEnabled = bool("seedingEnabled", false),
                torrentStorageLimitMb = int("torrentStorageLimitMb", 4096),
                metadataTimeoutSeconds = int("metadataTimeoutSeconds", 45),
                wifiOnlyDownloads = bool("wifiOnlyDownloads", true),
                requireVpnForNetwork = bool("requireVpnForNetwork", true),
                malwareScanEnabled = bool("malwareScanEnabled", true),
                blockScreenshots = bool("blockScreenshots", false),
                tmdbApiKey = (map["tmdbApiKey"] ?: com.streambuddy.app.BuildConfig.TMDB_API_KEY).take(200),
                motnApiKey = (map["motnApiKey"] ?: com.streambuddy.app.BuildConfig.MOTN_API_KEY).take(200),
                motnCountry = (map["motnCountry"] ?: "us").take(2),
                autoplayNextEpisode = bool("autoplayNextEpisode", false),
                preferredSubtitleLanguage = map["preferredSubtitleLanguage"].orEmpty().take(16),
            ).sanitized()
        }

        const val DEFAULT_PREFETCH_MINUTES = 5
        const val MIN_PREFETCH_MINUTES = 1
        const val MAX_PREFETCH_MINUTES = 30
        val PRESET_MINUTES = listOf(2, 5, 10, 20)
    }
}

/**
 * Settings are serialised to one encrypted blob. No cache limits, torrent
 * preferences or URLs are readable from the preferences file.
 */
class SettingsStore private constructor(context: Context) {
    private val prefs = SecurePreferences(context.applicationContext, "stream_buddy_settings")
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings {
        val raw = prefs.getString(KEY) ?: return AppSettings(
            tmdbApiKey = com.streambuddy.app.BuildConfig.TMDB_API_KEY.take(200),
        )
        return runCatching { AppSettings.decode(raw) }.getOrElse {
            AppSettings(tmdbApiKey = com.streambuddy.app.BuildConfig.TMDB_API_KEY.take(200))
        }
    }

    fun current(): AppSettings = _settings.value

    fun update(transform: (AppSettings) -> AppSettings): AppSettings =
        updateInternal(transform).settings

    fun updateAndReportPersistence(transform: (AppSettings) -> AppSettings): Boolean =
        updateInternal(transform).persisted

    private fun updateInternal(transform: (AppSettings) -> AppSettings): UpdateResult {
        synchronized(this) {
            val updated = transform(_settings.value).sanitized()
            val persisted = runCatching { prefs.putString(KEY, updated.encode()) }.isSuccess
            _settings.value = updated
            return UpdateResult(updated, persisted)
        }
    }

    fun clear() {
        prefs.remove(KEY)
        _settings.value = AppSettings(
            tmdbApiKey = com.streambuddy.app.BuildConfig.TMDB_API_KEY.take(200),
        )
    }

    companion object {
        const val KEY = "settings_v1"

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore = instance ?: synchronized(this) {
            instance ?: SettingsStore(context.applicationContext).also { instance = it }
        }
    }

    private data class UpdateResult(val settings: AppSettings, val persisted: Boolean)
}

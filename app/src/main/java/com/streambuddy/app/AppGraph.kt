package com.streambuddy.app

import android.content.Context
import com.streambuddy.app.data.AppDb
import com.streambuddy.app.data.LibraryRepository
import com.streambuddy.app.data.SettingsStore
import com.streambuddy.app.metadata.MetadataRepository
import com.streambuddy.app.metadata.MotnRepository
import com.streambuddy.app.net.NetworkGuard
import com.streambuddy.app.net.NetworkPolicy
import com.streambuddy.app.net.VpnMonitor
import com.streambuddy.app.optimizer.OptimizerRuntime
import com.streambuddy.app.playback.PlayerEngine
import com.streambuddy.app.security.SecurityCenter
import com.streambuddy.app.torrent.TorrentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Process-wide dependency graph. It is created once on the main thread so the
 * ExoPlayer instance and its MediaSession always have a Looper.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val settingsStore = SettingsStore(appContext)
    val db: AppDb = AppDb.get(appContext)
    val libraryRepository = LibraryRepository(db.library())
    val metadataRepository = MetadataRepository(settingsStore)
    val motnRepository = MotnRepository(settingsStore)
    val vpnMonitor = VpnMonitor(appContext)
    val networkPolicy = NetworkPolicy(settingsStore, vpnMonitor).also { NetworkGuard.install(it) }
    val optimizerRuntime = OptimizerRuntime(appContext, settingsStore, db)
    val securityCenter = SecurityCenter(appContext, settingsStore)
    val torrentManager = TorrentManager(appContext, settingsStore, vpnMonitor, securityCenter)
    val playerEngine = PlayerEngine(
        context = appContext,
        settingsStore = settingsStore,
        optimizer = optimizerRuntime,
        torrentManager = torrentManager,
        library = libraryRepository,
        vpnMonitor = vpnMonitor,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // If the tunnel drops while offline downloads are running, pause them
        // instead of letting Media3 continue over Wi-Fi or mobile data.
        scope.launch {
            combine(settingsStore.settings, vpnMonitor.status) { settings, vpn ->
                settings.requireVpnForNetwork && !vpn.active
            }
                .distinctUntilChanged()
                .collect { blocked ->
                    if (blocked) {
                        runCatching {
                            com.streambuddy.app.download.Downloads.pauseAll(appContext)
                        }
                    }
                }
        }
    }

    fun shutdown() {
        scope.cancel()
        NetworkGuard.clear()
        runCatching { playerEngine.release() }
        runCatching { torrentManager.release() }
        runCatching { securityCenter.release() }
        runCatching { vpnMonitor.release() }
        runCatching { com.streambuddy.app.download.Downloads.release() }
        runCatching { com.streambuddy.app.cache.PlaybackCache.release() }
    }
}

object AppGraph {

    @Volatile
    private var container: AppContainer? = null

    fun init(context: Context): AppContainer =
        container ?: synchronized(this) {
            container ?: AppContainer(context.applicationContext).also { container = it }
        }

    fun containerOrNull(): AppContainer? = container

    fun require(): AppContainer = container
        ?: error("AppGraph.init() must run in Application.onCreate()")

    fun shutdown() {
        synchronized(this) {
            container?.shutdown()
            container = null
        }
    }
}

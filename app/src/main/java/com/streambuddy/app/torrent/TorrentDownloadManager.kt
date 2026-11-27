package com.streambuddy.app.torrent

import android.content.Context
import android.os.Environment
import com.streambuddy.app.data.SettingsStore
import com.streambuddy.app.net.VpnMonitor
import com.streambuddy.app.net.VpnRequiredMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

enum class TorrentDownloadStatus { IDLE, PREPARING, DOWNLOADING, COMPLETED, FAILED, CANCELLED }

data class TorrentDownloadState(
    val itemKey: String = "",
    val title: String = "",
    val fileName: String = "",
    val filePath: String? = null,
    val status: TorrentDownloadStatus = TorrentDownloadStatus.IDLE,
    val progress: Double = 0.0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val rateBps: Long = 0L,
    val peers: Int = 0,
    val error: String? = null,
) {
    val active: Boolean
        get() = status == TorrentDownloadStatus.PREPARING || status == TorrentDownloadStatus.DOWNLOADING
}

/**
 * Downloads the selected playable file of one magnet into app-specific external
 * storage. It uses the same hardened libtorrent engine as streaming, enforces
 * rate limits and quality preferences, and keeps the VPN gate enforced while running.
 */
class TorrentDownloadManager(
    context: Context,
    private val settingsStore: SettingsStore,
    private val vpnMonitor: VpnMonitor,
) {
    private val appContext = context.applicationContext
    private val engine = LibtorrentEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(TorrentDownloadState())
    val state: StateFlow<TorrentDownloadState> = _state.asStateFlow()

    private var job: Job? = null

    /** Destination for completed media. No storage permission is required. */
    fun downloadRoot(): File {
        val base = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(appContext.filesDir, "movies")
        return File(base, "StreamBuddy").apply { mkdirs() }
    }

    fun start(itemKey: String, title: String, magnet: String) {
        job?.cancel()
        job = scope.launch {
            mutex.withLock { runDownload(itemKey, title, magnet) }
        }
    }

    fun cancel() {
        val activeJob = job
        job = null
        activeJob?.cancel()
        _state.value = _state.value.copy(
            status = TorrentDownloadStatus.CANCELLED,
            rateBps = 0L,
        )
    }

    fun release() {
        cancel()
        scope.cancel()
    }

    private suspend fun runDownload(itemKey: String, title: String, magnet: String) = withContext(Dispatchers.IO) {
        try {
            val settings = settingsStore.current()
            if (settings.requireVpnForNetwork && !vpnMonitor.isActive()) {
                fail(VpnRequiredMessage.torrent)
                return@withContext
            }
            val parsed = Magnet.parse(magnet).getOrElse {
                fail("That magnet is not valid. It needs a 40-character btih info hash.")
                return@withContext
            }
            val directory = File(
                downloadRoot(),
                TorrentFilePolicy.safeDirectoryName(parsed.infoHashHex, parsed.displayName ?: title),
            )
            if (!directory.isDirectory && !directory.mkdirs()) {
                fail("Could not create the download folder.")
                return@withContext
            }

            _state.value = TorrentDownloadState(
                itemKey = itemKey,
                title = title,
                status = TorrentDownloadStatus.PREPARING,
            )

            engine.start()
            engine.setDownloadLimit(settings.torrentDownloadLimitKbps)
            engine.setUploadLimit(settings.torrentUploadLimitKbps)
            engine.addMagnet(magnet, directory)
            val info = engine.awaitMetadata(settings.metadataTimeoutSeconds * 1_000L).getOrElse { error ->
                fail(error.message ?: "No metadata arrived for this magnet.")
                return@withContext
            }
            val handle = engine.handle ?: run {
                fail("The torrent session did not start.")
                return@withContext
            }
            val files = engine.files(info)
            val targetHeight = runCatching {
                val metrics = appContext.resources.displayMetrics
                minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(480)
            }.getOrDefault(1080)
            val main = TorrentFilePolicy.chooseMainFile(
                files = files,
                preferHighestQuality = settings.preferHighestQuality,
                targetHeightPx = targetHeight,
            ) ?: run {
                fail("This torrent has no playable video or audio file.")
                return@withContext
            }
            if (directory.usableSpace < main.size / 2) {
                fail("Not enough free storage space to download ${main.fileName}.")
                return@withContext
            }

            engine.selectFiles(
                handle = handle,
                files = files,
                mainIndex = main.index,
                subtitleIndexes = TorrentFilePolicy.subtitles(files).map { it.index }.toSet(),
            )
            engine.setSeeding(handle, settings.seedingEnabled)

            _state.value = _state.value.copy(
                fileName = main.fileName,
                totalBytes = main.size,
                status = TorrentDownloadStatus.DOWNLOADING,
            )

            while (isActive) {
                val currentSettings = settingsStore.current()
                if (currentSettings.requireVpnForNetwork && !vpnMonitor.isActive()) {
                    engine.pause(handle)
                    fail(VpnRequiredMessage.torrent)
                    return@withContext
                }
                val status = engine.status(handle)
                val done = status?.totalWantedDone() ?: 0L
                val rate = status?.downloadRate()?.toLong() ?: 0L
                val peers = status?.numPeers() ?: 0
                _state.value = _state.value.copy(
                    progress = if (main.size > 0) (done.toDouble() / main.size).coerceIn(0.0, 1.0) else 0.0,
                    downloadedBytes = done,
                    rateBps = rate,
                    peers = peers,
                    status = TorrentDownloadStatus.DOWNLOADING,
                )
                if (engine.isFinished || (main.size > 0 && done >= main.size)) {
                    complete(directory, main)
                    return@withContext
                }
                engine.lastError()?.let { message ->
                    fail(message)
                    return@withContext
                }
                delay(POLL_MS)
            }
        } catch (cancelled: CancellationException) {
            runCatching { engine.remove(deleteFiles = true) }
            runCatching { engine.shutdown() }
            throw cancelled
        }
    }

    private fun complete(directory: File, main: SafeTorrentFile) {
        val relative = main.safePath ?: main.path
        val file = File(directory, relative)
        runCatching { engine.remove(deleteFiles = false) }
        runCatching { engine.shutdown() }
        _state.value = _state.value.copy(
            status = TorrentDownloadStatus.COMPLETED,
            progress = 1.0,
            downloadedBytes = main.size,
            totalBytes = main.size,
            rateBps = 0L,
            filePath = file.absolutePath,
            error = null,
        )
    }

    private fun fail(message: String) {
        runCatching { engine.remove(deleteFiles = false) }
        runCatching { engine.shutdown() }
        _state.value = _state.value.copy(
            status = TorrentDownloadStatus.FAILED,
            rateBps = 0L,
            error = message,
        )
    }

    private companion object {
        const val POLL_MS = 1_000L
    }
}

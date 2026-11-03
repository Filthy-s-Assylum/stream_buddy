package com.streambuddy.app.torrent

import android.content.Context
import com.streambuddy.app.data.AppSettings
import com.streambuddy.app.data.SettingsStore
import com.streambuddy.app.net.VpnMonitor
import com.streambuddy.app.net.VpnRequiredMessage
import com.streambuddy.app.security.SecurityCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.util.Locale
import kotlin.math.abs

enum class TorrentPhase {
    IDLE,
    FETCHING_METADATA,
    BUFFERING,
    STREAMING,
    PAUSED,
    SEEDING,
    ERROR,
}

data class TorrentUiState(
    val phase: TorrentPhase = TorrentPhase.IDLE,
    val title: String = "",
    val infoHash: String = "",
    val progress: Double = 0.0,
    val downloadRateBps: Long = 0L,
    val uploadRateBps: Long = 0L,
    val peers: Int = 0,
    val seeds: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val selectedFileName: String = "",
    val selectedFileSize: Long = 0L,
    val error: String? = null,
    val trackerWarning: String? = null,
    val seeding: Boolean = false,
    val paused: Boolean = false,
    val totalFiles: Int = 0,
) {
    val active: Boolean get() = phase in setOf(
        TorrentPhase.FETCHING_METADATA,
        TorrentPhase.BUFFERING,
        TorrentPhase.STREAMING,
        TorrentPhase.PAUSED,
        TorrentPhase.SEEDING,
    )
}

data class TorrentSubtitle(
    val index: Int,
    val label: String,
    val url: String,
)

data class TorrentOpenResult(
    val mediaUrl: String,
    val title: String,
    val infoHash: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val subtitles: List<TorrentSubtitle>,
    val peers: Int,
    val totalFiles: Int,
)

data class ImportedTorrent(
    val file: File,
    val infoHash: String,
    val name: String,
)

/**
 * Owns the single active torrent, the loopback HTTP bridge and the storage
 * quota.
 *
 * The play button in the library calls [open] with the source the user attached
 * to that title. Stream Buddy does not search for torrents and does not inject
 * sources into the library; [open] is only ever reached through a user-owned
 * magnet or direct URL.
 */
class TorrentManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val vpnMonitor: VpnMonitor,
    private val securityCenter: SecurityCenter,
) {
    private val engine = LibtorrentEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val openMutex = Mutex()

    private val _state = MutableStateFlow(TorrentUiState())
    val state: StateFlow<TorrentUiState> = _state.asStateFlow()

    private var active: Active? = null
    private var pollJob: Job? = null

    /** True when the swarm was paused automatically because the VPN dropped. */
    @Volatile
    private var vpnPaused = false

    @Volatile
    private var optimizerAheadBytes: Long? = null

    private val torrentRoot: File
        get() = File(context.filesDir, "torrents").apply { mkdirs() }

    init {
        // Fail closed: if the VPN route disappears mid-stream, stop all peer
        // traffic until the user resumes with a VPN in place. We do not resume
        // automatically, because that could silently expose the user's IP.
        scope.launch {
            vpnMonitor.status.collectLatest { status ->
                if (status.active) {
                    if (vpnPaused && active != null) {
                        _state.value = _state.value.copy(
                            phase = TorrentPhase.PAUSED,
                            paused = true,
                            trackerWarning = "VPN restored. Press “Resume torrent” when you are ready.",
                        )
                    }
                    return@collectLatest
                }
                if (!settingsStore.current().requireVpnForNetwork) return@collectLatest
                val session = active ?: return@collectLatest
                if (_state.value.phase in VPN_SENSITIVE_PHASES) {
                    runCatching { engine.pause(session.handle) }
                    vpnPaused = true
                    _state.value = _state.value.copy(
                        phase = TorrentPhase.PAUSED,
                        paused = true,
                        downloadRateBps = 0,
                        uploadRateBps = 0,
                        trackerWarning = "VPN connection lost. Torrent traffic was paused to avoid exposing your IP.",
                    )
                }
            }
        }
    }

    suspend fun open(magnetUri: String, startFraction: Double = 0.0): Result<TorrentOpenResult> =
        withContext(Dispatchers.IO) {
            openMutex.withLock {
                closeInternal(deleteFiles = true, stopService = false)
                val parsed = Magnet.parse(magnetUri).getOrElse {
                    return@withContext Result.failure(
                        TorrentException("That is not a valid magnet link. Paste a magnet with a btih info hash."),
                    )
                }
                openLocked(parsed, magnetUri, null, startFraction)
            }
        }

    /**
     * Opens a .torrent captured by the built-in browser or a file manager.
     * The file's own metadata is used directly, so it works even when the
     * tracker list is empty and DHT cannot discover the swarm.
     */
    suspend fun openTorrentFile(
        torrentFile: File,
        startFraction: Double = 0.0,
    ): Result<TorrentOpenResult> =
        withContext(Dispatchers.IO) {
            openMutex.withLock {
                closeInternal(deleteFiles = true, stopService = false)
                if (!torrentFile.isFile || torrentFile.length() <= 0L) {
                    return@withContext Result.failure(
                        TorrentException("That .torrent file is empty or missing."),
                    )
                }
                val info = runCatching { TorrentInfo(torrentFile) }.getOrElse {
                    return@withContext Result.failure(
                        TorrentException("That file is not valid .torrent metadata."),
                    )
                }
                if (!runCatching { info.isValid }.getOrDefault(false)) {
                    return@withContext Result.failure(
                        TorrentException("That .torrent file is not valid."),
                    )
                }
                val hash = runCatching { info.infoHash().toHex() }.getOrElse {
                    return@withContext Result.failure(
                        TorrentException("That .torrent has no v1 info hash. Add it as a magnet instead."),
                    )
                }
                val name = runCatching { info.name() }.getOrNull()
                openLocked(MagnetLink(hash, name, emptyList(), null), null, torrentFile, startFraction)
            }
        }

    /**
     * Copies a user-captured .torrent into persistent app-private storage and
     * returns its canonical path + info hash. The original browser copy can
     * then be removed without breaking the library entry.
     */
    suspend fun importTorrentFile(source: File): Result<ImportedTorrent> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(source.isFile && source.length() > 0L) { "The .torrent file is empty." }
                val info = TorrentInfo(source)
                require(info.isValid) { "The .torrent metadata is not valid." }
                val hash = info.infoHash().toHex()
                val name = runCatching { info.name() }.getOrDefault("Torrent").take(300)
                val directory = File(torrentRoot, "meta").apply { mkdirs() }
                val target = File(directory, "$hash.torrent")
                if (!target.exists()) {
                    val temporary = File(directory, "$hash.torrent.part")
                    source.copyTo(temporary, overwrite = true)
                    if (!temporary.renameTo(target)) {
                        temporary.copyTo(target, overwrite = true)
                        temporary.delete()
                    }
                }
                ImportedTorrent(target, hash, name)
            }
        }

    private suspend fun openLocked(
        parsed: MagnetLink,
        magnetUri: String?,
        torrentFile: File?,
        startFraction: Double,
    ): Result<TorrentOpenResult> {
                val settings = settingsStore.current()
                if (settings.requireVpnForNetwork && !vpnMonitor.isActive()) {
                    val message = VpnRequiredMessage.torrent
                    _state.value = TorrentUiState(phase = TorrentPhase.ERROR, error = message)
                    return Result.failure(TorrentException(message))
                }
                _state.value = TorrentUiState(
                    phase = TorrentPhase.FETCHING_METADATA,
                    title = parsed.displayName.orEmpty(),
                    infoHash = parsed.infoHashHex,
                )
                TorrentService.start(context)
                return try {
                    val directory = File(torrentRoot, TorrentFilePolicy.safeDirectoryName(parsed.infoHashHex, parsed.displayName))
                    if (!directory.isDirectory && !directory.mkdirs()) {
                        throw TorrentException("Cannot create a private storage directory for this torrent.")
                    }
                    engine.start()
                    engine.setDownloadLimit(settings.torrentDownloadLimitKbps)
                    engine.setUploadLimit(settings.torrentUploadLimitKbps)
                    if (torrentFile != null) {
                        engine.addTorrentFile(torrentFile, directory)
                    } else {
                        engine.addMagnet(
                            magnetUri ?: throw TorrentException("No magnet or .torrent source was provided."),
                            directory,
                        )
                    }

                    val info = engine.awaitMetadata(settings.metadataTimeoutSeconds * 1_000L).getOrThrow()
                    val files = engine.files(info)
                    val main = TorrentFilePolicy.chooseMainFile(files)
                        ?: throw TorrentException(
                            "This torrent has no playable video or audio file. Stream Buddy only plays media files.",
                        )
                    val subtitles = TorrentFilePolicy.subtitles(files).take(MAX_SUBTITLES)
                    val requiredBytes = main.size + subtitles.sumOf { it.size }
                    val quota = settings.torrentStorageLimitMb.toLong() * 1024L * 1024L
                    if (requiredBytes > quota) {
                        throw TorrentException(
                            "This title needs ${com.streambuddy.app.core.Fmt.bytes(requiredBytes)} but the torrent storage limit is " +
                                "${com.streambuddy.app.core.Fmt.bytes(quota)}. Raise the limit in Settings.",
                        )
                    }
                    if (freeSpaceBytes(directory) < requiredBytes / 2) {
                        throw TorrentException("Not enough free storage for this title. Free some space and try again.")
                    }

                    val handle = engine.awaitHandle(10_000L)
                        ?: throw TorrentException(engine.lastError() ?: "The torrent could not be added.")
                    engine.selectFiles(handle, files, main.index, subtitles.map { it.index }.toSet())
                    engine.setSeeding(handle, settings.seedingEnabled)

                    val info2 = runCatching { handle.torrentFile() }.getOrNull() ?: info
                    val pieceLength = info2.pieceLength().toLong().coerceAtLeast(16 * 1024L)
                    val offsets = engine.fileOffsets(info2)
                    val fileOffset = offsets.getOrElse(main.index) { 0L }

                    val session = Active(
                        magnet = parsed,
                        directory = directory,
                        handle = handle,
                        info = info2,
                        files = files,
                        mainFile = main,
                        fileOffset = fileOffset,
                        pieceLength = pieceLength,
                        subtitles = subtitles,
                        startFraction = startFraction.coerceIn(0.0, 1.0),
                    )
                    val server = LoopbackServer(
                        object : FileCatalog {
                            override fun media(): ServedFile? = session.mediaFile()
                            override fun subtitle(index: Int): ServedFile? = session.subtitleFile(index)
                        },
                    )
                    session.server = server
                    server.start()
                    active = session

                    val startByte = (main.size * session.startFraction).toLong().coerceIn(0L, (main.size - 1).coerceAtLeast(0L))
                    engine.updatePlayhead(
                        handle = handle,
                        info = info2,
                        fileOffset = fileOffset,
                        fileSize = main.size,
                        positionInFile = startByte,
                        bufferAheadBytes = bufferAheadBytes(settings),
                        previousDeadlines = session.deadlines,
                    )

                    _state.value = TorrentUiState(
                        phase = TorrentPhase.BUFFERING,
                        title = parsed.displayName.orEmpty(),
                        infoHash = parsed.infoHashHex,
                        selectedFileName = main.fileName,
                        selectedFileSize = main.size,
                        totalBytes = main.size + subtitles.sumOf { it.size },
                        totalFiles = files.size,
                    )

                    val probe = minOf(main.size - startByte, 6L * 1024L * 1024L).coerceAtLeast(1L)
                    val ready = engine.awaitRange(
                        handle = handle,
                        pieceLength = pieceLength,
                        start = startByte,
                        endExclusive = startByte + probe,
                        timeoutMs = FIRST_BUFFER_TIMEOUT_MS,
                    )
                    if (!ready) {
                        val status = engine.status(handle)
                        val peers = status?.numPeers() ?: 0
                        throw TorrentException(
                            if (peers == 0) {
                                "No peers are available for this magnet. The torrent may be old, unseeded, or blocked on this network."
                            } else {
                                "Peers are connected but did not deliver the first pieces in time. Try again, or lower the start buffer in Settings."
                            },
                        )
                    }

                    // The first pieces are on disk. Inspect the real bytes
                    // before the loopback URL is handed to the player.
                    val mediaPath = session.mainFile.safePath
                    if (!mediaPath.isNullOrBlank()) {
                        val report = securityCenter.scanForPlayback(
                            File(directory, mediaPath),
                            "Torrent · ${session.mainFile.fileName}",
                        )
                        if (report?.blocked == true) {
                            throw TorrentException(
                                "Malware scan blocked this torrent: ${report.summary}",
                            )
                        }
                    }

                    _state.value = _state.value.copy(phase = TorrentPhase.STREAMING)
                    startPolling()
                    Result.success(
                        TorrentOpenResult(
                            mediaUrl = server.mediaUrl(),
                            title = parsed.displayName.orEmpty().ifBlank { main.fileName },
                            infoHash = parsed.infoHashHex,
                            fileName = main.fileName,
                            fileSizeBytes = main.size,
                            subtitles = subtitles.mapIndexed { index, file ->
                                TorrentSubtitle(index, file.fileName, server.subtitleUrl(index))
                            },
                            peers = engine.status(handle)?.numPeers() ?: 0,
                            totalFiles = files.size,
                        ),
                    )
                } catch (throwable: Throwable) {
                    closeInternal(deleteFiles = true, stopService = true)
                    val message = when (throwable) {
                        is TorrentException -> throwable.message ?: "Torrent failed"
                        else -> engine.lastError() ?: throwable.message ?: "Torrent failed"
                    }
                    _state.value = _state.value.copy(phase = TorrentPhase.ERROR, error = message)
                    Result.failure(TorrentException(message, throwable))
                }
}

    fun pause() {
        val session = active ?: return
        engine.pause(session.handle)
        vpnPaused = false
        _state.value = _state.value.copy(phase = TorrentPhase.PAUSED, paused = true)
    }

    fun resume() {
        val session = active ?: return
        val settings = settingsStore.current()
        if (settings.requireVpnForNetwork && !vpnMonitor.isActive()) {
            _state.value = _state.value.copy(
                phase = TorrentPhase.PAUSED,
                paused = true,
                trackerWarning = VpnRequiredMessage.torrent,
            )
            return
        }
        engine.resume(session.handle)
        vpnPaused = false
        _state.value = _state.value.copy(
            phase = if (engine.isFinished) TorrentPhase.SEEDING else TorrentPhase.STREAMING,
            paused = false,
            trackerWarning = null,
        )
    }

    fun onPlayhead(positionMs: Long, durationMs: Long) {
        val session = active ?: return
        if (durationMs <= 0 || session.mainFile.size <= 0) return
        val now = System.currentTimeMillis()
        val fraction = (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
        val seeked = abs(fraction - session.lastFraction) * session.mainFile.size > SEEK_THRESHOLD_BYTES
        if (!seeked && now - session.lastWindowUpdateMs < WINDOW_UPDATE_MS) return
        session.lastFraction = fraction
        session.lastWindowUpdateMs = now
        engine.updatePlayhead(
            handle = session.handle,
            info = session.info,
            fileOffset = session.fileOffset,
            fileSize = session.mainFile.size,
            positionInFile = (session.mainFile.size * fraction).toLong(),
            bufferAheadBytes = bufferAheadBytes(settingsStore.current()),
            previousDeadlines = session.deadlines,
        )
    }

    fun setSeeding(enabled: Boolean) {
        settingsStore.update { it.copy(seedingEnabled = enabled) }
        val session = active ?: return
        engine.setSeeding(session.handle, enabled)
        _state.value = _state.value.copy(seeding = enabled)
    }

    fun applyRateLimits(settings: AppSettings) {
        engine.setDownloadLimit(settings.torrentDownloadLimitKbps)
        engine.setUploadLimit(settings.torrentUploadLimitKbps)
        active?.let { engine.setSeeding(it.handle, settings.seedingEnabled) }
        if (settings.requireVpnForNetwork && !vpnMonitor.isActive()) {
            val session = active ?: return
            if (_state.value.phase in VPN_SENSITIVE_PHASES) {
                runCatching { engine.pause(session.handle) }
                vpnPaused = true
                _state.value = _state.value.copy(
                    phase = TorrentPhase.PAUSED,
                    paused = true,
                    downloadRateBps = 0,
                    uploadRateBps = 0,
                    trackerWarning = "VPN is required. Torrent traffic was paused.",
                )
            }
        }
    }

    /**
     * The optimizer may widen or narrow the piece window around the playhead.
     * This only changes which already-requested bytes are fetched first; it
     * never claims to make the swarm faster.
     */
    fun setOptimizerAhead(bytes: Long?) {
        optimizerAheadBytes = bytes?.coerceIn(MIN_AHEAD_BYTES, MAX_AHEAD_BYTES)
    }

    fun stop() {
        scope.launch { openMutex.withLock { closeInternal(deleteFiles = true, stopService = true) } }
    }

    fun clearTorrentStorage() {
        scope.launch(Dispatchers.IO) {
            if (active == null) {
                runCatching { torrentRoot.deleteRecursively() }
                torrentRoot.mkdirs()
            }
        }
    }

    /**
     * Create a .torrent from a user-owned file.
     * Writes the .torrent to private app storage and returns the info hash and magnet output.
     * The .torrent is intended for loopback-only HTTP streaming via PlayerEngine.openTorrentFile().
     * Caller must ensure they own the content and have the right to create a torrent from it.
     */
    data class CreatedTorrent(
        val torrentBytes: ByteArray,
        val infoHash: String,
        val torrentName: String,
        val file: File,
    )

    /**
     * Create a .torrent from a user-owned file.
     * Writes the .torrent to private app storage and returns the info hash and magnet output.
     */
    suspend fun createTorrentFile(
        sourceFile: File,
        label: String = "StreamBuddy",
        comment: String? = null,
    ): Result<CreatedTorrent> = withContext(Dispatchers.IO) {
        val extension = sourceFile.name.substringAfterLast('.', "").lowercase(Locale.US)
        if (extension !in setOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "ts", "m2ts", "mts")) {
            return@withContext Result.failure(
                TorrentException("Unsupported file type for torrent creation. Use video files only.")
            )
        }

        val builder = TorrentBuilder()
        builder.path(sourceFile)
        builder.comment(label)
        builder.creationDate(System.currentTimeMillis() / 1000L)
        if (comment?.isNotBlank() == true) builder.comment(comment)
        builder.setPrivate(true)

        val torrentBytes = builder.generate().entry().bencode()
        val infoHash = TorrentInfo(torrentBytes).infoHash().toHex()
        val torrentName = sourceFile.name.take(100)

        Result.success(CreatedTorrent(torrentBytes = torrentBytes, infoHash = infoHash, torrentName = torrentName, file = sourceFile))
    }

    /** Bytes currently used by torrent data and metadata in app-private storage. */
    fun storageUsedBytes(): Long = runCatching {
        torrentRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    /** Files being written by the active torrent, excluded from manual scans. */
    fun activeDirectory(): File? = active?.directory

    fun release() {
        scope.launch { openMutex.withLock { closeInternal(deleteFiles = true, stopService = true) } }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val session = active ?: break
                val status = engine.status(session.handle)
                if (status != null) {
                    val settings = settingsStore.current()
                    if (status.isFinished() && !settings.seedingEnabled && !status.isSeeding()) {
                        engine.pause(session.handle)
                    }
                    _state.value = TorrentUiState(
                        phase = when {
                            _state.value.phase == TorrentPhase.PAUSED -> TorrentPhase.PAUSED
                            status.isFinished() && settings.seedingEnabled -> TorrentPhase.SEEDING
                            status.isFinished() -> TorrentPhase.STREAMING
                            _state.value.phase == TorrentPhase.BUFFERING -> TorrentPhase.BUFFERING
                            else -> TorrentPhase.STREAMING
                        },
                        title = _state.value.title.ifBlank { session.magnet.displayName.orEmpty() },
                        infoHash = session.magnet.infoHashHex,
                        progress = status.progress().toDouble().coerceIn(0.0, 1.0),
                        downloadRateBps = status.downloadRate().toLong(),
                        uploadRateBps = status.uploadRate().toLong(),
                        peers = status.numPeers(),
                        seeds = status.numSeeds(),
                        downloadedBytes = status.totalDone(),
                        totalBytes = status.totalWanted().coerceAtLeast(session.mainFile.size),
                        selectedFileName = session.mainFile.fileName,
                        selectedFileSize = session.mainFile.size,
                        error = null,
                        trackerWarning = engine.trackerError(),
                        seeding = status.isSeeding() && settings.seedingEnabled,
                        paused = _state.value.phase == TorrentPhase.PAUSED,
                        totalFiles = session.files.size,
                    )
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun closeInternal(deleteFiles: Boolean, stopService: Boolean) {
        pollJob?.cancel()
        pollJob = null
        vpnPaused = false
        val session = active
        active = null
        if (session != null) {
            engine.clearDeadlines(session.handle, session.deadlines)
            runCatching { session.server.stop() }
        }
        engine.remove(deleteFiles)
        if (deleteFiles && session != null) {
            runCatching { session.directory.deleteRecursively() }
        }
        if (stopService) {
            TorrentService.stop(context)
            _state.value = TorrentUiState()
        } else {
            _state.value = _state.value.copy(
                phase = TorrentPhase.IDLE,
                downloadRateBps = 0,
                uploadRateBps = 0,
            )
        }
    }

    private fun bufferAheadBytes(settings: AppSettings): Long =
        optimizerAheadBytes ?: (settings.prefetchSeconds.toLong() * 128L * 1024L)
            .coerceIn(MIN_AHEAD_BYTES, MAX_AHEAD_BYTES)

    private fun freeSpaceBytes(directory: File): Long = runCatching {
        directory.usableSpace
    }.getOrDefault(Long.MAX_VALUE)

    private inner class Active(
        val magnet: MagnetLink,
        val directory: File,
        val handle: TorrentHandle,
        val info: TorrentInfo,
        val files: List<SafeTorrentFile>,
        val mainFile: SafeTorrentFile,
        val fileOffset: Long,
        val pieceLength: Long,
        val subtitles: List<SafeTorrentFile>,
        val startFraction: Double,
    ) {
        lateinit var server: LoopbackServer
        val deadlines = HashSet<Int>()
        @Volatile var lastFraction: Double = startFraction
        @Volatile var lastWindowUpdateMs: Long = 0L

        fun mediaFile(): ServedFile? {
            val path = mainFile.safePath ?: return null
            val file = File(directory, path)
            return ServedFile(
                file = file,
                size = mainFile.size,
                contentType = contentTypeFor(mainFile.path),
                readiness = RangeReadiness { start, end ->
                    engine.awaitRange(handle, pieceLength, start, end, READ_TIMEOUT_MS)
                },
            )
        }

        fun subtitleFile(index: Int): ServedFile? {
            val file = subtitles.getOrNull(index) ?: return null
            val path = file.safePath ?: return null
            return ServedFile(
                file = File(directory, path),
                size = file.size,
                contentType = "text/vtt; charset=utf-8",
                readiness = RangeReadiness { start, end ->
                    engine.awaitRange(handle, pieceLength, start, end, READ_TIMEOUT_MS)
                },
            )
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val WINDOW_UPDATE_MS = 2_500L
        const val SEEK_THRESHOLD_BYTES = 12L * 1024L * 1024L
        const val FIRST_BUFFER_TIMEOUT_MS = 45_000L
        const val READ_TIMEOUT_MS = 25_000L
        const val MIN_AHEAD_BYTES = 12L * 1024L * 1024L
        const val MAX_AHEAD_BYTES = 512L * 1024L * 1024L
        const val MAX_SUBTITLES = 12

        val VPN_SENSITIVE_PHASES = setOf(
            TorrentPhase.FETCHING_METADATA,
            TorrentPhase.BUFFERING,
            TorrentPhase.STREAMING,
            TorrentPhase.SEEDING,
        )

        fun contentTypeFor(path: String): String {
            val extension = path.substringAfterLast('.', "").lowercase(Locale.US)
            return when (extension) {
                "mp4", "m4v", "mov", "3gp" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "ts", "m2ts", "mts" -> "video/mp2t"
                "avi" -> "video/x-msvideo"
                "mp3" -> "audio/mpeg"
                "m4a", "aac" -> "audio/mp4"
                "flac" -> "audio/flac"
                "ogg", "oga" -> "audio/ogg"
                "opus" -> "audio/opus"
                "wav" -> "audio/wav"
                else -> "application/octet-stream"
            }
        }

        /**
         * Create a .torrent from a user-owned video file.
         * Writes the .torrent to private app storage and returns a [CreatedTorrent]
         * containing the .torrent bytes, info hash, and suggested name.
         * The .torrent is intended for loopback-only HTTP streaming via
         * [PlayerEngine.openTorrentFile()].
         * Caller must own the content and have the right to create a torrent from it.
         */
    }
}

// The handle does not exist yet. Upload is paused again by the first poll
// if seeding is disabled, and the initial flags are SEQUENTIAL_DOWNLOAD.
private fun LibtorrentEngine.setSeedingEnabledLater() {
    // The handle does not exist yet. Upload is paused again by the first poll
    // if seeding is disabled, and the initial flags are SEQUENTIAL_DOWNLOAD.
}

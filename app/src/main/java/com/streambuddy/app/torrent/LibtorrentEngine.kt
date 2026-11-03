package com.streambuddy.app.torrent

import android.os.SystemClock
import android.util.Log
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.FileErrorAlert
import org.libtorrent4j.alerts.MetadataFailedAlert
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.alerts.TorrentRemovedAlert
import org.libtorrent4j.alerts.TrackerErrorAlert
import org.libtorrent4j.swig.session_handle
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin, lifecycle-safe wrapper around one libtorrent session and at most one
 * active torrent.
 *
 * Security choices:
 *  - peer traffic is forced to use RC4 encrypted connections when the peer
 *    supports it (`out_enc_policy = forced`), so plaintext BitTorrent protocol
 *    traffic is never sent;
 *  - local service discovery is disabled, so the swarm is not advertised on the
 *    user's LAN;
 *  - all data is written below the app's private `filesDir`;
 *  - unsafe metadata paths are ignored and never exposed through the loopback
 *    server.
 *
 * The engine is deliberately low level. [TorrentManager] owns the user-facing
 * state machine, storage quotas and the HTTP bridge.
 */
class LibtorrentEngine {

    private val session = SessionManager(false)
    private val handleRef = AtomicReference<TorrentHandle?>(null)
    private var listener: AlertListener? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var lastTrackerError: String? = null

    @Volatile
    private var finished = false

    @Volatile
    private var started = false

    val isRunning: Boolean get() = started && session.isRunning

    val handle: TorrentHandle? get() = handleRef.get()

    fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            val settings = hardenedSettings()
            session.start(SessionParams(settings))
            session.maxActiveDownloads(1)
            session.maxActiveSeeds(1)
            session.maxConnections(120)
            session.maxPeers(80)
            listener = buildListener().also { session.addListener(it) }
            started = true
        }
    }

    fun shutdown() {
        synchronized(this) {
            runCatching { handleRef.getAndSet(null)?.let { session.remove(it) } }
            listener?.let { runCatching { session.removeListener(it) } }
            listener = null
            runCatching { session.stop() }
            started = false
        }
    }

    /**
     * Adds a magnet. Metadata is fetched asynchronously; call [awaitMetadata]
     * before reading the file list.
     */
    fun addMagnet(magnetUri: String, directory: File) {
        start()
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create torrent storage directory" }
        val params = AddTorrentParams.parseMagnetUri(magnetUri)
        params.savePath = directory.absolutePath
        params.flags = TorrentFlags.SEQUENTIAL_DOWNLOAD
        lastError = null
        lastTrackerError = null
        finished = false
        handleRef.set(null)
        session.swig().async_add_torrent(params.swig())
    }

    /**
     * Adds a parsed .torrent. Using the file directly means playback does not
     * depend on a reachable tracker or DHT bootstrap before metadata exists.
     */
    fun addTorrentFile(torrentFile: File, directory: File) {
        start()
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create torrent storage directory" }
        val info = TorrentInfo(torrentFile)
        require(info.isValid) { "Invalid .torrent metadata" }
        val params = AddTorrentParams()
        params.torrentInfo = info
        params.savePath = directory.absolutePath
        params.flags = TorrentFlags.SEQUENTIAL_DOWNLOAD
        lastError = null
        lastTrackerError = null
        finished = false
        handleRef.set(null)
        session.swig().async_add_torrent(params.swig())
    }

    fun awaitHandle(timeoutMs: Long): TorrentHandle? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            handleRef.get()?.let { if (it.isValid) return it }
            lastError?.let { return null }
            Thread.sleep(POLL_MS)
        }
        return handleRef.get()?.takeIf { it.isValid }
    }

    fun awaitMetadata(timeoutMs: Long): Result<TorrentInfo> {
        val handle = awaitHandle(timeoutMs) ?: return Result.failure(
            TorrentException(lastError ?: "The tracker did not return the torrent. Check the magnet and try again."),
        )
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            lastError?.let { return Result.failure(TorrentException(it)) }
            val status = runCatching { handle.status() }.getOrNull()
            if (status?.hasMetadata() == true) {
                val info = runCatching { handle.torrentFile() }.getOrNull()
                if (info != null && info.isValid) return Result.success(info)
            }
            Thread.sleep(POLL_MS)
        }
        return Result.failure(
            TorrentException(
                "No metadata after ${timeoutMs / 1000}s. The swarm may be offline or the magnet may have no seeds.",
            ),
        )
    }

    /**
     * Metadata files with the security policy applied. Unsafe paths are kept in
     * the list with a rejection reason so the UI can explain why they are not
     * played, but they are always given [Priority.IGNORE].
     */
    fun files(info: TorrentInfo): List<TorrentFile> {
        val storage = info.files()
        val files = ArrayList<TorrentFile>(storage.numFiles())
        for (index in 0 until storage.numFiles()) {
            val rawPath = runCatching { storage.filePath(index) }.getOrDefault("")
            val size = runCatching { storage.fileSize(index) }.getOrDefault(0L)
            val sanitized = TorrentFilePolicy.sanitize(rawPath, index)
            files += if (sanitized.rejection == null && sanitized.safePath == rawPath) {
                sanitized.copy(size = size)
            } else if (sanitized.rejection != null) {
                sanitized.copy(size = size)
            } else {
                sanitized.copy(
                    size = size,
                    safePath = null,
                    rejection = "Path is not canonical and was rejected",
                )
            }
        }
        return files
    }

    fun fileOffsets(info: TorrentInfo): LongArray {
        val storage = info.files()
        return LongArray(storage.numFiles()) { index ->
            runCatching { storage.fileOffset(index) }.getOrDefault(0L)
        }
    }

    /**
     * Only the selected file is wanted. Subtitles are kept at low priority and
     * everything else is ignored, which stops a malicious torrent from filling
     * the disk with unrelated content.
     */
    fun selectFiles(
        handle: TorrentHandle,
        files: List<TorrentFile>,
        mainIndex: Int,
        subtitleIndexes: Set<Int>,
    ) {
        if (files.isEmpty()) return
        val priorities = Array(files.size) { Priority.IGNORE }
        for (index in files.indices) {
            val file = files[index]
            priorities[index] = when {
                file.rejection != null || file.safePath == null -> Priority.IGNORE
                index == mainIndex -> Priority.DEFAULT
                index in subtitleIndexes -> Priority.LOW
                else -> Priority.IGNORE
            }
        }
        runCatching { handle.prioritizeFiles(priorities) }
        val info = runCatching { handle.torrentFile() }.getOrNull() ?: return
        val storage = info.files()
        if (mainIndex in 0 until storage.numFiles()) {
            val first = runCatching { storage.pieceIndexAtFile(mainIndex) }.getOrDefault(0)
            val last = runCatching { storage.lastPieceIndexAtFile(mainIndex) }.getOrDefault(first)
            runCatching { handle.setSequentialRange(first, last) }
        }
    }

    fun status(handle: TorrentHandle): TorrentStatus? = runCatching { handle.status() }.getOrNull()

    fun havePiece(handle: TorrentHandle, piece: Int): Boolean =
        runCatching { handle.havePiece(piece) }.getOrDefault(false)

    /**
     * Blocks the loopback streaming thread until every piece covering the byte
     * range has been verified. A short poll keeps CPU cost negligible (the
     * server feeds 64 KiB blocks) and a bounded timeout guarantees a stalled
     * swarm cannot pin a thread forever.
     */
    fun awaitRange(
        handle: TorrentHandle,
        pieceLength: Long,
        start: Long,
        endExclusive: Long,
        timeoutMs: Long,
    ): Boolean {
        if (pieceLength <= 0 || endExclusive <= start) return true
        val first = (start / pieceLength).toInt()
        val last = ((endExclusive - 1) / pieceLength).toInt()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = true
            for (piece in first..last) {
                if (!havePiece(handle, piece)) {
                    ready = false
                    break
                }
            }
            if (ready) return true
            Thread.sleep(RANGE_POLL_MS)
        }
        return (first..last).all { havePiece(handle, it) }
    }

    /**
     * Recomputes piece deadlines around the playhead. Deadlines (rather than
     * plain priorities) make libtorrent request the next few seconds before the
     * far end of the buffer, which is what removes most seek stalls.
     */
    fun updatePlayhead(
        handle: TorrentHandle,
        info: TorrentInfo,
        fileOffset: Long,
        fileSize: Long,
        positionInFile: Long,
        bufferAheadBytes: Long,
        previousDeadlines: MutableSet<Int>,
    ) {
        val pieceLength = info.pieceLength().toLong()
        val totalPieces = info.numPieces()
        if (pieceLength <= 0 || totalPieces <= 0 || fileSize <= 0) return
        val window = TorrentPieceWindow.forPlayhead(
            playheadByte = positionInFile,
            fileOffsetByte = fileOffset,
            fileSize = fileSize,
            pieceLength = pieceLength,
            totalPieces = totalPieces,
            aheadBytes = bufferAheadBytes,
        )
        if (window.size <= 0) return

        // Clear deadlines that left the window. Only one window is active at a
        // time, so a bounded set of ints is enough.
        val stale = previousDeadlines.filter { !window.contains(it) }
        for (piece in stale) {
            runCatching { handle.resetPieceDeadline(piece) }
            previousDeadlines.remove(piece)
        }

        val first = window.firstPiece
        val last = minOf(window.lastPiece, first + MAX_DEADLINE_PIECES - 1)
        for (piece in first..last) {
            val deadline = TorrentPieceWindow.deadlineMs(piece, window)
            runCatching { handle.setPieceDeadline(piece, deadline.coerceAtLeast(0)) }
            previousDeadlines.add(piece)
        }
    }

    fun clearDeadlines(handle: TorrentHandle, deadlines: MutableSet<Int>) {
        for (piece in deadlines.toList()) {
            runCatching { handle.resetPieceDeadline(piece) }
        }
        deadlines.clear()
        runCatching { handle.clearPieceDeadlines() }
    }

    fun pause(handle: TorrentHandle) = runCatching { handle.pause() }

    fun resume(handle: TorrentHandle) = runCatching { handle.resume() }

    fun setSeeding(handle: TorrentHandle, enabled: Boolean) {
        runCatching {
            if (enabled) handle.setFlags(TorrentFlags.SEED_MODE)
            else handle.setFlags(TorrentFlags.UPLOAD_MODE, TorrentFlags.SEED_MODE)
        }
    }

    fun setDownloadLimit(kilobytesPerSecond: Int) {
        val bytes = if (kilobytesPerSecond <= 0) 0 else kilobytesPerSecond.coerceAtLeast(8) * 1024
        runCatching { session.downloadRateLimit(bytes) }
        handleRef.get()?.let { runCatching { it.setDownloadLimit(bytes) } }
    }

    fun setUploadLimit(kilobytesPerSecond: Int) {
        val bytes = kilobytesPerSecond.coerceAtLeast(0) * 1024
        runCatching { session.uploadRateLimit(bytes) }
        handleRef.get()?.let { runCatching { it.setUploadLimit(bytes) } }
    }

    fun remove(deleteFiles: Boolean) {
        val handle = handleRef.get() ?: return
        val flags = if (deleteFiles) session_handle.delete_files else null
        runCatching {
            if (flags != null) session.remove(handle, flags) else session.remove(handle)
        }
        handleRef.compareAndSet(handle, null)
        finished = false
    }

    fun lastError(): String? = lastError

    fun trackerError(): String? = lastTrackerError

    val isFinished: Boolean get() = finished

    fun sessionRates(): Pair<Long, Long> = runCatching {
        session.stats()
        session.downloadRate() to session.uploadRate()
    }.getOrDefault(0L to 0L)

    private fun buildListener(): AlertListener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.ADD_TORRENT.swig(),
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.METADATA_FAILED.swig(),
            AlertType.TORRENT_ERROR.swig(),
            AlertType.FILE_ERROR.swig(),
            AlertType.TRACKER_ERROR.swig(),
            AlertType.TORRENT_FINISHED.swig(),
            AlertType.TORRENT_REMOVED.swig(),
        )

        override fun alert(alert: Alert<*>) {
            when (alert) {
                is AddTorrentAlert -> {
                    val error = alert.error()
                    if (error != null && error.isError) {
                        lastError = "Could not add torrent: ${error.message}"
                    } else {
                        handleRef.compareAndSet(null, alert.handle())
                    }
                }
                is MetadataReceivedAlert -> handleRef.compareAndSet(null, alert.handle())
                is MetadataFailedAlert -> lastError =
                    "Metadata download failed: ${alert.getError().message}"
                is TorrentErrorAlert -> lastError = "Torrent error: ${alert.error().message}"
                is FileErrorAlert -> lastError =
                    "Storage error while writing ${alert.filename()}: ${alert.error().message}"
                is TrackerErrorAlert -> {
                    lastTrackerError = alert.errorMessage().take(200)
                    Log.d(TAG, "tracker error without user data: ${alert.error().value}")
                }
                is TorrentFinishedAlert -> finished = true
                is TorrentRemovedAlert -> handleRef.compareAndSet(alert.handle(), null)
                else -> Unit
            }
        }
    }

    private fun hardenedSettings(): SettingsPack = SettingsPack().apply {
        // Refuse plaintext peer connections. The swarm still works with peers
        // that negotiate RC4, and the user's ISP sees only encrypted traffic.
        setInteger(
            settings_pack.int_types.out_enc_policy.swigValue(),
            settings_pack.enc_policy.pe_forced.swigValue(),
        )
        setInteger(
            settings_pack.int_types.in_enc_policy.swigValue(),
            settings_pack.enc_policy.pe_forced.swigValue(),
        )
        setInteger(
            settings_pack.int_types.allowed_enc_level.swigValue(),
            settings_pack.enc_level.pe_both.swigValue(),
        )
        // Never advertise on the local network.
        setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), false)
        setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
        setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), true)
        setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
        setBoolean(settings_pack.bool_types.anonymous_mode.swigValue(), false)
        activeDownloads(1)
        activeSeeds(1)
        alertQueueSize(512)
    }

    private companion object {
        const val TAG = "StreamBuddyTorrent"
        const val POLL_MS = 120L
        const val RANGE_POLL_MS = 150L
        const val MAX_DEADLINE_PIECES = 64
    }
}

class TorrentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Human-readable file size helper shared by the torrent and library screens. */
fun TorrentFile.sizeLabel(): String = com.streambuddy.app.core.Fmt.bytes(size)

fun TorrentStatus.shortState(): String = when (state()) {
    TorrentStatus.State.CHECKING_FILES -> "Checking files"
    TorrentStatus.State.DOWNLOADING_METADATA -> "Fetching metadata"
    TorrentStatus.State.DOWNLOADING -> "Downloading"
    TorrentStatus.State.FINISHED -> "Finished"
    TorrentStatus.State.SEEDING -> "Seeding"
    TorrentStatus.State.CHECKING_RESUME_DATA -> "Checking resume data"
    else -> state().name.lowercase(Locale.US).replace('_', ' ')
}

fun TorrentInfo.fileCountLabel(): String = "${files().numFiles()} files"

fun TorrentFileStorageBytes(info: TorrentInfo): Long = runCatching { info.totalSize() }.getOrDefault(0L)

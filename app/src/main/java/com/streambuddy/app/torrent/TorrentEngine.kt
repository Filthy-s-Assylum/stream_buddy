package com.streambuddy.app.torrent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.util.Log
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.swig.torrent_flags_t
import java.io.File
import java.io.IOException

/**
 * Owns the libtorrent session and turns a magnet link into something a player can read.
 *
 * One session per process. Torrents are added on demand, metadata is resolved in the background,
 * only the chosen video file is prioritised, and playback reads straight from the files libtorrent
 * is writing — blocking until the pieces it needs have arrived. That is the whole trick behind
 * "magnet in, video out".
 *
 * Legal note: this is a general BitTorrent client. It downloads what the magnet points at and
 * enforces no source restrictions; use it for content you have the right to access.
 */
object TorrentEngine {

    private const val TAG = "TorrentEngine"

    /** How long metadata (BEP 9) may take before the queue row reports a failure. */
    private const val METADATA_TIMEOUT_MS = 45_000L

    /** Reads may wait this long without any progress before failing the playback. */
    private const val STALL_TIMEOUT_MS = 45_000L

    /** Deadline granted to pieces ahead of the playhead, in seconds. */
    private const val READAHEAD_DEADLINE_S = 60

    /** How many pieces ahead to keep hot. */
    private const val READAHEAD_PIECES = 48

    private const val UPLOAD_LIMIT_KBPS = 200

    @Volatile
    private var session: SessionManager? = null

    private val tracked = LinkedHashMap<String, Tracked>()

    /** Live state for one torrent, safe to read from the UI. */
    private data class Tracked(
        val magnet: MagnetLink,
        val selection: QualitySelector.Selection?,
        val progress: Progress,
        /** Set when the user overrides the automatic pick via [switchTo]. */
        val preferredFileIndex: Int? = null,
    )

    /** Snapshot of a torrent for the queue and player UI. */
    data class Progress(
        /** `false` when the entry exists in the queue but this process never started the torrent. */
        val running: Boolean = false,
        val hasMetadata: Boolean = false,
        val progressPercent: Int = 0,
        val downloadRateBps: Long = 0L,
        val peers: Int = 0,
        val seeds: Int = 0,
        val totalBytes: Long = 0L,
        val doneBytes: Long = 0L,
        val fileName: String? = null,
        val error: String? = null,
    )

    // region session

    private fun session(context: Context): SessionManager = session ?: synchronized(this) {
        session ?: SessionManager().apply {
            start()
            downloadRateLimit(0)
            maxActiveDownloads(4)
            Log.i(TAG, "libtorrent session started")
        }.also { session = it }
    }

    /** The session if it is already running; never starts native code as a side effect. */
    private fun runningSession(): SessionManager? = session

    /** Directory torrents are written to. App-specific external storage: no permissions needed. */
    fun saveDirectory(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(base, "torrents").apply { mkdirs() }
    }

    /** True when the active connection is metered or absent. */
    fun isMetered(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return true
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // endregion

    // region torrents

    /**
     * Adds (or reuses) a torrent for [magnet]; returns immediately.
     *
     * Metadata resolution and file selection then happen on a background thread
     * ([resolveInBackground]), so by the time the user hits play only the chosen video is being
     * fetched instead of the whole torrent.
     */
    fun ensure(context: Context, magnet: MagnetLink) =
        start(context, magnet, prefetchSelection = true)

    private fun start(context: Context, magnet: MagnetLink, prefetchSelection: Boolean) {
        synchronized(tracked) {
            if (tracked.containsKey(magnet.infoHashHex)) return
            tracked[magnet.infoHashHex] = Tracked(
                magnet = magnet,
                selection = null,
                progress = Progress(),
            )
        }
        try {
            session(context).download(
                magnet.toMagnetUri(),
                saveDirectory(context),
                torrent_flags_t(),
            )
        } catch (e: Throwable) {
            updateProgress(magnet.infoHashHex) { it.copy(error = e.message ?: "Could not add torrent") }
            return
        }
        if (prefetchSelection) resolveInBackground(context, magnet)
    }

    /** The libtorrent handle for [infoHashHex], once the session knows about it. */
    fun handle(context: Context, infoHashHex: String): TorrentHandle? {
        val manager = runningSession() ?: return null
        val hash = try {
            Sha1Hash(infoHashHex)
        } catch (_: Throwable) {
            return null
        }
        val found = manager.find(hash) ?: return null
        return if (found.isValid) found else null
    }

    /**
     * Picks the best playable file for this device and network and prioritises it.
     * Blocking; call from a background dispatcher. Returns the cached selection when already made.
     */
    fun prepare(context: Context, magnet: MagnetLink): QualitySelector.Selection? {
        // Starting the download here is enough; no second resolver thread for the same magnet.
        start(context, magnet, prefetchSelection = false)

        synchronized(tracked) {
            tracked[magnet.infoHashHex]?.selection?.let { return it }
        }

        val handle = awaitMetadata(context, magnet) ?: return null
        val info = handle.torrentFile() ?: return null
        val files = filesOf(info)
        val selection = QualitySelector.select(
            files = files,
            device = DeviceCapabilities.profile(context),
            network = QualitySelector.NetworkProfile(
                metered = isMetered(context),
                downstreamBps = handle.status().downloadRate().toLong(),
            ),
        ) ?: run {
            updateProgress(magnet.infoHashHex) {
                it.copy(error = "No playable video file in this torrent")
            }
            return null
        }

        // Only the chosen file is downloaded; everything else is ignored.
        val priorities = Array(files.size) { index ->
            if (index == selection.chosen.file.index) Priority.NORMAL else Priority.IGNORE
        }
        runCatching { handle.prioritizeFiles(priorities) }
        runCatching { handle.setUploadLimit(UPLOAD_LIMIT_KBPS * 1024) }

        synchronized(tracked) {
            tracked[magnet.infoHashHex] = tracked.getValue(magnet.infoHashHex).copy(
                selection = selection,
            )
        }
        Log.i(
            TAG,
            "Selected ${selection.chosen.file.name} (${selection.chosen.reasons.joinToString("; ")})",
        )
        return selection
    }

    /**
     * Re-prioritises the torrent so that [fileIndex] is the only file downloading.
     *
     * Used when the user overrides the automatic pick. Blocking; call from a background dispatcher.
     */
    fun switchTo(context: Context, magnet: MagnetLink, fileIndex: Int): Boolean {
        val handle = handle(context, magnet.infoHashHex) ?: return false
        val info = handle.torrentFile() ?: return false
        val count = info.files().numFiles()
        if (fileIndex !in 0 until count) return false
        val priorities = Array(count) { index ->
            if (index == fileIndex) Priority.NORMAL else Priority.IGNORE
        }
        val switched = try {
            handle.prioritizeFiles(priorities)
            true
        } catch (_: Exception) {
            false
        }
        if (switched) {
            synchronized(tracked) {
                val current = tracked[magnet.infoHashHex]
                if (current != null) {
                    tracked[magnet.infoHashHex] = current.copy(preferredFileIndex = fileIndex)
                }
            }
        }
        return switched
    }

    /** The selection made by [prepare], if the torrent is ready. */
    fun selectionFor(infoHashHex: String): QualitySelector.Selection? =
        synchronized(tracked) { tracked[infoHashHex]?.selection }

    /**
     * Absolute path of a file inside the torrent, creating the parent directories.
     *
     * libtorrent writes relative to its save path, and multi-file torrents already carry their
     * folder name in the file path (`Show/Season 1/e01.mkv`), so the path is always below it.
     */
    fun filePath(context: Context, file: TorrentFile): File {
        val target = File(saveDirectory(context), file.path)
        target.parentFile?.mkdirs()
        if (!target.exists()) runCatching { target.createNewFile() }
        return target
    }

    /**
     * Blocks until the bytes `[offsetInFile, offsetInFile + length)` of [file] are on disk,
     * giving them a deadline so libtorrent fetches them next.
     *
     * @throws IOException when the torrent stalls, fails, or never gets metadata.
     */
    fun awaitBytes(
        context: Context,
        magnet: MagnetLink,
        file: TorrentFile,
        offsetInFile: Long,
        length: Int,
    ) {
        val handle = handle(context, magnet.infoHashHex)
            ?: throw IOException("Torrent is not running")
        val info = handle.torrentFile() ?: throw IOException("Torrent metadata is not ready")

        val pieceLength = info.pieceLength().toLong().coerceAtLeast(1L)
        val globalStart = file.offsetInTorrent + offsetInFile
        val lastByte = globalStart + length.coerceAtLeast(1) - 1
        val firstPiece = (globalStart / pieceLength).toInt()
        val lastPiece = (lastByte / pieceLength).toInt()

        // Keep the read window hot so libtorrent fetches it before anything else.
        for (piece in firstPiece..minOf(lastPiece + READAHEAD_PIECES, info.numPieces() - 1)) {
            runCatching { handle.setPieceDeadline(piece, READAHEAD_DEADLINE_S) }
        }

        var lastProgressAt = System.currentTimeMillis()
        var lastSeen = doneBytes(handle)
        while (true) {
            var allPresent = true
            for (piece in firstPiece..lastPiece) {
                if (!handle.havePiece(piece)) {
                    allPresent = false
                    break
                }
            }
            if (allPresent) return

            val status = handle.status()
            val done = doneBytes(handle)
            if (done > lastSeen) {
                lastSeen = done
                lastProgressAt = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastProgressAt > STALL_TIMEOUT_MS) {
                throw IOException(
                    "Torrent stalled: ${status.numPeers()} peer(s), no data for " +
                        "${STALL_TIMEOUT_MS / 1000}s",
                )
            }
            Thread.sleep(150)
        }
    }

    /** Snapshot for the queue/player UI. Never starts the native session. */
    fun progress(context: Context, magnet: MagnetLink): Progress {
        val trackedEntry = synchronized(tracked) { tracked[magnet.infoHashHex] }?: return Progress()
        val handle = handle(context, magnet.infoHashHex)
            ?: return trackedEntry.progress
        val status = handle.status()
        val hasMetadata = status.hasMetadata()
        val selected = trackedEntry.preferredFileIndex
            ?.let { index ->
                trackedEntry.selection?.alternatives?.firstOrNull { it.file.index == index }?.file
            }
            ?: trackedEntry.selection?.chosen?.file
        val total = selected?.sizeBytes ?: status.totalWanted()
        val done = selected?.let { file -> fileProgressBytes(handle, file) } ?: status.totalWantedDone()
        val percent = if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
        return Progress(
            running = true,
            hasMetadata = hasMetadata,
            progressPercent = percent,
            downloadRateBps = status.downloadRate().toLong(),
            peers = status.numPeers(),
            seeds = status.numSeeds(),
            totalBytes = total,
            doneBytes = done,
            fileName = selected?.name ?: if (hasMetadata) handle.torrentFile()?.name() else null,
            error = trackedEntry.progress.error,
        )
    }

    /** Stops a torrent and forgets it (downloaded files are kept). */
    fun stop(context: Context, magnet: MagnetLink) {
        val wasTracked = synchronized(tracked) { tracked.remove(magnet.infoHashHex) != null }
        if (!wasTracked) return
        val handle = handle(context, magnet.infoHashHex) ?: return
        runCatching { runningSession()?.remove(handle) }
    }

    /** Releases the native session. Only for tests and process teardown. */
    fun shutdown() {
        synchronized(tracked) { tracked.clear() }
        synchronized(this) {
            runCatching { session?.stop() }
            session = null
        }
    }

    // endregion

    // region helpers

    /**
     * Resolves metadata and chooses a file without blocking the caller, so a magnet added to the
     * queue stops downloading everything and narrows to the file the user will actually watch.
     */
    private fun resolveInBackground(context: Context, magnet: MagnetLink) {
        val appContext = context.applicationContext
        Thread({
            runCatching { prepare(appContext, magnet) }
                .onFailure { Log.w(TAG, "Could not resolve ${magnet.shortHash}", it) }
        }, "torrent-resolve-${magnet.shortHash}").apply { isDaemon = true }.start()
    }

    private fun awaitMetadata(context: Context, magnet: MagnetLink): TorrentHandle? {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < METADATA_TIMEOUT_MS) {
            val handle = handle(context, magnet.infoHashHex)
            if (handle != null && handle.status().hasMetadata()) return handle
            Thread.sleep(250)
        }
        updateProgress(magnet.infoHashHex) {
            it.copy(error = "Could not find metadata — no peers or trackers answered")
        }
        return null
    }

    private fun filesOf(info: TorrentInfo): List<TorrentFile> {
        val storage = info.files()
        return (0 until storage.numFiles()).map { index ->
            TorrentFile(
                index = index,
                path = storage.filePath(index),
                sizeBytes = storage.fileSize(index),
                offsetInTorrent = storage.fileOffset(index),
            )
        }
    }

    private fun doneBytes(handle: TorrentHandle): Long = handle.status().totalWantedDone()

    /** Bytes of [file] already on disk, from libtorrent's per-file progress counters. */
    private fun fileProgressBytes(handle: TorrentHandle, file: TorrentFile): Long = runCatching {
        val progress = handle.fileProgress()
        if (file.index in progress.indices) progress[file.index] else doneBytes(handle)
    }.getOrDefault(0L)

    private fun updateProgress(infoHashHex: String, transform: (Progress) -> Progress) {
        synchronized(tracked) {
            val current = tracked[infoHashHex] ?: return
            tracked[infoHashHex] = current.copy(progress = transform(current.progress))
        }
    }

    private fun MagnetLink.toMagnetUri(): String = buildString {
        append("magnet:?xt=urn:btih:")
        append(infoHashHex)
        displayName?.let { append("&dn=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
        trackers.forEach { append("&tr=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
    }

    // endregion
}

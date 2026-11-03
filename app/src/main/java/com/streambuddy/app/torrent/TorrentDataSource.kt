package com.streambuddy.app.torrent

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Media3 data source that reads a video straight out of a running torrent.
 *
 * ExoPlayer asks for byte ranges; this opens the file libtorrent is filling in and blocks on each
 * read until the pieces covering those bytes are on disk ([TorrentEngine.awaitBytes]). Blocking is
 * fine: ExoPlayer reads on its own loader thread, and that is exactly the buffering behaviour a
 * streaming player wants.
 *
 * URI form: `streambuddy-torrent://<infoHash40hex>/<fileIndex>`.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class TorrentDataSourceFactory(
    private val context: Context,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = TorrentDataSource(context)

    companion object {
        /** Builds the URI the player uses for [magnet] file number [fileIndex]. */
        fun uriFor(magnet: MagnetLink, fileIndex: Int): Uri =
            Uri.parse("${MagnetLink.SCHEME}://${magnet.infoHashHex}/$fileIndex")

        /** Parses `<scheme>://<hash>/<index>`; `null` for anything else. */
        fun parse(uri: Uri): Pair<MagnetLink, Int>? {
            if (!MagnetLink.SCHEME.equals(uri.scheme, ignoreCase = true)) return null
            val hash = uri.host ?: return null
            val fileIndex = uri.lastPathSegment?.toIntOrNull() ?: return null
            val magnet = MagnetLink(infoHashHex = hash, displayName = null, trackers = emptyList(), exactLengthBytes = null)
            return magnet to fileIndex
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class TorrentDataSource(private val context: Context) : DataSource {

    private var uri: Uri? = null
    private var magnet: MagnetLink? = null
    private var file: TorrentFile? = null
    private var handle: RandomAccessFile? = null
    private var position = 0L
    private var remaining = 0L
    private var listener: TransferListener? = null
    private var dataSpec: DataSpec? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listener = transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val (parsedMagnet, fileIndex) = TorrentDataSourceFactory.parse(dataSpec.uri)
            ?: throw IOException("Not a torrent URI: ${dataSpec.uri}")
        val selection = TorrentEngine.selectionFor(parsedMagnet.infoHashHex)
            ?: TorrentEngine.prepare(context, parsedMagnet)
            ?: throw IOException("This torrent has no playable video file")
        val chosen = selection.alternatives.firstOrNull { it.file.index == fileIndex }?.file
            ?: selection.chosen.file

        val path = TorrentEngine.filePath(context, chosen)
        val access = try {
            RandomAccessFile(path, "r")
        } catch (e: IOException) {
            throw IOException("Torrent file is not ready yet: ${path.name}", e)
        }

        uri = dataSpec.uri
        this.dataSpec = dataSpec
        magnet = parsedMagnet
        file = chosen
        handle = access
        position = dataSpec.position.coerceIn(0L, chosen.sizeBytes)
        remaining = when {
            dataSpec.length == C.LENGTH_UNSET.toLong() -> chosen.sizeBytes - position
            else -> minOf(dataSpec.length, chosen.sizeBytes - position)
        }
        access.seek(position)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val access = handle ?: throw IOException("Torrent data source is not open")
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val wanted = minOf(length.toLong(), remaining).toInt()
        if (wanted <= 0) return C.RESULT_END_OF_INPUT

        val currentMagnet = magnet ?: throw IOException("Torrent data source is not open")
        val currentFile = file ?: throw IOException("Torrent data source is not open")

        // Blocks until the pieces covering this window have arrived, so a read never returns
        // bytes that are still being written.
        TorrentEngine.awaitBytes(context, currentMagnet, currentFile, position, wanted)

        val read = access.read(buffer, offset, wanted)
        if (read <= 0) return C.RESULT_END_OF_INPUT
        position += read
        remaining -= read
        // Local disk read, not a network transfer: keep ExoPlayer's bandwidth estimate honest.
        dataSpec?.let { spec -> listener?.onBytesTransferred(this, spec, false, read) }
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { handle?.close() }
        handle = null
        uri = null
        dataSpec = null
        magnet = null
        file = null
        position = 0L
        remaining = 0L
    }
}

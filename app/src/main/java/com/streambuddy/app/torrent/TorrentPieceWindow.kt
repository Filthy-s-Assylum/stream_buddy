package com.streambuddy.app.torrent

import kotlin.math.max
import kotlin.math.min

/**
 * Pure piece selection helper for sequential streaming.
 *
 * libtorrent is told to download sequentially, but sequential downloading alone
 * can still fetch the whole file. This window asks only for pieces between the
 * playhead and the buffer horizon (plus a small read-ahead for the decoder).
 * On a seek the caller computes a new window and calls [reprioritize].
 */
object TorrentPieceWindow {

    data class Window(
        val firstPiece: Int,
        val lastPiece: Int,
        val pieceCount: Int,
    ) {
        val size: Int get() = if (pieceCount <= 0) 0 else lastPiece - firstPiece + 1
        fun contains(piece: Int): Boolean = piece in firstPiece..lastPiece
        fun pieces(): IntProgression = if (pieceCount <= 0) IntRange.EMPTY else firstPiece..lastPiece
    }

    /**
     * @param playheadByte byte offset inside the selected file
     * @param fileOffsetByte byte offset of the selected file inside the torrent
     * @param fileSize bytes of the selected file
     * @param pieceLength torrent piece length
     * @param totalPieces torrent piece count
     * @param aheadBytes how far past the playhead to prioritise
     */
    fun forPlayhead(
        playheadByte: Long,
        fileOffsetByte: Long,
        fileSize: Long,
        pieceLength: Long,
        totalPieces: Int,
        aheadBytes: Long,
        readAheadBytes: Long = 8L * 1024 * 1024,
    ): Window {
        if (pieceLength <= 0 || totalPieces <= 0 || fileSize <= 0) {
            return Window(0, -1, 0)
        }
        val absolute = (fileOffsetByte.coerceAtLeast(0) + playheadByte.coerceAtLeast(0))
            .coerceAtMost(fileOffsetByte + fileSize - 1)
        // Start one piece early so a player that opens slightly behind the
        // reported position still finds data available.
        val first = ((absolute / pieceLength) - 1).coerceAtLeast(0).toInt()
        val horizon = absolute + aheadBytes.coerceAtLeast(readAheadBytes)
        val absoluteEnd = (absolute + readAheadBytes).coerceAtMost(fileOffsetByte + fileSize - 1)
        val last = max(
            (horizon / pieceLength).coerceAtMost((fileOffsetByte + fileSize - 1) / pieceLength),
            absoluteEnd / pieceLength,
        ).toInt()
        val clampedFirst = min(first, totalPieces - 1)
        val clampedLast = min(max(last, clampedFirst), totalPieces - 1)
        return Window(clampedFirst, clampedLast, totalPieces)
    }

    /**
     * A tiny head window lets the player probe the container without waiting
     * for the whole buffer horizon.
     */
    fun probeWindow(pieceLength: Long, totalPieces: Int): Window {
        if (pieceLength <= 0 || totalPieces <= 0) return Window(0, -1, 0)
        return Window(0, min(3, totalPieces - 1), totalPieces)
    }

    /**
     * Deadlines are in milliseconds from now. Data close to the playhead gets a
     * shorter deadline than the far edge of the window. A zero result means
     * "as soon as possible".
     */
    fun deadlineMs(
        piece: Int,
        window: Window,
        pieceDurationMs: Long = 2_000,
    ): Int {
        if (window.size <= 0 || !window.contains(piece)) return 0
        val offset = (piece - window.firstPiece).coerceAtLeast(0)
        return (offset * pieceDurationMs).coerceIn(0L, 120_000L).toInt()
    }
}

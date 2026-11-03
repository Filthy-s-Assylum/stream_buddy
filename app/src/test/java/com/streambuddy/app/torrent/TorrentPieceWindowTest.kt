package com.streambuddy.app.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentPieceWindowTest {

    private val mb = 1024L * 1024L

    @Test
    fun `window starts at the playhead and reaches the buffer horizon`() {
        val window = TorrentPieceWindow.forPlayhead(
            playheadByte = 0,
            fileOffsetByte = 0,
            fileSize = 1024 * mb,
            pieceLength = 4 * mb,
            totalPieces = 256,
            aheadBytes = 32 * mb,
        )
        assertEquals(0, window.firstPiece)
        assertEquals(8, window.lastPiece)
        assertEquals(256, window.pieceCount)
        assertEquals(9, window.size)
        assertTrue(window.contains(0))
        assertTrue(window.contains(8))
        assertFalse(window.contains(9))
    }

    @Test
    fun `window accounts for the file offset inside the torrent`() {
        val window = TorrentPieceWindow.forPlayhead(
            playheadByte = 100 * mb,
            fileOffsetByte = 40 * mb,
            fileSize = 512 * mb,
            pieceLength = 4 * mb,
            totalPieces = 512,
            aheadBytes = 32 * mb,
        )
        // (40 + 100) MiB = piece 35, start one piece early.
        assertEquals(34, window.firstPiece)
        assertEquals(43, window.lastPiece)
    }

    @Test
    fun `seek forward recomputes a lookahead window`() {
        val window = TorrentPieceWindow.forPlayhead(
            playheadByte = 500 * mb,
            fileOffsetByte = 0,
            fileSize = 1024 * mb,
            pieceLength = 4 * mb,
            totalPieces = 256,
            aheadBytes = 32 * mb,
        )
        assertEquals(124, window.firstPiece)
        assertEquals(133, window.lastPiece)
        assertTrue(window.contains(133))
        assertFalse(window.contains(120))
    }

    @Test
    fun `window is clamped to the final piece`() {
        val window = TorrentPieceWindow.forPlayhead(
            playheadByte = 1024 * mb - 1,
            fileOffsetByte = 0,
            fileSize = 1024 * mb,
            pieceLength = 4 * mb,
            totalPieces = 256,
            aheadBytes = 128 * mb,
        )
        assertEquals(254, window.firstPiece)
        assertEquals(255, window.lastPiece)
        assertEquals(2, window.size)
    }

    @Test
    fun `read ahead always covers the decoder probe`() {
        val window = TorrentPieceWindow.forPlayhead(
            playheadByte = 0,
            fileOffsetByte = 0,
            fileSize = 1024 * mb,
            pieceLength = 4 * mb,
            totalPieces = 256,
            aheadBytes = 0,
            readAheadBytes = 12 * mb,
        )
        assertTrue(window.lastPiece >= 3)
    }

    @Test
    fun `invalid inputs produce an empty window`() {
        listOf(
            TorrentPieceWindow.forPlayhead(0, 0, 0, 4 * mb, 10, 10 * mb),
            TorrentPieceWindow.forPlayhead(0, 0, 10 * mb, 0, 10, 10 * mb),
            TorrentPieceWindow.forPlayhead(0, 0, 10 * mb, 4 * mb, 0, 10 * mb),
        ).forEach { window ->
            assertEquals(0, window.size)
            assertFalse(window.contains(0))
            assertTrue(window.pieces().none())
        }
    }

    @Test
    fun `probe window is small`() {
        val window = TorrentPieceWindow.probeWindow(4 * mb, 100)
        assertEquals(0, window.firstPiece)
        assertEquals(3, window.lastPiece)
        assertEquals(4, window.size)

        val tiny = TorrentPieceWindow.probeWindow(4 * mb, 2)
        assertEquals(0, tiny.firstPiece)
        assertEquals(1, tiny.lastPiece)
    }

    @Test
    fun `deadlines increase with distance from the playhead`() {
        val window = TorrentPieceWindow.Window(10, 20, 100)
        assertEquals(0, TorrentPieceWindow.deadlineMs(10, window))
        assertEquals(2_000, TorrentPieceWindow.deadlineMs(11, window))
        assertEquals(20_000, TorrentPieceWindow.deadlineMs(20, window))
        assertEquals(0, TorrentPieceWindow.deadlineMs(9, window))
        assertEquals(0, TorrentPieceWindow.deadlineMs(21, window))
    }

    @Test
    fun `deadlines are capped`() {
        val window = TorrentPieceWindow.Window(0, 500, 1000)
        assertEquals(120_000, TorrentPieceWindow.deadlineMs(499, window))
    }

    @Test
    fun `pieces iterator matches the window bounds`() {
        val window = TorrentPieceWindow.Window(5, 9, 20)
        assertEquals(listOf(5, 6, 7, 8, 9), window.pieces().toList())
    }
}

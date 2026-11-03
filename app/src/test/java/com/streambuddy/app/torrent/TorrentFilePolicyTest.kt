package com.streambuddy.app.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentFilePolicyTest {

    @Test
    fun `classifies file kinds by extension`() {
        assertEquals(FileKind.VIDEO, TorrentFilePolicy.classify("Movie.2024.1080p.mkv"))
        assertEquals(FileKind.VIDEO, TorrentFilePolicy.classify("clip.MP4"))
        assertEquals(FileKind.AUDIO, TorrentFilePolicy.classify("soundtrack.flac"))
        assertEquals(FileKind.SUBTITLE, TorrentFilePolicy.classify("subs/english.srt"))
        assertEquals(FileKind.OTHER, TorrentFilePolicy.classify("readme.txt"))
        assertEquals(FileKind.OTHER, TorrentFilePolicy.classify("no-extension"))
    }

    @Test
    fun `accepts ordinary nested paths`() {
        val file = TorrentFilePolicy.sanitize("Movie (2024)/Movie.2024.mkv", 0)
        assertNull(file.rejection)
        assertEquals("Movie (2024)/Movie.2024.mkv", file.safePath)
        assertEquals(FileKind.VIDEO, file.kind)
        assertEquals("Movie.2024.mkv", file.fileName)
        assertTrue(file.playable)
    }

    @Test
    fun `rejects traversal and absolute paths`() {
        val bad = listOf(
            "../escape.mkv",
            "folder/../../escape.mkv",
            "/etc/passwd",
            "\\windows\\system32\\evil.exe",
            "C:/windows/evil.exe",
            "folder//double.mkv",
            "./relative.mkv",
        )
        bad.forEach { path ->
            val file = TorrentFilePolicy.sanitize(path, 1)
            assertNull("$path should be rejected", file.safePath)
            assertNotNull(file.rejection)
            assertFalse(file.playable)
        }
    }

    @Test
    fun `rejects control characters and NUL bytes`() {
        assertNull(TorrentFilePolicy.sanitize("evil\u0000.mkv", 0).safePath)
        assertNull(TorrentFilePolicy.sanitize("evil\n.mkv", 0).safePath)
        assertNull(TorrentFilePolicy.sanitize("evil\u001f.mkv", 0).safePath)
    }

    @Test
    fun `rejects windows reserved names`() {
        listOf("con.mkv", "NUL.mp4", "aux.srt", "folder/com1.mkv").forEach { path ->
            assertNull(path, TorrentFilePolicy.sanitize(path, 0).safePath)
        }
        // Reserved words are only rejected as the whole stem.
        assertNotNull(TorrentFilePolicy.sanitize("console.mkv", 0).safePath)
    }

    @Test
    fun `rejects trailing dot or space and illegal characters`() {
        listOf("movie .mkv", "movie..mkv", "bad?.mkv", "bad*.mkv", "bad|.mkv", "bad<.mkv").forEach {
            assertNull(TorrentFilePolicy.sanitize(it, 0).safePath)
        }
    }

    @Test
    fun `rejects over long paths and segments`() {
        assertNull(TorrentFilePolicy.sanitize("a".repeat(181) + ".mkv", 0).safePath)
        assertNull(TorrentFilePolicy.sanitize((0 until 30).joinToString("/") { "d$it" } + "/v.mkv", 0).safePath)
        assertNull(TorrentFilePolicy.sanitize("x".repeat(901) + ".mkv", 0).safePath)
        assertNull(TorrentFilePolicy.sanitize("", 0).safePath)
    }

    @Test
    fun `chooses the largest non sample video`() {
        val files = listOf(
            file("Movie.sample.mkv", 50, FileKind.VIDEO),
            file("Movie.mkv", 4_000, FileKind.VIDEO),
            file("extras/featurette.mkv", 300, FileKind.VIDEO),
            file("notes.txt", 1, FileKind.OTHER),
        )
        assertEquals("Movie.mkv", TorrentFilePolicy.chooseMainFile(files)?.path)
    }

    @Test
    fun `falls back to the largest sample when only samples exist`() {
        val files = listOf(
            file("a.sample.mkv", 10, FileKind.VIDEO),
            file("b.sample.mkv", 20, FileKind.VIDEO),
        )
        assertEquals("b.sample.mkv", TorrentFilePolicy.chooseMainFile(files)?.path)
    }

    @Test
    fun `falls back to audio for audio only torrents`() {
        val files = listOf(
            file("track01.flac", 30, FileKind.AUDIO),
            file("track02.flac", 40, FileKind.AUDIO),
            file("cover.jpg", 5, FileKind.OTHER),
        )
        val main = TorrentFilePolicy.chooseMainFile(files)
        assertEquals("track02.flac", main?.path)
        assertTrue(main!!.playable)
    }

    @Test
    fun `ignores rejected files when choosing the main file`() {
        val files = listOf(
            file("good.mkv", 100, FileKind.VIDEO),
            file("../escape.mkv", 900, FileKind.VIDEO).copy(safePath = null, rejection = "traversal"),
        )
        assertEquals("good.mkv", TorrentFilePolicy.chooseMainFile(files)?.path)
    }

    @Test
    fun `lists only sanitized subtitles`() {
        val files = listOf(
            file("english.srt", 10, FileKind.SUBTITLE),
            file("spanish.vtt", 10, FileKind.SUBTITLE),
            file("../evil.srt", 10, FileKind.SUBTITLE).copy(safePath = null, rejection = "bad"),
        )
        assertEquals(2, TorrentFilePolicy.subtitles(files).size)
    }

    @Test
    fun `directory names are filesystem safe`() {
        val name = TorrentFilePolicy.safeDirectoryName(
            "0123456789abcdef0123456789abcdef01234567",
            "Some: Movie / *weird* name?",
        )
        assertTrue(name.startsWith("0123456789abcdef-"))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('/'))
        assertFalse(name.contains('*'))
        assertTrue(name.length <= 100)
    }

    @Test
    fun `blank torrent name still yields a directory`() {
        assertEquals("abcdef0123456789-torrent", TorrentFilePolicy.safeDirectoryName("ABCDEF0123456789", "   "))
        assertEquals("abcdef0123456789-torrent", TorrentFilePolicy.safeDirectoryName("abcdef0123456789", null))
    }

    private fun file(path: String, size: Long, kind: FileKind) = TorrentFile(
        index = 0,
        path = path,
        size = size,
        safePath = path,
        kind = kind,
    )
}

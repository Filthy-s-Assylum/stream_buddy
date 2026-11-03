package com.streambuddy.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLinkScraperTest {

    private val base = "https://example.test/shows/1"

    @Test
    fun `finds magnet torrent stream media and subtitle links`() {
        val html = """
            <html><body>
              <a href="magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Owned">m</a>
              <a href="/files/owned.torrent">t</a>
              <video src="media/owned.mp4"></video>
              <source src="//cdn.example.test/stream/index.m3u8?token=1">
              <a href="https://cdn.example.test/sub/owned.vtt">s</a>
              <script>var u = "https://cdn.example.test/deep/manifest.mpd";</script>
            </body></html>
        """.trimIndent()

        val links = PageLinkScraper.extractFromHtml(html, base)
        val byUrl = links.associateBy { it.url }

        assertTrue(byUrl.values.any { it.kind == ScrapedKind.MAGNET })
        assertEquals(ScrapedKind.TORRENT, byUrl["https://example.test/files/owned.torrent"]?.kind)
        assertEquals(ScrapedKind.MEDIA, byUrl["https://example.test/shows/media/owned.mp4"]?.kind)
        assertEquals(ScrapedKind.HLS, byUrl["https://cdn.example.test/stream/index.m3u8?token=1"]?.kind)
        assertEquals(ScrapedKind.DASH, byUrl["https://cdn.example.test/deep/manifest.mpd"]?.kind)
        assertEquals(ScrapedKind.SUBTITLE, byUrl["https://cdn.example.test/sub/owned.vtt"]?.kind)
    }

    @Test
    fun `drops ordinary navigation and active schemes`() {
        val html = """
            <a href="notes.txt">note</a>
            <a href="data:text/html,<script>alert(1)</script>">data</a>
            <a href="javascript:alert(1)">js</a>
            <a href="mailto:someone@example.test">mail</a>
            <a href="/about">about</a>
        """.trimIndent()

        assertTrue(PageLinkScraper.extractFromHtml(html, base).isEmpty())
    }

    @Test
    fun `deduplicates identical links`() {
        val html = """
            <a href="/a.torrent">1</a>
            <a href="/a.torrent">2</a>
            <source src="/a.torrent">
        """.trimIndent()

        val links = PageLinkScraper.extractFromHtml(html, base)
        assertEquals(1, links.size)
        assertEquals("https://example.test/a.torrent", links.single().url)
    }

    @Test
    fun `classifies torrent paths without an extension`() {
        val links = PageLinkScraper.classify(
            listOf("https://example.test/torrent/456", "https://example.test/download.php?id=9"),
            base,
        )
        assertTrue(links.all { it.kind == ScrapedKind.TORRENT })
    }

    @Test
    fun `ignores query strings when classifying`() {
        val links = PageLinkScraper.classify(
            listOf("https://cdn.example.test/a.m3u8?token=abc", "https://cdn.example.test/b.mp4?x=1"),
            base,
        )
        assertEquals(ScrapedKind.HLS, links[0].kind)
        assertEquals(ScrapedKind.MEDIA, links[1].kind)
    }

    @Test
    fun `does not invent links without a base`() {
        val links = PageLinkScraper.classify(listOf("/relative.torrent"), null)
        assertTrue(links.isEmpty())
    }

    @Test
    fun `caps the number of results`() {
        val html = (1..300).joinToString("") { "<a href=\"/f$it.mp4\">x</a>" }
        val links = PageLinkScraper.extractFromHtml(html, base)
        assertTrue(links.size <= PageLinkScraper.MAX_LINKS)
        assertFalse(links.isEmpty())
    }
}

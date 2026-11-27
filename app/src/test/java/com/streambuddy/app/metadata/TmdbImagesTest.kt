package com.streambuddy.app.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbImagesTest {

    @Test
    fun `normalizePath accepts safe image paths and rejects traversal or non-images`() {
        assertEquals("/abc123.jpg", TmdbImages.normalizePath("/abc123.jpg"))
        assertEquals("/nested/poster_v2.webp", TmdbImages.normalizePath("/nested/poster_v2.webp"))
        assertNull(TmdbImages.normalizePath("../etc/passwd.jpg"))
        assertNull(TmdbImages.normalizePath("no-leading-slash.jpg"))
        assertNull(TmdbImages.normalizePath("/script.html"))
        assertNull(TmdbImages.normalizePath("/bad?query=1.jpg"))
    }

    @Test
    fun `posterUrl and backdropUrl select expected TMDB size buckets`() {
        assertEquals("https://image.tmdb.org/t/p/w185/p.jpg", TmdbImages.posterUrl("/p.jpg", 185))
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", TmdbImages.posterUrl("/p.jpg", 342))
        assertEquals("https://image.tmdb.org/t/p/w780/p.jpg", TmdbImages.posterUrl("/p.jpg", 780))
        assertEquals("https://image.tmdb.org/t/p/original/p.jpg", TmdbImages.posterUrl("/p.jpg", 1200))
        assertEquals("https://image.tmdb.org/t/p/w300/b.png", TmdbImages.backdropUrl("/b.png", 320))
        assertEquals("https://image.tmdb.org/t/p/w780/b.png", TmdbImages.backdropUrl("/b.png", 780))
        assertNull(TmdbImages.posterUrl("../evil.jpg"))
    }
}

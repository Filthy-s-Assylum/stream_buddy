package com.streambuddy.app.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SourceValidationTest {

    private val hash = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun `accepts valid magnet http https content and file uris`() {
        assertNull(validateSource("magnet:?xt=urn:btih:$hash&dn=Example"))
        assertNull(validateSource("https://cdn.example.test/video/stream.m3u8"))
        assertNull(validateSource("http://192.168.1.20:8080/movie.mp4"))
        assertNull(validateSource("content://com.android.providers.media.documents/document/video%3A42"))
        assertNull(validateSource("file:///storage/emulated/0/Movies/sample.mp4"))
    }

    @Test
    fun `rejects empty invalid schemes and malformed magnets`() {
        assertNotNull(validateSource("   "))
        assertNotNull(validateSource("magnet:?dn=NoHash"))
        assertNotNull(validateSource("https:///no-host.mp4"))
        assertNotNull(validateSource("javascript:alert(1)"))
        assertNotNull(validateSource("ftp://example.test/file.mp4"))
    }
}

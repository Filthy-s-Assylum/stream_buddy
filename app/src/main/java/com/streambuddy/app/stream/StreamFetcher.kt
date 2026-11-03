package com.streambuddy.app.stream

import com.streambuddy.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Small HTTP helper for the non-media requests the pre-cache engine needs: probing headers and
 * downloading playlist text (played segments go through Media3, not through here).
 */
class StreamFetcher(private val client: OkHttpClient = Http.client) {

    data class TextResult(val text: String, val finalUrl: String)

    data class ProbeResult(
        val contentType: String?,
        val contentLength: Long,
        val finalUrl: String,
    )

    /**
     * Determines content type and length. Uses HEAD first and falls back to a one-byte ranged GET,
     * because plenty of CDNs reject HEAD requests.
     */
    suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        val head: ProbeResult? = try {
            client.newCall(Http.request(url).head().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    null
                } else {
                    ProbeResult(
                        contentType = response.header("Content-Type"),
                        contentLength = response.body?.contentLength() ?: -1L,
                        finalUrl = response.request.url.toString(),
                    )
                }
            }
        } catch (_: IOException) {
            null
        }

        if (head != null && (head.contentLength > 0L || head.contentType != null)) {
            return@withContext head
        }

        try {
            client.newCall(Http.request(url).header("Range", "bytes=0-0").build()).execute().use { response ->
                val contentRange = response.header("Content-Range")
                val totalFromRange = contentRange?.substringAfterLast('/')?.toLongOrNull()
                ProbeResult(
                    contentType = response.header("Content-Type"),
                    contentLength = when {
                        totalFromRange != null && totalFromRange > 0L -> totalFromRange
                        else -> response.body?.contentLength() ?: -1L
                    },
                    finalUrl = response.request.url.toString(),
                )
            }
        } catch (e: IOException) {
            throw PreCacheNetworkException("Cannot reach the stream: ${e.message ?: "network error"}", e)
        }
    }

    /**
     * Downloads a playlist as UTF-8 text, following redirects. The final URL is returned because
     * relative segment URIs must resolve against it. [maxBytes] bounds the read so a server that
     * returns something huge cannot exhaust memory.
     */
    suspend fun fetchText(url: String, maxBytes: Int = MAX_PLAYLIST_BYTES): TextResult =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(Http.request(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw PreCacheNetworkException("Server returned HTTP ${response.code} for the playlist")
                    }
                    val body = response.body
                        ?: throw PreCacheNetworkException("Empty playlist response")
                    val input = body.byteStream()
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (total < maxBytes) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val allowed = minOf(read, maxBytes - total)
                        output.write(buffer, 0, allowed)
                        total += allowed
                    }
                    TextResult(
                        text = String(output.toByteArray(), Charsets.UTF_8),
                        finalUrl = response.request.url.toString(),
                    )
                }
            } catch (e: PreCacheNetworkException) {
                throw e
            } catch (e: IOException) {
                throw PreCacheNetworkException(
                    "Playlist download failed: ${e.message ?: "network error"}",
                    e,
                )
            }
        }

    companion object {
        /** Playlists are small; anything past this is served by mistake. */
        const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024
    }
}

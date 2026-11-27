package com.streambuddy.app.metadata

import com.streambuddy.app.data.MediaType
import com.streambuddy.app.data.MetadataCandidate
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentIndexTest {

    private val hexHash = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun `defaultClient disables HTTP and SSL redirects`() {
        val client = TorrentIndexClient.defaultClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun `parses YTS movie torrents and drops invalid hashes`() = runTest {
        val json = """
            {
              "data": {
                "movies": [
                  {
                    "imdb_code": "tt1234567",
                    "title": "Sample Film",
                    "title_long": "Sample Film (2026)",
                    "year": 2026,
                    "torrents": [
                      {
                        "hash": "$hexHash",
                        "quality": "1080p",
                        "type": "web",
                        "size_bytes": 2147483648,
                        "seeds": 120,
                        "peers": 30
                      },
                      {
                        "hash": "too-short",
                        "quality": "720p",
                        "type": "web",
                        "size_bytes": 1000,
                        "seeds": 500,
                        "peers": 10
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val client = TorrentIndexClient(fakeClient(json))
        val releases = client.releases(candidate(MediaType.MOVIE), "tt1234567").getOrThrow()

        assertEquals(1, releases.size)
        assertEquals("YTS", releases[0].provider)
        assertEquals("1080P WEB", releases[0].quality)
        assertTrue(releases[0].magnet.startsWith("magnet:?xt=urn:btih:$hexHash"))
    }

    @Test
    fun `parses EZTV show torrents and formats episode labels`() = runTest {
        val json = """
            {
              "torrents": [
                {
                  "filename": "Sample.Show.S01E03.1080p.mkv",
                  "hash": "$hexHash",
                  "season": 1,
                  "episode": 3,
                  "size_bytes": 1048576000,
                  "seeds": 42,
                  "peers": 7
                }
              ]
            }
        """.trimIndent()

        val client = TorrentIndexClient(fakeClient(json))
        val releases = client.releases(candidate(MediaType.TV), "tt7654321").getOrThrow()

        assertEquals(1, releases.size)
        assertEquals("EZTV", releases[0].provider)
        assertEquals("S01E03", releases[0].episodeLabel)
        assertEquals("1080p", releases[0].quality)
    }

    @Test
    fun `rejects responses from unexpected hosts`() = runTest {
        val evilClient = OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor {
                val evil = Request.Builder().url("https://evil-eztv.attacker.example/api").build()
                Response.Builder()
                    .request(evil)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val client = TorrentIndexClient(evilClient)
        val result = client.releases(candidate(MediaType.TV), "tt7654321")
        assertTrue(result.isFailure)
    }

    private fun candidate(type: MediaType) = MetadataCandidate(
        tmdbId = 1,
        mediaType = type,
        title = "Sample Film",
        overview = "",
        posterPath = null,
        backdropPath = null,
        year = 2026,
        genres = emptyList(),
        network = null,
        rating = null,
        runtimeMinutes = null,
        seasons = null,
    )

    private fun fakeClient(body: String): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()
}

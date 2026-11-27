package com.streambuddy.app.metadata

import com.streambuddy.app.data.MediaType
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TmdbClientTest {

    @Test
    fun `defaultClient disables HTTP and SSL redirects`() {
        val client = TmdbClient.defaultClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun `now playing parses movie results without media_type`() = runBlocking {
        val body = """{"results":[{"id":11,"title":"In cinemas","release_date":"2026-11-20"}]}"""
        val client = TmdbClient({ "test-key" }, fakeClient(body))

        val results = client.nowPlayingMovies().getOrThrow()

        assertEquals(1, results.size)
        assertEquals(MediaType.MOVIE, results.single().mediaType)
        assertEquals(2026, results.single().year)
        assertEquals("2026-11-20", results.single().releaseDate)
    }

    @Test
    fun `upcoming parses movie results without media_type`() = runBlocking {
        val body = """{"results":[{"id":22,"title":"Coming soon","release_date":"2027-02-14"}]}"""
        val client = TmdbClient({ "test-key" }, fakeClient(body))

        val results = client.upcomingMovies().getOrThrow()

        assertEquals(1, results.size)
        assertEquals(MediaType.MOVIE, results.single().mediaType)
        assertEquals("2027-02-14", results.single().releaseDate)
    }

    @Test
    fun `on the air parses tv results without media_type`() = runBlocking {
        val body = """{"results":[{"id":33,"name":"Weekly show","first_air_date":"2026-09-01"}]}"""
        val client = TmdbClient({ "test-key" }, fakeClient(body))

        val results = client.onTheAirShows().getOrThrow()

        assertEquals(1, results.size)
        assertEquals(MediaType.TV, results.single().mediaType)
        assertEquals("Weekly show", results.single().title)
    }

    @Test
    fun `mixed list still uses explicit media_type and skips people`() = runBlocking {
        val body = """
            {"results":[
              {"id":1,"media_type":"movie","title":"Film"},
              {"id":2,"media_type":"tv","name":"Series"},
              {"id":3,"media_type":"person","name":"Actor"}
            ]}
        """.trimIndent()
        val client = TmdbClient({ "test-key" }, fakeClient(body))

        val results = client.trending().getOrThrow()

        assertEquals(listOf(MediaType.MOVIE, MediaType.TV), results.map { it.mediaType })
        assertEquals(listOf("Film", "Series"), results.map { it.title })
    }

    @Test
    fun `imdbId validates IMDb identifier shape`() = runBlocking {
        val candidate = com.streambuddy.app.data.MetadataCandidate(
            tmdbId = 10,
            mediaType = MediaType.MOVIE,
            title = "Film",
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
        val valid = TmdbClient({ "test-key" }, fakeClient("""{"imdb_id":"tt1234567"}"""))
        assertEquals("tt1234567", valid.imdbId(candidate).getOrThrow())

        val invalid = TmdbClient({ "test-key" }, fakeClient("""{"imdb_id":"not-imdb"}"""))
        assertEquals(null, invalid.imdbId(candidate).getOrThrow())
    }

    private fun fakeClient(body: String): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()
}

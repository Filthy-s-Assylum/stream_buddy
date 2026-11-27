package com.streambuddy.app.metadata

import com.streambuddy.app.data.MediaType
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

class MotnClientTest {

    @Test
    fun `defaultClient disables HTTP and SSL redirects to protect API key`() {
        val client = MotnClient.defaultClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun `TmdbClient defaultClient also disables HTTP and SSL redirects`() {
        val client = TmdbClient.defaultClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun `searchByTitle parses options and ignores unsafe links`() = runTest {
        val json = """
            {
              "shows": [
                {
                  "id": "show-1",
                  "title": "Sample Show",
                  "overview": "Test overview",
                  "firstAirYear": 2024,
                  "showType": "series",
                  "streamingOptions": {
                    "us": [
                      {
                        "service": { "name": "ExampleFlix" },
                        "type": "subscription",
                        "quality": "hd",
                        "link": "https://example.test/watch/1"
                      },
                      {
                        "service": { "name": "BadLink" },
                        "type": "rent",
                        "link": "javascript:alert(1)",
                        "price": { "formatted": "${'$'}3.99" }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor { chain ->
                val req = chain.request()
                assertEquals("test-key", req.header("X-API-Key"))
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val motn = MotnClient({ "test-key" }, client)
        val result = motn.searchByTitle("Sample", "US", MediaType.TV)
        assertTrue(result.isSuccess)
        val shows = result.getOrThrow()
        assertEquals(1, shows.size)
        assertEquals(2024, shows[0].year)
        assertEquals(2, shows[0].options.size)
        assertEquals("https://example.test/watch/1", shows[0].options[0].link)
        assertEquals(null, shows[0].options[1].link)
        assertEquals("$3.99", shows[0].options[1].price)
    }

    @Test
    fun `blocks unexpected host responses even if a synthetic client rewrites request URL`() = runTest {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor { chain ->
                val evilRequest = Request.Builder().url("https://evil.example.test/steal").build()
                Response.Builder()
                    .request(evilRequest)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val motn = MotnClient({ "secret-key" }, client)
        val result = motn.searchByTitle("Test", "us", MediaType.MOVIE)
        assertTrue(result.isFailure)
    }
}

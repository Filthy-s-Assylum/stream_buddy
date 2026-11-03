package com.streambuddy.app.metadata

import com.streambuddy.app.data.MediaType
import com.streambuddy.app.data.SettingsStore
import com.streambuddy.app.net.NetworkGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One place a show can be watched, as reported by Movie of the Night. */
data class WatchOption(
    val serviceName: String,
    val type: String,
    val quality: String?,
    val link: String?,
    val price: String?,
) {
    val typeLabel: String
        get() = when (type.lowercase(Locale.US)) {
            "subscription" -> "Subscription"
            "rent" -> "Rent"
            "buy" -> "Buy"
            "free" -> "Free"
            "ads" -> "With ads"
            else -> type.replaceFirstChar { it.titlecase(Locale.US) }
        }
}

data class MotnShow(
    val id: String?,
    val title: String,
    val overview: String,
    val year: Int?,
    val showType: String,
    val options: List<WatchOption>,
)

/**
 * Read-only Movie of the Night (Streaming Availability) client.
 *
 * - Uses the official v4 host with the user's own `X-API-Key`.
 * - The key is stored encrypted and is never logged or sent anywhere else.
 * - Requests pass through [NetworkGuard], so the "Require VPN" setting covers
 *   availability lookups too.
 * - Response size is capped and redirects are pinned to the API host.
 */
class MotnClient(
    private val apiKeyProvider: () -> String,
    private val client: OkHttpClient = defaultClient(),
) {

    val hasKey: Boolean get() = apiKeyProvider().isNotBlank()

    suspend fun searchByTitle(
        title: String,
        country: String,
        mediaType: MediaType?,
    ): Result<List<MotnShow>> = request {
        val query = title.trim()
        require(query.length in 1..200) { "Enter a title to look up" }
        val url = buildUrl("shows/search/title") {
            addQueryParameter("title", query)
            addQueryParameter("country", normalizeCountry(country))
            addQueryParameter("output_language", "en")
            addQueryParameter("series_granularity", "show")
            when (mediaType) {
                MediaType.MOVIE -> addQueryParameter("show_type", "movie")
                MediaType.TV -> addQueryParameter("show_type", "series")
                null -> Unit
            }
        }
        val body = fetch(url)
        val shows = body.optJSONArray("shows") ?: JSONArray()
        buildList {
            for (index in 0 until shows.length()) {
                val json = shows.optJSONObject(index) ?: continue
                parseShow(json, country)?.let { add(it) }
                if (size >= 20) break
            }
        }
    }

    suspend fun show(id: String, country: String): Result<MotnShow> = request {
        val safeId = validateId(id)
        val url = buildUrl("shows/$safeId") {
            addQueryParameter("country", normalizeCountry(country))
            addQueryParameter("output_language", "en")
            addQueryParameter("series_granularity", "show")
        }
        val json = fetch(url)
        parseShow(json, country) ?: throw IOException("Movie of the Night did not return a show")
    }

    private fun parseShow(json: JSONObject, country: String): MotnShow? {
        val title = json.optString("title").trim()
        if (title.isBlank()) return null
        val year = json.optInt("releaseYear", 0).takeIf { it > 0 }
            ?: json.optInt("firstAirYear", 0).takeIf { it > 0 }
        return MotnShow(
            id = json.optString("id").takeIf { it.isNotBlank() },
            title = title.take(300),
            overview = json.optString("overview").take(4_000),
            year = year,
            showType = json.optString("showType").ifBlank { "movie" },
            options = parseOptions(json.optJSONObject("streamingOptions"), country),
        )
    }

    private fun parseOptions(map: JSONObject?, country: String): List<WatchOption> {
        if (map == null) return emptyList()
        val normalized = normalizeCountry(country)
        val key = when {
            map.has(normalized) -> normalized
            map.has(normalized.uppercase(Locale.US)) -> normalized.uppercase(Locale.US)
            else -> map.keys().asSequence().firstOrNull()
        } ?: return emptyList()
        val array = map.optJSONArray(key) ?: return emptyList()
        val options = ArrayList<WatchOption>(array.length())
        for (index in 0 until array.length()) {
            val option = array.optJSONObject(index) ?: continue
            val service = option.optJSONObject("service")
                ?.optString("name")
                ?.trim()
                .orEmpty()
                .ifBlank { "Streaming service" }
            val type = option.optString("type").ifBlank { "subscription" }
            val link = option.optString("link").trim().takeIf { isHttp(it) }
            val quality = option.optString("quality").trim().takeIf { it.isNotBlank() }
            val price = option.optJSONObject("price")
                ?.optString("formatted")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            options += WatchOption(
                serviceName = service.take(80),
                type = type.take(40),
                quality = quality?.take(20),
                link = link,
                price = price?.take(40),
            )
            if (options.size >= 60) break
        }
        return options.distinctBy { "${it.serviceName}|${it.type}|${it.link}|${it.price}" }
            .take(40)
    }

    private fun buildUrl(path: String, block: okhttp3.HttpUrl.Builder.() -> Unit): String {
        val key = apiKeyProvider().trim()
        require(key.isNotEmpty()) { "Add a Movie of the Night API key in Settings" }
        val builder = "https://$HOST/v4/$path".toHttpUrlOrNull()
            ?.newBuilder()
            ?: error("Invalid Movie of the Night endpoint")
        builder.block()
        return builder.build().toString()
    }

    private suspend fun fetch(url: String): JSONObject = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) throw IOException("Add a Movie of the Night API key in Settings")
        NetworkGuard.check()
        val request = Request.Builder()
            .url(url)
            .header("X-API-Key", key)
            .header("Accept", "application/json")
            .header("User-Agent", "StreamBuddy/1.0 (Android)")
            .build()
        client.newCall(request).execute().use { response ->
            // Pin the response to the official API host even if a redirect is
            // attempted, so the key cannot be replayed to another server.
            val finalHost = response.request.url.host.lowercase(Locale.US)
            if (finalHost != HOST) {
                throw IOException("Blocked a redirect to an unexpected host")
            }
            if (!response.isSuccessful) {
                throw IOException("Movie of the Night HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Movie of the Night returned an empty response")
            val length = body.contentLength()
            if (length > MAX_RESPONSE_BYTES) throw IOException("Response is too large")
            val text = body.string()
            if (text.length > MAX_RESPONSE_BYTES) throw IOException("Response is too large")
            JSONObject(text)
        }
    }

    private suspend fun <T> request(block: suspend () -> T): Result<T> = runCatching {
        withContext(Dispatchers.IO) { block() }
    }.recoverCatching { throwable ->
        throw MotnException(friendlyMessage(throwable), throwable)
    }

    private fun friendlyMessage(throwable: Throwable): String {
        val message = throwable.message.orEmpty()
        return when {
            message.contains("VPN", ignoreCase = true) ->
                "A VPN connection is required. Connect your trusted VPN and try again."
            message.contains("API key", ignoreCase = true) ->
                "Add a Movie of the Night API key in Settings"
            message.contains("401") || message.contains("403") ->
                "Movie of the Night rejected the API key. Check it in Settings."
            message.contains("404") ->
                "Movie of the Night has no availability for this title."
            message.contains("429") ->
                "Movie of the Night rate limit reached. Try again in a minute."
            throwable is IOException ->
                "Could not reach Movie of the Night. Check your connection and try again."
            else -> "Streaming availability is unavailable right now."
        }
    }

    private fun validateId(raw: String): String {
        val id = raw.trim()
        require(ID_PATTERN.matches(id)) { "Invalid show id" }
        return id
    }

    private fun normalizeCountry(raw: String): String {
        val code = raw.trim().lowercase(Locale.US).filter { it.isLetter() }.take(2)
        return code.ifBlank { "us" }
    }

    private fun isHttp(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true)

    companion object {
        const val HOST = "api.movieofthenight.com"
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private val ID_PATTERN = Regex("^[A-Za-z0-9/_-]{1,80}$")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(NetworkGuard.interceptor())
            .build()
    }
}

class MotnException(message: String, cause: Throwable? = null) : IOException(message, cause)

class MotnRepository(private val settingsStore: SettingsStore) {
    private val client = MotnClient({ settingsStore.current().motnApiKey })

    val hasKey: Boolean get() = client.hasKey

    val country: String get() = settingsStore.current().motnCountry

    suspend fun whereToWatch(title: String, mediaType: MediaType?): Result<List<WatchOption>> =
        client.searchByTitle(title, country, mediaType).map { shows ->
            val best = shows.firstOrNull()
            best?.options.orEmpty()
        }
}

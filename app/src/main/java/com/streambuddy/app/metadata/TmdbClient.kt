package com.streambuddy.app.metadata

import com.streambuddy.app.data.MediaType
import com.streambuddy.app.data.MetadataCandidate
import com.streambuddy.app.data.SettingsStore
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

/**
 * Metadata-only integration with TMDB. The user supplies their own API key,
 * which is stored encrypted. Stream Buddy never ships a key, never searches for
 * torrents here, and never sends the user's library or watch history anywhere.
 */
class TmdbClient(
    private val apiKeyProvider: () -> String,
    private val client: OkHttpClient = defaultClient(),
) {

    val hasKey: Boolean get() = apiKeyProvider().isNotBlank()

    suspend fun search(query: String): Result<List<MetadataCandidate>> = request {
        val encoded = query.trim()
        require(encoded.length in 1..200) { "Enter a title to search" }
        val url = buildUrl("search/multi") {
            addQueryParameter("query", encoded)
            addQueryParameter("include_adult", "false")
            addQueryParameter("language", "en-US")
        }
        val body = fetch(url)
        parseList(body.optJSONArray("results"), allowPeople = true)
    }

    suspend fun trending(): Result<List<MetadataCandidate>> = request {
        val url = buildUrl("trending/all/week") { addQueryParameter("language", "en-US") }
        parseList(fetch(url).optJSONArray("results"), allowPeople = true)
    }

    suspend fun details(candidate: MetadataCandidate): Result<MetadataCandidate> = request {
        val path = when (candidate.mediaType) {
            MediaType.MOVIE -> "movie/${candidate.tmdbId}"
            MediaType.TV -> "tv/${candidate.tmdbId}"
        }
        val url = buildUrl(path) { addQueryParameter("language", "en-US") }
        val json = fetch(url)
        if (candidate.mediaType == MediaType.MOVIE) parseMovie(json) else parseTv(json)
    }

    private fun parseList(array: JSONArray?, allowPeople: Boolean): List<MetadataCandidate> {
        if (array == null) return emptyList()
        val results = ArrayList<MetadataCandidate>(array.length())
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val type = json.optString("media_type")
            val mediaType = when (type) {
                "movie" -> MediaType.MOVIE
                "tv" -> MediaType.TV
                else -> if (allowPeople) null else null
            } ?: continue
            val id = json.optInt("id", 0)
            if (id <= 0) continue
            val title = if (mediaType == MediaType.MOVIE) json.optString("title") else json.optString("name")
            if (title.isBlank()) continue
            val date = if (mediaType == MediaType.MOVIE) {
                json.optString("release_date")
            } else {
                json.optString("first_air_date")
            }
            results += MetadataCandidate(
                tmdbId = id,
                mediaType = mediaType,
                title = title.take(300),
                overview = json.optString("overview").take(4_000),
                posterPath = imagePath(json.optString("poster_path")),
                backdropPath = imagePath(json.optString("backdrop_path")),
                year = date.take(4).toIntOrNull(),
                genres = emptyList(),
                network = null,
                rating = json.optDouble("vote_average").takeIf { it.isFinite() && it > 0 },
                runtimeMinutes = null,
                seasons = null,
            )
            if (results.size >= 40) break
        }
        return results
    }

    private fun parseMovie(json: JSONObject): MetadataCandidate {
        val genres = json.optJSONArray("genres").names()
        val companies = json.optJSONArray("production_companies").names()
        return MetadataCandidate(
            tmdbId = json.optInt("id"),
            mediaType = MediaType.MOVIE,
            title = json.optString("title").take(300),
            overview = json.optString("overview").take(4_000),
            posterPath = imagePath(json.optString("poster_path")),
            backdropPath = imagePath(json.optString("backdrop_path")),
            year = json.optString("release_date").take(4).toIntOrNull(),
            genres = genres,
            network = companies.firstOrNull(),
            rating = json.optDouble("vote_average").takeIf { it.isFinite() && it > 0 },
            runtimeMinutes = json.optInt("runtime", 0).takeIf { it > 0 },
            seasons = null,
        )
    }

    private fun parseTv(json: JSONObject): MetadataCandidate {
        val genres = json.optJSONArray("genres").names()
        val networks = json.optJSONArray("networks").names()
        val runtimes = json.optJSONArray("episode_run_time")
        val runtime = if (runtimes != null && runtimes.length() > 0) {
            runtimes.optInt(0, 0).takeIf { it > 0 }
        } else null
        return MetadataCandidate(
            tmdbId = json.optInt("id"),
            mediaType = MediaType.TV,
            title = json.optString("name").take(300),
            overview = json.optString("overview").take(4_000),
            posterPath = imagePath(json.optString("poster_path")),
            backdropPath = imagePath(json.optString("backdrop_path")),
            year = json.optString("first_air_date").take(4).toIntOrNull(),
            genres = genres,
            network = networks.firstOrNull(),
            rating = json.optDouble("vote_average").takeIf { it.isFinite() && it > 0 },
            runtimeMinutes = runtime,
            seasons = json.optInt("number_of_seasons", 0).takeIf { it > 0 },
        )
    }

    private fun JSONArray?.names(): List<String> {
        if (this == null) return emptyList()
        val names = ArrayList<String>(length())
        for (index in 0 until length()) {
            val name = optJSONObject(index)?.optString("name").orEmpty().trim()
            if (name.isNotEmpty()) names += name.take(80)
        }
        return names.distinct().take(12)
    }

    private fun buildUrl(path: String, block: okhttp3.HttpUrl.Builder.() -> Unit): String {
        val key = apiKeyProvider().trim()
        require(key.isNotEmpty()) { "Add a TMDB API key in Settings to load posters and descriptions" }
        val builder = "https://api.themoviedb.org/3/$path".toHttpUrlOrNull()
            ?.newBuilder()
            ?: error("Invalid metadata endpoint")
        builder.addQueryParameter("api_key", key)
        builder.block()
        return builder.build().toString()
    }

    private suspend fun fetch(url: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "StreamBuddy/1.0 (Android)")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("TMDB HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("TMDB returned an empty response")
            val length = body.contentLength()
            if (length > MAX_RESPONSE_BYTES) throw IOException("Metadata response is too large")
            val text = body.string()
            if (text.length > MAX_RESPONSE_BYTES) throw IOException("Metadata response is too large")
            JSONObject(text)
        }
    }

    private suspend fun <T> request(block: suspend () -> T): Result<T> = runCatching {
        withContext(Dispatchers.IO) { block() }
    }.recoverCatching { throwable ->
        throw MetadataException(friendlyMessage(throwable), throwable)
    }

    private fun friendlyMessage(throwable: Throwable): String {
        val message = throwable.message.orEmpty()
        return when {
            message.contains("api key", ignoreCase = true) ||
                message.contains("API key", ignoreCase = true) -> "Add a TMDB API key in Settings"
            message.contains("401") -> "TMDB rejected the API key. Check it in Settings."
            message.contains("404") -> "This title is no longer available on TMDB."
            message.contains("429") -> "TMDB rate limit reached. Try again in a minute."
            throwable is IOException -> "Could not reach TMDB. Check your connection and try again."
            message.contains("VPN", ignoreCase = true) -> message
            else -> "Metadata is unavailable right now."
        }
    }

    private fun imagePath(raw: String?): String? {
        val path = raw?.trim().orEmpty()
        if (path.isEmpty()) return null
        return TmdbImages.normalizePath(path)
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(com.streambuddy.app.net.NetworkGuard.interceptor())
            .build()
    }
}

class MetadataException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * TMDB image paths are untrusted strings from a remote API. Only the known
 * image host and a conservative path shape may be requested, which prevents
 * the image loader from becoming an SSRF primitive.
 */
object TmdbImages {
    const val HOST = "image.tmdb.org"
    private val SAFE_PATH = Regex("^/[A-Za-z0-9._/-]{1,160}$")
    private val ALLOWED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    fun normalizePath(raw: String): String? {
        if (!SAFE_PATH.matches(raw)) return null
        if (raw.contains("..")) return null
        val extension = raw.substringAfterLast('.', "").lowercase(Locale.US)
        return if (extension in ALLOWED_EXTENSIONS) raw else null
    }

    fun posterUrl(path: String?, width: Int = 342): String? {
        val safe = path?.let(::normalizePath) ?: return null
        val size = when {
            width <= 200 -> "w185"
            width <= 400 -> "w342"
            width <= 800 -> "w780"
            else -> "original"
        }
        return "https://$HOST/t/p/$size$safe"
    }

    fun backdropUrl(path: String?, width: Int = 780): String? {
        val safe = path?.let(::normalizePath) ?: return null
        val size = if (width <= 400) "w300" else "w780"
        return "https://$HOST/t/p/$size$safe"
    }
}

class MetadataRepository(private val settingsStore: SettingsStore) {
    private val client = TmdbClient({ settingsStore.current().tmdbApiKey })

    val hasKey: Boolean get() = client.hasKey

    suspend fun search(query: String): Result<List<MetadataCandidate>> = client.search(query)

    suspend fun trending(): Result<List<MetadataCandidate>> = client.trending()

    suspend fun enrich(candidate: MetadataCandidate): Result<MetadataCandidate> =
        client.details(candidate)
}

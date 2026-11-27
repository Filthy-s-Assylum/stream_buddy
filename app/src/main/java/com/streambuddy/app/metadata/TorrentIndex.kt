package com.streambuddy.app.metadata

import com.streambuddy.app.core.Fmt
import com.streambuddy.app.data.MediaType
import com.streambuddy.app.data.MetadataCandidate
import com.streambuddy.app.net.NetworkGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One magnet a user can choose for a catalogue title. */
data class TorrentRelease(
    val provider: String,
    val title: String,
    val magnet: String,
    val quality: String,
    val sizeBytes: Long,
    val seeds: Int,
    val peers: Int,
    val episodeLabel: String? = null,
) {
    val detailLabel: String
        get() = buildString {
            if (quality.isNotBlank()) append(quality)
            if (episodeLabel != null) {
                if (isNotEmpty()) append(" · ")
                append(episodeLabel)
            }
            if (sizeBytes > 0) {
                if (isNotEmpty()) append(" · ")
                append(Fmt.bytes(sizeBytes))
            }
            if (seeds > 0) {
                if (isNotEmpty()) append(" · ")
                append(seeds).append(" seeds")
            }
        }
}

/**
 * Finds a magnet for a title the user picked in the TMDB catalogue.
 *
 * This is deliberately an on-demand lookup, not a background crawler: nothing
 * runs until the user taps "Watch now", "Download", or "Magnet links", and
 * every request passes through [NetworkGuard] with redirects disabled and
 * exact host allowlisting.
 */
class TorrentIndexClient(
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun releases(
        item: MetadataCandidate,
        imdbId: String?,
    ): Result<List<TorrentRelease>> = runCatching {
        withContext(Dispatchers.IO) {
            val safeImdb = imdbId?.trim()?.takeIf { IMDB.matches(it) }
            when (item.mediaType) {
                MediaType.MOVIE -> movies(item, safeImdb)
                MediaType.TV -> shows(item, safeImdb)
            }
        }
    }.recoverCatching { throwable ->
        throw TorrentLookupException(friendlyMessage(throwable), throwable)
    }

    private suspend fun movies(item: MetadataCandidate, imdbId: String?): List<TorrentRelease> {
        val queries = buildList {
            if (imdbId != null) add(imdbId)
            add(item.title)
        }.distinct()
        var lastError: Throwable? = null
        for (query in queries) {
            try {
                val results = queryYts(query, item, imdbId)
                if (results.isNotEmpty()) return results
            } catch (throwable: Throwable) {
                lastError = throwable
            }
        }
        if (lastError != null) throw lastError
        return emptyList()
    }

    private suspend fun queryYts(query: String, item: MetadataCandidate, imdbId: String?): List<TorrentRelease> {
        val url = "https://yts.mx/api/v2/list_movies.json".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("query_term", query)
            ?.addQueryParameter("limit", "20")
            ?.addQueryParameter("sort_by", "seeds")
            ?.addQueryParameter("order_by", "desc")
            ?.build()
            ?.toString()
            ?: throw IOException("Invalid YTS endpoint")
        val json = fetch(url) { host -> host in YTS_HOSTS }
        val movies = json.optJSONObject("data")?.optJSONArray("movies") ?: JSONArray()
        val releases = ArrayList<TorrentRelease>()
        for (index in 0 until movies.length()) {
            val movie = movies.optJSONObject(index) ?: continue
            if (!matchesMovie(movie, item, imdbId)) continue
            val title = movie.optString("title_long").ifBlank {
                movie.optString("title") + " (" + movie.optInt("year") + ")"
            }
            val torrents = movie.optJSONArray("torrents") ?: continue
            for (t in 0 until torrents.length()) {
                val torrent = torrents.optJSONObject(t) ?: continue
                val hash = torrent.optString("hash").trim()
                if (!INFO_HASH.matches(hash)) continue
                val quality = torrent.optString("quality").trim()
                val type = torrent.optString("type").trim()
                releases += TorrentRelease(
                    provider = "YTS",
                    title = title.take(300),
                    magnet = buildMagnet(hash, title),
                    quality = listOf(quality, type).filter { it.isNotBlank() }
                        .joinToString(" ").uppercase(Locale.US),
                    sizeBytes = torrent.optLong("size_bytes", 0L),
                    seeds = torrent.optInt("seeds", 0),
                    peers = torrent.optInt("peers", 0),
                )
            }
        }
        return releases.sortedWith(compareByDescending<TorrentRelease> { it.seeds }.thenByDescending { it.sizeBytes })
            .take(MAX_RELEASES)
    }

    private suspend fun shows(item: MetadataCandidate, imdbId: String?): List<TorrentRelease> {
        if (imdbId == null) {
            throw TorrentLookupException(
                "TMDB did not return an IMDb id for this show, so no magnet can be matched. " +
                    "Open the browser to search your own source instead.",
            )
        }
        val numeric = imdbId.removePrefix("tt")
        val url = "https://eztv.re/api/get-torrents".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("imdb_id", numeric)
            ?.addQueryParameter("limit", "100")
            ?.build()
            ?.toString()
            ?: throw IOException("Invalid EZTV endpoint")
        val json = fetch(url) { host -> host in EZTV_HOSTS }
        val torrents = json.optJSONArray("torrents") ?: JSONArray()
        val releases = ArrayList<TorrentRelease>()
        for (index in 0 until torrents.length()) {
            val torrent = torrents.optJSONObject(index) ?: continue
            val filename = torrent.optString("filename").trim()
            val hash = torrent.optString("hash").trim()
            val magnet = torrent.optString("magnet_url").trim().takeIf { it.startsWith("magnet:?") }
                ?: hash.takeIf { INFO_HASH.matches(it) }?.let { buildMagnet(it, filename) }
                ?: continue
            val season = torrent.optInt("season", 0)
            val episode = torrent.optInt("episode", 0)
            val episodeLabel = if (season > 0) {
                if (episode > 0) "S%02dE%02d".format(Locale.US, season, episode) else "Season $season"
            } else null
            releases += TorrentRelease(
                provider = "EZTV",
                title = filename.ifBlank { item.title }.take(300),
                magnet = magnet,
                quality = qualityFrom(filename),
                sizeBytes = torrent.optLong("size_bytes", 0L),
                seeds = torrent.optInt("seeds", 0),
                peers = torrent.optInt("peers", 0),
                episodeLabel = episodeLabel,
            )
        }
        return releases.sortedWith(
            compareByDescending<TorrentRelease> { it.seeds }
                .thenByDescending { it.episodeLabel.orEmpty() },
        ).take(MAX_RELEASES)
    }

    private fun matchesMovie(movie: JSONObject, item: MetadataCandidate, imdbId: String?): Boolean {
        val code = movie.optString("imdb_code").trim()
        if (imdbId != null && code.isNotEmpty()) return code.equals(imdbId, ignoreCase = true)
        val title = movie.optString("title").lowercase(Locale.US)
        val wanted = item.title.lowercase(Locale.US)
        if (!title.contains(wanted) && !wanted.contains(title)) return false
        val year = movie.optInt("year", 0)
        return item.year == null || year == 0 || kotlin.math.abs(year - item.year) <= 1
    }

    private fun qualityFrom(filename: String): String {
        val lower = filename.lowercase(Locale.US)
        return when {
            lower.contains("2160p") || lower.contains("4k") -> "2160p"
            lower.contains("1080p") -> "1080p"
            lower.contains("720p") -> "720p"
            lower.contains("480p") -> "480p"
            else -> ""
        }
    }

    private fun buildMagnet(hash: String, name: String): String {
        val encodedName = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val trackers = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
        )
        return buildString {
            append("magnet:?xt=urn:btih:").append(hash)
            append("&dn=").append(encodedName)
            trackers.forEach { tracker ->
                append("&tr=").append(URLEncoder.encode(tracker, "UTF-8").replace("+", "%20"))
            }
        }
    }

    private suspend fun fetch(url: String, allowedHosts: (String) -> Boolean): JSONObject =
        withContext(Dispatchers.IO) {
            NetworkGuard.check()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "StreamBuddy/1.0 (Android)")
                .build()
            client.newCall(request).execute().use { response ->
                if (!allowedHosts(response.request.url.host.lowercase(Locale.US))) {
                    throw IOException("Blocked a redirect to an unexpected host")
                }
                if (!response.isSuccessful) {
                    throw IOException("${response.request.url.host} HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("The index returned an empty response")
                if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Response is too large")
                val text = body.byteStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    val out = ByteArrayOutputStream()
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_RESPONSE_BYTES) throw IOException("Response is too large")
                        out.write(buffer, 0, read)
                    }
                    out.toString(Charsets.UTF_8.name())
                }
                JSONObject(text)
            }
        }

    private fun friendlyMessage(throwable: Throwable): String {
        val message = throwable.message.orEmpty()
        return when {
            throwable is TorrentLookupException -> message
            message.contains("VPN", ignoreCase = true) ->
                "A VPN connection is required. Connect your trusted VPN and try again."
            message.contains("HTTP 429") -> "The torrent index is rate limiting. Try again in a minute."
            message.contains("HTTP 5") -> "The torrent index is unavailable right now."
            throwable is IOException ->
                "Could not reach the torrent index. Check your connection, VPN and the site's current domain."
            else -> "No magnet could be found for this title."
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_RELEASES = 30
        private val INFO_HASH = Regex("^[A-Fa-f0-9]{40}$|^[A-Za-z2-7]{32}$")
        private val IMDB = Regex("^tt[0-9]{7,10}$")
        private val YTS_HOSTS = setOf(
            "yts.mx", "www.yts.mx", "yts.lt", "www.yts.lt", "yts.am", "www.yts.am",
            "yts.ag", "www.yts.ag", "yts.rs", "www.yts.rs",
        )
        private val EZTV_HOSTS = setOf(
            "eztv.re", "www.eztv.re", "eztvx.to", "www.eztvx.to",
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .addInterceptor(NetworkGuard.interceptor())
            .build()

        /** Public search page used when no magnet can be resolved automatically. */
        fun browseUrl(item: MetadataCandidate): String {
            val query = URLEncoder.encode(item.title, "UTF-8").replace("+", "%20")
            return if (item.mediaType == MediaType.MOVIE) {
                "https://yts.mx/browse-movies/$query/all/all/0/latest/0/all"
            } else {
                "https://eztv.re/search/$query"
            }
        }
    }
}

class TorrentLookupException(message: String, cause: Throwable? = null) : IOException(message, cause)
